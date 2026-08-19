package com.lifeos.labs.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerformanceLabTest {

    @Test
    void localBenchmarkIsDeterministicAndBounded() {
        PerformanceLab.BenchmarkResult result = PerformanceLab.runMapLookup(1_000);
        PerformanceLab.BenchmarkResult repeat = PerformanceLab.runMapLookup(1_000);

        assertEquals(1_000, result.operations());
        assertEquals(result.checksum(), repeat.checksum());
        assertTrue(result.checksum() > 0);
        assertTrue(result.p50Nanos() <= result.p95Nanos());
        assertTrue(result.p95Nanos() <= result.p99Nanos());
        assertTrue(result.operationsPerSecond() > 0);
        assertTrue(result.methodology().contains("seed="));
    }

    @Test
    void cacheBenchmarkReportsBoundedHitRatioAndEviction() {
        PerformanceLab.CacheBenchmarkResult result = PerformanceLab.runCacheLookup(2_000, 32);

        assertEquals(2_000, result.operations());
        assertEquals(32, result.capacity());
        assertEquals(result.operations(), result.hits() + result.misses());
        assertTrue(result.hits() > 0);
        assertTrue(result.misses() > 0);
        assertTrue(result.finalSize() <= result.capacity());
        assertTrue(result.hitRatio() > 0.0d && result.hitRatio() < 1.0d);
        assertTrue(result.methodology().contains("hotKeySpace=64"));
    }
}
