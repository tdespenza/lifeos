package com.lifeos.labs.concurrency;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.StructuredTaskScope;
import jdk.jfr.Recording;

/**
 * A bounded, executable comparison of the main Java concurrency building blocks.
 *
 * <p>Every strategy processes at most {@value #MAX_TASKS} tasks and each task has a finite
 * deadline. The result intentionally reports cancellation and timeout rather than hiding them in
 * an exception or waiting indefinitely. The structured-concurrency experiment uses the Java 25
 * preview API and is compiled/run with preview enabled by this module.
 */
public final class ConcurrencyLab {

    public static final int MAX_TASKS = 32;
    public static final Duration TASK_DELAY = Duration.ofMillis(2);
    public static final Duration DEADLINE = Duration.ofSeconds(2);

    private static final ScopedValue<String> CORRELATION = ScopedValue.newInstance();

    private ConcurrencyLab() {}

    public static void main(String[] args) {
        int taskCount = args.length == 0 ? 16 : boundedTaskCount(Integer.parseInt(args[0]));
        List<RunResult> results = List.of(
                run("platform-threads", taskCount, Executors.newFixedThreadPool(Math.min(8, taskCount))),
                run("virtual-threads", taskCount, Executors.newVirtualThreadPerTaskExecutor()),
                runCompletableFuture(taskCount),
                runStructuredConcurrency(taskCount),
                runScopedValuePropagation(taskCount));
        results.forEach(result -> System.out.println(result.toJson()));
    }

    public static RunResult runPlatformThreads(int taskCount) {
        int bounded = boundedTaskCount(taskCount);
        return run("platform-threads", bounded, Executors.newFixedThreadPool(Math.min(8, bounded)));
    }

    public static RunResult runVirtualThreads(int taskCount) {
        return run("virtual-threads", boundedTaskCount(taskCount), Executors.newVirtualThreadPerTaskExecutor());
    }

    public static RunResult runExecutorAndCompletableFuture(int taskCount) {
        return runCompletableFuture(boundedTaskCount(taskCount));
    }

