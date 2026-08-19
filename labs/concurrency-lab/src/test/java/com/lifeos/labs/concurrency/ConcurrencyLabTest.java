package com.lifeos.labs.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConcurrencyLabTest {

    @Test
    void platformAndVirtualStrategiesAccountForEveryBoundedTask() {
        ConcurrencyLab.RunResult platform = ConcurrencyLab.runPlatformThreads(8);
        ConcurrencyLab.RunResult virtual = ConcurrencyLab.runVirtualThreads(8);

        assertEquals(8, platform.submitted());
        assertTrue(platform.boundedAndAccounted());
        assertEquals(8, virtual.submitted());
        assertTrue(virtual.boundedAndAccounted());
    }

    @Test
    void completableFutureStrategyHasAFiniteDeadline() {
        ConcurrencyLab.RunResult result = ConcurrencyLab.runExecutorAndCompletableFuture(8);

        assertEquals(8, result.submitted());
        assertTrue(result.elapsedMillis() < 2_000);
        assertTrue(result.boundedAndAccounted());
    }

    @Test
    void structuredStrategyIsExplicitlyPreviewOrFallback() {
        ConcurrencyLab.RunResult result = ConcurrencyLab.runStructuredConcurrency(4);

        assertTrue(result.strategy().startsWith("structured-concurrency-"));
        assertEquals(4, result.submitted());
        assertTrue(result.boundedAndAccounted());
    }

    @Test
    void scopedValuePropagationIsBoundedAndDoesNotLeak() {
        ConcurrencyLab.RunResult result = ConcurrencyLab.runScopedValuePropagation(8);

        assertEquals(8, result.completed());
        assertTrue(result.boundedAndAccounted());
    }

    @Test
    void diagnosticsAreBoundedAndFinite() {
        ConcurrencyLab.ThreadDumpSummary dump = ConcurrencyLab.threadDump(8);
        assertTrue(dump.totalThreads() >= dump.sampledThreadNames().size());
        assertTrue(dump.sampledThreadNames().size() <= 8);

        ConcurrencyLab.JfrSample sample = ConcurrencyLab.recordJfr(Duration.ofMillis(1));
        assertTrue(sample.elapsedMillis() < 5_000);
        if (sample.recorded()) {
            assertTrue(sample.recordingBytes() >= 0);
        } else {
            assertFalse(sample.failure().isBlank());
        }
    }
}
