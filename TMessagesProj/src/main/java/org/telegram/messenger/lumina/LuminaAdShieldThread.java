/*
 * LuminaGram - AdShield ("隱藏重複廣告") engine thread.
 * Copyright (C) 2026 LuminaGram contributors. Licensed under GPL-3.0-or-later.
 */
package org.telegram.messenger.lumina;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The single dedicated AdShield engine thread (SPEC §8.4: all computation and I/O on one queue).
 * Created with a 16 MB stack: {@code java.util.regex} recurses once per repetition of
 * {@code (?:...)+}, so link extraction on inputs like {@code a.a.a....com} overflows the default
 * 1 MB stack of background threads (SPEC §4.2, vectors stress-domain / stress-email).
 *
 * <p>{@link LuminaAdShieldStore} and {@link LuminaAdShieldAccount} are confined to this thread; the UI
 * thread only copies plain fields into {@link LuminaAdShield.MessageInput} and {@link #post}s.
 * Every posted task is wrapped in {@code catch (Throwable)} so one bad message can never kill the queue.
 */
public final class LuminaAdShieldThread {

    public static final long STACK_SIZE = 16L << 20;
    public static final String NAME = "LuminaAdShield";

    private static final ThreadLocal<Boolean> IS_ENGINE = new ThreadLocal<Boolean>();
    private static ScheduledThreadPoolExecutor executor;

    private LuminaAdShieldThread() {
    }

    public static synchronized ScheduledThreadPoolExecutor executor() {
        if (executor == null) {
            ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable r) {
                    Thread t = new Thread(null, new Runnable() {
                        @Override
                        public void run() {
                            IS_ENGINE.set(Boolean.TRUE);
                            r.run();
                        }
                    }, NAME, STACK_SIZE);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }
            });
            e.setRemoveOnCancelPolicy(true);
            executor = e;
        }
        return executor;
    }

    public static boolean isEngineThread() {
        return Boolean.TRUE.equals(IS_ENGINE.get());
    }

    static Runnable guard(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    LuminaAdShield.log("adshield task failed: " + t.getClass().getSimpleName());
                }
            }
        };
    }

    public static void post(Runnable r) {
        executor().execute(guard(r));
    }

    /** For debounced flushes (SPEC §8.4: 5 s per chat); cancel the returned future to reschedule. */
    public static ScheduledFuture<?> postDelayed(Runnable r, long delayMs) {
        return executor().schedule(guard(r), delayMs, TimeUnit.MILLISECONDS);
    }

    /** Runs c on the engine thread and waits (directly when already on it). Not for the UI thread. */
    public static <T> T call(Callable<T> c) throws Exception {
        if (isEngineThread()) {
            return c.call();
        }
        Future<T> f = executor().submit(c);
        try {
            return f.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }
}
