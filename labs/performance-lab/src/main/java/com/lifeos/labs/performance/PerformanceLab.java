package com.lifeos.labs.performance;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic, bounded local benchmark harness. It reports measurements, not capacity claims.
 * The same harness can be run under a JVM profiler/JFR or inside a constrained container.
 */
public final class PerformanceLab {

    public static final int DEFAULT_OPERATIONS = 10_000;
    public static final int MAX_OPERATIONS = 50_000;
    private static final int KEY_SPACE = 4_096;
    private static final long SEED = 0x4c4946454f53L;

    private PerformanceLab() {}

    public static void main(String[] args) {
        int operations = args.length == 0 ? DEFAULT_OPERATIONS : boundedOperations(Integer.parseInt(args[0]));
        System.out.println(runMapLookup(operations).toJson());
        System.out.println(runCacheLookup(operations, 256).toJson());
    }

    public static BenchmarkResult runMapLookup(int operations) {
        int bounded = boundedOperations(operations);
        Map<Integer, Integer> values = new HashMap<>(KEY_SPACE * 2);
        for (int key = 0; key < KEY_SPACE; key++) {
            values.put(key, key * 31);
        }

        long[] samples = new long[bounded];
        Random random = new Random(SEED);
        long checksum = 0;
        Instant started = Instant.now();
        for (int i = 0; i < bounded; i++) {
            int key = random.nextInt(KEY_SPACE);
            long sampleStart = System.nanoTime();
            checksum += values.get(key);
            samples[i] = Math.max(0, System.nanoTime() - sampleStart);
        }
        long elapsedNanos = Math.max(1, Duration.between(started, Instant.now()).toNanos());
        Arrays.sort(samples);
        return new BenchmarkResult(
                "bounded-map-lookup",
                bounded,
                checksum,
                percentile(samples, 0.50),
                percentile(samples, 0.95),
                percentile(samples, 0.99),
                bounded * 1_000_000_000L / elapsedNanos,
                Runtime.version().toString(),
                "seed=" + SEED + ",keySpace=" + KEY_SPACE);
    }

    /**
     * Runs a deterministic hot/cold cache workload and reports the measured hit ratio. The cache
     * is intentionally bounded so the fixture demonstrates eviction and hit-rate instrumentation
     * without claiming to model Redis latency or production capacity.
     */
    public static CacheBenchmarkResult runCacheLookup(int operations, int capacity) {
        int boundedOperations = boundedOperations(operations);
        if (capacity < 1 || capacity > 4_096) {
            throw new IllegalArgumentException("cache capacity must be between 1 and 4096");
        }
        BoundedCache cache = new BoundedCache(capacity);
        Random random = new Random(SEED ^ 0x43414348454cL);
        long checksum = 0;
        Instant started = Instant.now();
        for (int operation = 0; operation < boundedOperations; operation++) {
            int key = operation % 5 == 0
                    ? KEY_SPACE + random.nextInt(KEY_SPACE)
                    : random.nextInt(64);
            Integer value = cache.get(key);
            if (value == null) {
                value = key * 31;
                cache.put(key, value);
            }
            checksum += value;
        }
        long elapsedNanos = Math.max(1, Duration.between(started, Instant.now()).toNanos());
        return new CacheBenchmarkResult(
                boundedOperations,
                capacity,
                cache.hits(),
                cache.misses(),
                cache.size(),
                checksum,
                (double) cache.hits() / boundedOperations,
                boundedOperations * 1_000_000_000L / elapsedNanos,
                Runtime.version().toString(),
                "seed=" + (SEED ^ 0x43414348454cL) + ",hotKeySpace=64,coldEvery=5");
    }

    private static int boundedOperations(int operations) {
        if (operations < 1 || operations > MAX_OPERATIONS) {
            throw new IllegalArgumentException("operations must be between 1 and " + MAX_OPERATIONS);
        }
        return operations;
    }

    private static long percentile(long[] values, double fraction) {
        int index = Math.min(values.length - 1, Math.max(0, (int) Math.ceil(values.length * fraction) - 1));
        return values[index];
    }

    public record BenchmarkResult(
            String workload,
            int operations,
            long checksum,
            long p50Nanos,
            long p95Nanos,
            long p99Nanos,
            long operationsPerSecond,
            String javaRuntime,
            String methodology) {

        String toJson() {
            return "{\"workload\":\"" + workload + "\",\"operations\":" + operations
                    + ",\"checksum\":" + checksum + ",\"p50Nanos\":" + p50Nanos
                    + ",\"p95Nanos\":" + p95Nanos + ",\"p99Nanos\":" + p99Nanos
                    + ",\"operationsPerSecond\":" + operationsPerSecond + ",\"javaRuntime\":\""
                    + javaRuntime.replace("\"", "'") + "\",\"methodology\":\""
                    + methodology + "\"}";
        }
    }

    public record CacheBenchmarkResult(
            int operations,
            int capacity,
            long hits,
            long misses,
            int finalSize,
            long checksum,
            double hitRatio,
            long operationsPerSecond,
            String javaRuntime,
            String methodology) {

        public CacheBenchmarkResult {
            if (operations < 1 || capacity < 1 || hits < 0 || misses < 0
                    || hits + misses != operations || finalSize < 0 || finalSize > capacity
                    || hitRatio < 0.0d || hitRatio > 1.0d || operationsPerSecond < 1
                    || methodology == null || methodology.length() > 256) {
                throw new IllegalArgumentException("cache benchmark result is invalid or unbounded");
            }
        }

        String toJson() {
            return "{\"workload\":\"bounded-cache-lookup\",\"operations\":" + operations
                    + ",\"capacity\":" + capacity + ",\"hits\":" + hits
                    + ",\"misses\":" + misses + ",\"finalSize\":" + finalSize
                    + ",\"hitRatio\":" + hitRatio + ",\"operationsPerSecond\":"
                    + operationsPerSecond + ",\"javaRuntime\":\""
                    + javaRuntime.replace("\"", "'") + "\",\"methodology\":\""
                    + methodology + "\"}";
        }
    }

    private static final class BoundedCache {

        private final int capacity;
        private final LinkedHashMap<Integer, Integer> values = new LinkedHashMap<>(16, 0.75f, true);
        private long hits;
        private long misses;

        private BoundedCache(int capacity) {
            this.capacity = capacity;
        }

        private Integer get(int key) {
            Integer value = values.get(key);
            if (value == null) {
                misses++;
            } else {
                hits++;
            }
            return value;
        }

        private void put(int key, int value) {
            values.put(key, value);
            if (values.size() > capacity) {
                values.remove(values.keySet().iterator().next());
            }
        }

        private long hits() {
            return hits;
        }

        private long misses() {
            return misses;
        }

        private int size() {
            return values.size();
        }
    }
}
