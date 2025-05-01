package edu.wisc.cs.sdn.vnet.tcp;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class RetransmissionManager {
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ConcurrentHashMap<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> retries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Runnable> actions = new ConcurrentHashMap<>();
    private final TCPLogger logger;

    private double ertt = 0;
    private double edev = 0;
    private long timeout = 5000;

    public RetransmissionManager(TCPLogger logger) {
        this.logger = logger;
    }

    public void scheduleRetransmission(int seq, Runnable action, int maxRetries) {
        actions.put(seq, action);
        retries.put(seq, new AtomicInteger(0));
        schedule(seq);
    }

    private void schedule(int seq) {
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            handleTimeout(seq);
        }, timeout, TimeUnit.MILLISECONDS);

        timers.put(seq, future);
    }

    private void handleTimeout(int seq) {
        AtomicInteger count = retries.get(seq);
        if (count.incrementAndGet() > 16) {
            logger.logError("Max retries for seq " + seq);
            cleanup(seq);
            return;
        }
        actions.get(seq).run();
        schedule(seq);
    }

    public void cancelRetransmissionsBelow(int ackNum) {
        timers.keySet().removeIf(seq -> seq < ackNum);
        retries.keySet().removeIf(seq -> seq < ackNum);
        actions.keySet().removeIf(seq -> seq < ackNum);
    }

    public void forceRetransmit(int seq) {
        ScheduledFuture<?> timer = timers.get(seq);
        if (timer != null) {
            timer.cancel(false);
            handleTimeout(seq);
        }
    }

    public void updateRTT(long rttNanos) {
        long rttMillis = TimeUnit.NANOSECONDS.toMillis(rttNanos);

        synchronized (this) {
            if (ertt == 0) {
                ertt = rttMillis;
                edev = 0;
                timeout = (long) (2 * ertt);
            } else {
                double srtt = rttMillis;
                double sdev = Math.abs(srtt - ertt);
                ertt = 0.875 * ertt + 0.125 * srtt;
                edev = 0.75 * edev + 0.25 * sdev;
                timeout = (long) (ertt + 4 * edev);
            }
        }
    }

    private void cleanup(int seq) {
        timers.remove(seq);
        retries.remove(seq);
        actions.remove(seq);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
