package com.ultraview.lod.core;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Pool pequeno e FIXO de trabalhadores com fila priorizada e limite de tarefas pendentes. Nunca toca em OpenGL. */
public final class BackgroundTaskManager {
    private static final class Job implements Runnable {
        final int priority;
        final long seq;
        final Runnable body;
        final AtomicInteger pending;
        Job(int priority, long seq, Runnable body, AtomicInteger pending) {
            this.priority = priority; this.seq = seq; this.body = body; this.pending = pending;
        }
        public void run() {
            try { body.run(); }
            catch (Throwable t) { System.err.println("[UltraViewLOD] erro em tarefa: " + t); }
            finally { pending.decrementAndGet(); }
        }
    }

    private final ThreadPoolExecutor executor;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicLong seq = new AtomicLong();
    private final int maxPending;

    public BackgroundTaskManager(int workers, int maxPending) {
        this.maxPending = Math.max(1, maxPending);
        final AtomicInteger n = new AtomicInteger();
        executor = new ThreadPoolExecutor(Math.max(1, workers), Math.max(1, workers), 30, TimeUnit.SECONDS,
            new PriorityBlockingQueue<Runnable>(64, new Comparator<Runnable>() {
                public int compare(Runnable a, Runnable b) {
                    Job x = (Job) a, y = (Job) b;
                    if (x.priority != y.priority) return Integer.compare(x.priority, y.priority);
                    return Long.compare(x.seq, y.seq);
                }
            }),
            new ThreadFactory() {
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "UVLOD-Worker-" + n.incrementAndGet());
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 1);
                    return t;
                }
            });
        executor.allowCoreThreadTimeOut(true);
    }

    /** Menor valor de prioridade executa primeiro. Retorna false se a fila estiver cheia. */
    public boolean submit(int priority, Runnable r) {
        if (pending.incrementAndGet() > maxPending) { pending.decrementAndGet(); return false; }
        try {
            executor.execute(new Job(priority, seq.incrementAndGet(), r, pending));
            return true;
        } catch (RejectedExecutionException e) {
            pending.decrementAndGet();
            return false;
        }
    }

    public int pending() { return pending.get(); }

    public void shutdown(long waitMillis) {
        executor.shutdown();
        try { executor.awaitTermination(waitMillis, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
    }
}
