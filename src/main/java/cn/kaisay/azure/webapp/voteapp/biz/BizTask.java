package cn.kaisay.azure.webapp.voteapp.biz;

import cn.kaisay.azure.webapp.voteapp.model.Vote;
import cn.kaisay.azure.webapp.voteapp.web.VoteServlet;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;
import java.util.stream.Collectors;


public class BizTask {

    private static Logger logger = LogManager.getLogger();
    private final LocalDateTime init = LocalDateTime.now();
    private LocalDateTime start;
    private final StampedLock stampedLock = new StampedLock();
    private volatile Status status = Status.NEW;
    private AsyncContext ac;
    private Vote vote;
    private LocalDateTime timeout = init.plusSeconds(10);
    private ScheduledFuture sf ;

    private Duration processingTime;

    public BizTask(AsyncContext ac) {
        this.ac = ac;
        HttpServletRequest acc = ((HttpServletRequest) ac.getRequest());
        String email = acc.getParameter("email");
        String sel = acc.getParameter("sel");
        //TODO validate for the Vote Data
        vote = new Vote(email, sel);
        startMonitor();
    }

    private void startMonitor() {
        sf = VoteServlet.slowMonitorScheduler.schedule(() -> {
            long st = stampedLock.writeLock();
            try {
                logger.debug("mark the process is timeout, add to the slow monitor");
                status = Status.TIMEOUT;
                VoteServlet.slowNumber.incrementAndGet();
            } finally {
                stampedLock.unlock(st);
            }
        }, 3, TimeUnit.SECONDS);
    }

    public Duration howMuchTimeLeft() {
        Duration left = Duration.between(LocalDateTime.now(),timeout);
        return left.isNegative()?Duration.ZERO:left;
    }


    public AsyncContext getAc() {
        return ac;
    }


    public HttpServletResponse getResp() {
        return (HttpServletResponse) (getAc().getResponse());
    }

    public HttpServletRequest getReq() {
        return (HttpServletRequest) (getAc().getRequest());
    }

    public void processing() {
        start = LocalDateTime.now();
        status = Status.PROCESSING;
        //这种处理也有问题，如果很快完成的任务，也会继续添加monitor线程
        //TODO connect to MySQL and persistence
        try (BufferedReader br = getReq().getReader()) {
            processingTime = Duration.ofMillis(
                    ThreadLocalRandom.current().nextLong(2950, 11100));
            String json = br.lines().collect(Collectors.joining());
            Gson gson = new Gson();
            Vote v = gson.fromJson(json, Vote.class);
            logger.debug(() -> v);
            logger.debug("the processing will wait for " + processingTime);
            Thread.sleep(processingTime.toMillis());
        } catch (InterruptedException e) {
            status = Status.EXCEPTION;
        } catch (JsonSyntaxException e) {
            status = Status.EXCEPTION;
            logger.error(() -> "Json format is not right", e);
        } catch (IOException e) {
            status = Status.EXCEPTION;
            logger.error(() -> "error when encoding the json file.", e);
        } catch (Exception e) {
            status = Status.EXCEPTION;
        } finally {
            logger.debug("begin done");
            done();
            sf.cancel(true);
            logger.debug("end done");
        }

        logger.debug("Actually finish task with waiting for " + processingTime);

    }

    private void done() {
        logger.debug(() -> "done()@" + Thread.currentThread().getName());
        long stamp = stampedLock.tryOptimisticRead();
        Status st = status;
//        long stamp = 0;
        try {
            //如果当前现在没有别的线程持有写锁，则validate 返回true
            if (!stampedLock.validate(stamp)) {
                logger.warn("Ops!....there's another thread try to update the status.");
                stamp = stampedLock.readLock(); //取得悲观读锁，说明此时已经没有比的写锁，拷贝变量进入自己的方法栈
                try {
                    st = status;   //以此时获取的状态为准，此时如果没有超时，则就是没有超时
                } finally {
                    stampedLock.unlock(stamp);
                }
            }
            setDone(st);
        } finally {
            logger.debug("finish done...");
        }

    }

    private void setDone(Status s) {
        if (s == Status.TIMEOUT) {
            logger.debug("----timeout ok");
            slowOk();
            VoteServlet.slowNumber.decrementAndGet();
            logger.debug("timeout ok");
        } else if (s == Status.EXCEPTION) {
            error();
        } else {
            logger.debug("====ok");
            ok();
            logger.debug("----ok");
        }

    }


    public void ok() {
        getResp().addHeader("time0", Duration.between(init, LocalDateTime.now()).toString());
        getResp().setStatus(HttpServletResponse.SC_OK);
        getAc().complete();
    }

    public void slowOk() {
        LocalDateTime t = LocalDateTime.now();
        logger.warn(() -> "slowOk after "+Duration.between(init, t));
        getResp().addHeader("slowQueuq", "y");
        getResp().addHeader("time0", Duration.between(init, t).toString());
        getResp().addHeader("timeout", "start @ " + init + " timeout @ " + t);
        getResp().setStatus(HttpServletResponse.SC_OK);
        getAc().complete();
    }

    public void unavailable() {
        logger.error(() -> "server is unavailable now");
        getResp().addHeader("time0", Duration.between(init, LocalDateTime.now()).toString());
        getResp().setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        getAc().complete();
    }

    public void error() {
        logger.error(() -> "error when processing");
        getResp().addHeader("time0", Duration.between(init, LocalDateTime.now()).toString());
        getResp().setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        getAc().complete();
    }

    public void timeouted() {
        LocalDateTime t = LocalDateTime.now();
        logger.error(() -> "timeout after total " + Duration.between(init, t)
                + " | processing time windows is " + processingTime);
        getResp().setStatus(599);
        getResp().addHeader("time0", Duration.between(init, t).toString());
        getResp().addHeader("timeout", "start @ " + init + " timeout @ " + t);
        getAc().complete();
    }

/*    public Duration timeLeft() {
        Duration past = Duration.between(LocalDateTime.now(), timeout);
        return past.isNegative() ? Duration.ZERO : past;
    }*/

    enum Status {
        NEW,
        STARTING,
        PROCESSING,
        TIMEOUT,
        TIMEOUTED,
        EXCEPTION,
        DONE
    }
}