    /** Uses Java 25's bounded structured-concurrency preview with inherited ScopedValue context. */
    public static RunResult runStructuredConcurrency(int taskCount) {
        int bounded = boundedTaskCount(taskCount);
        Instant started = Instant.now();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger propagated = new AtomicInteger();
        try {
            ScopedValue.where(CORRELATION, "lab-correlation").run(() -> {
                try (var scope = StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow(),
                        configuration -> configuration.withTimeout(DEADLINE))) {
                    for (int i = 0; i < bounded; i++) {
                        scope.fork(() -> {
                            if (CORRELATION.isBound() && "lab-correlation".equals(CORRELATION.get())) {
                                propagated.incrementAndGet();
                            }
                            boundedWork();
                            completed.incrementAndGet();
                            return null;
                        });
                    }
                    scope.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LabFailure(interrupted);
                } catch (StructuredTaskScope.TimeoutException timeout) {
                    throw new LabFailure(timeout);
                }
            });
            return new RunResult(
                    "structured-concurrency-preview",
                    bounded,
                    completed.get(),
                    bounded - completed.get(),
                    false,
                    elapsedMillis(started),
                    completed.get() == bounded && propagated.get() == bounded,
                    "");
        } catch (LabFailure failure) {
            return new RunResult(
                    "structured-concurrency-unavailable",
                    bounded,
                    completed.get(),
                    bounded - completed.get(),
                    true,
                    elapsedMillis(started),
                    false,
                    failure.getCause().getClass().getSimpleName());
        }
    }

    public static RunResult runScopedValuePropagation(int taskCount) {
        int bounded = boundedTaskCount(taskCount);
        AtomicInteger propagated = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        Instant started = Instant.now();
        try {
            ScopedValue.where(CORRELATION, "lab-correlation").run(() -> {
                try (var scope = StructuredTaskScope.open(
                        StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow(),
                        configuration -> configuration.withTimeout(DEADLINE))) {
                    for (int i = 0; i < bounded; i++) {
                        scope.fork(() -> {
                            if (CORRELATION.isBound() && "lab-correlation".equals(CORRELATION.get())) {
                                propagated.incrementAndGet();
                            }
                            completed.incrementAndGet();
                        });
                    }
                    scope.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LabFailure(interrupted);
                } catch (StructuredTaskScope.TimeoutException timeout) {
                    throw new LabFailure(timeout);
                }
            });
            return new RunResult(
                    "scoped-value",
                    bounded,
                    completed.get(),
                    bounded - completed.get(),
                    false,
                    elapsedMillis(started),
                    propagated.get() == bounded,
                    "");
        } catch (LabFailure failure) {
            return new RunResult(
                    "scoped-value",
                    bounded,
                    completed.get(),
                    bounded - completed.get(),
                    true,
                    elapsedMillis(started),
                    false,
                    failure.getCause().getClass().getSimpleName());
        }
    }

    /** Returns a bounded, safe thread-dump summary suitable for diagnosing a lab run. */
    public static ThreadDumpSummary threadDump(int maximumThreads) {
        if (maximumThreads < 1 || maximumThreads > 256) {
            throw new IllegalArgumentException("maximumThreads must be between 1 and 256");
        }
        ThreadInfo[] infos = ManagementFactory.getThreadMXBean().dumpAllThreads(false, false);
        List<String> names = java.util.Arrays.stream(infos)
                .filter(java.util.Objects::nonNull)
                .map(ThreadInfo::getThreadName)
                .sorted()
                .limit(maximumThreads)
                .toList();
        return new ThreadDumpSummary(infos.length, names);
    }

    /**
     * Records a bounded JFR sample without writing private thread or request data to disk. The
     * returned size proves that a recording was produced; callers may opt into a reviewed dump
     * path outside this lab.
     */
    public static JfrSample recordJfr(Duration requestedDuration) {
        if (requestedDuration == null || requestedDuration.isNegative() || requestedDuration.isZero()
                || requestedDuration.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("requestedDuration must be between 1ms and 5s");
        }
        Instant started = Instant.now();
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ThreadSleep").withoutStackTrace();
            recording.start();
            Thread.sleep(Math.max(1L, requestedDuration.toMillis()));
            recording.stop();
            return new JfrSample(true, recording.getSize(), elapsedMillis(started), "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new JfrSample(false, 0L, elapsedMillis(started), "InterruptedException");
        } catch (RuntimeException failure) {
            return new JfrSample(false, 0L, elapsedMillis(started), failure.getClass().getSimpleName());
        }
    }

    private static RunResult run(String strategy, int taskCount, ExecutorService executor) {
        Instant started = Instant.now();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch startedTasks = new CountDownLatch(taskCount);
        try (executor) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                futures.add(executor.submit(() -> {
                    startedTasks.countDown();
                    boundedWork();
                    completed.incrementAndGet();
                }));
            }
            startedTasks.await(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            int cancelled = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    if (future.cancel(true)) {
                        cancelled++;
                    }
                } catch (java.util.concurrent.ExecutionException failedTask) {
                    if (future.cancel(true)) {
                        cancelled++;
                    }
                }
            }
            return new RunResult(
                    strategy,
                    taskCount,
                    completed.get(),
                    cancelled + Math.max(0, taskCount - completed.get() - cancelled),
                    false,
                    elapsedMillis(started),
                    completed.get() + cancelled == taskCount,
                    "");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new RunResult(
                    strategy, taskCount, completed.get(), taskCount - completed.get(), true,
                    elapsedMillis(started), false, "InterruptedException");
        }
    }

    private static RunResult runCompletableFuture(int taskCount) {
        Instant started = Instant.now();
        AtomicInteger completed = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    boundedWork();
                    completed.incrementAndGet();
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .orTimeout(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            return new RunResult(
                    "completable-future",
                    taskCount,
                    completed.get(),
                    taskCount - completed.get(),
                    false,
                    elapsedMillis(started),
                    completed.get() == taskCount,
                    "");
        } catch (RuntimeException failure) {
            return new RunResult(
                    "completable-future",
                    taskCount,
                    completed.get(),
                    taskCount - completed.get(),
                    true,
                    elapsedMillis(started),
                    false,
                    failure.getClass().getSimpleName());
        }
    }

    private static void boundedWork() {
        try {
            Thread.sleep(TASK_DELAY);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int boundedTaskCount(int taskCount) {
        if (taskCount < 1 || taskCount > MAX_TASKS) {
            throw new IllegalArgumentException("taskCount must be between 1 and " + MAX_TASKS);
        }
        return taskCount;
    }

    private static long elapsedMillis(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }

    public record ThreadDumpSummary(int totalThreads, List<String> sampledThreadNames) {
        public ThreadDumpSummary {
            sampledThreadNames = List.copyOf(sampledThreadNames);
            if (totalThreads < 0 || sampledThreadNames.size() > 256 || sampledThreadNames.size() > totalThreads) {
                throw new IllegalArgumentException("thread dump is invalid or unbounded");
            }
        }
    }

    public record JfrSample(boolean recorded, long recordingBytes, long elapsedMillis, String failure) {
        public JfrSample {
            if (recordingBytes < 0 || elapsedMillis < 0 || failure == null || failure.length() > 64) {
                throw new IllegalArgumentException("JFR sample is invalid");
            }
        }
    }

    private static final class LabFailure extends RuntimeException {

        private LabFailure(Throwable cause) {
            super(cause);
        }
    }

    public record RunResult(
            String strategy,
            int submitted,
            int completed,
            int cancelledOrTimedOut,
            boolean timedOut,
            long elapsedMillis,
            boolean boundedAndAccounted,
            String failure) {

        RunResult withStrategy(String replacement) {
            return new RunResult(
                    replacement,
                    submitted,
                    completed,
                    cancelledOrTimedOut,
                    timedOut,
                    elapsedMillis,
                    boundedAndAccounted,
                    failure);
        }

        String toJson() {
            return "{\"strategy\":\"" + strategy + "\",\"submitted\":" + submitted
                    + ",\"completed\":" + completed + ",\"cancelledOrTimedOut\":"
                    + cancelledOrTimedOut + ",\"timedOut\":" + timedOut + ",\"elapsedMillis\":"
                    + elapsedMillis + ",\"boundedAndAccounted\":" + boundedAndAccounted
                    + ",\"failure\":\"" + failure + "\"}";
        }
    }
}
