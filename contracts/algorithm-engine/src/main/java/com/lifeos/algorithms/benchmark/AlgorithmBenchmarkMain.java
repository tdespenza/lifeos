package com.lifeos.algorithms.benchmark;

import com.lifeos.algorithms.graph.BoundedTopologicalOrder;
import com.lifeos.algorithms.graph.DirectedEdge;
import com.lifeos.algorithms.interval.BoundedIntervalConflictDetector;
import com.lifeos.algorithms.interval.TimeInterval;
import com.lifeos.algorithms.ranking.BoundedPriorityRanker;
import com.lifeos.algorithms.ranking.PrioritizedItem;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A dependency-free, correctness-checked benchmark harness for the shared algorithm primitives.
 *
 * <p>This is intentionally not a substitute for a JMH result used in performance claims. It is a
 * repeatable smoke benchmark: every run records warmup/measured iteration counts, input size, JVM
 * and host metadata, median/p95 wall-clock duration, and a correctness assertion. The generated
 * report is local build output, never a committed performance baseline.
 */
public final class AlgorithmBenchmarkMain {

    private static final int WARMUP_ITERATIONS = 8;
    private static final int MEASURED_ITERATIONS = 20;
    private static final int GRAPH_NODES = 1_000;
    private static final int INTERVALS = 1_000;
    private static final int RANKING_CANDIDATES = 5_000;

    private AlgorithmBenchmarkMain() {
    }

    /**
     * Runs all fixed benchmark fixtures.
     *
     * @param arguments one optional output JSON path; defaults under {@code build/reports}
     * @throws IOException if the local report cannot be written
     */
    public static void main(String[] arguments) throws IOException {
        Path output = arguments.length == 0
                ? Path.of("build", "reports", "benchmarks", "algorithm-engine.json")
                : Path.of(arguments[0]);
        BenchmarkReport report = new BenchmarkReport(
                Instant.now(),
                WARMUP_ITERATIONS,
                MEASURED_ITERATIONS,
                List.of(topologicalOrderBenchmark(), intervalConflictBenchmark(), priorityRankingBenchmark()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, report.toJson(), StandardCharsets.UTF_8);
    }

    private static BenchmarkCase topologicalOrderBenchmark() {
        List<String> nodes = new ArrayList<>(GRAPH_NODES);
        List<DirectedEdge<String>> edges = new ArrayList<>(GRAPH_NODES * 3);
        for (int index = 0; index < GRAPH_NODES; index++) {
            nodes.add("node-" + index);
            if (index > 0) {
                edges.add(new DirectedEdge<>("node-" + (index - 1), "node-" + index));
            }
            if (index > 2) {
                edges.add(new DirectedEdge<>("node-" + (index - 3), "node-" + index));
            }
        }
        BoundedTopologicalOrder algorithm = new BoundedTopologicalOrder();
        return measure("bounded_topological_order", GRAPH_NODES, GRAPH_NODES, () -> {
            List<String> ordered = algorithm.order(nodes, edges);
            if (ordered.size() != GRAPH_NODES || !"node-0".equals(ordered.getFirst())) {
                throw new IllegalStateException("topological benchmark correctness check failed");
            }
        });
    }

    private static BenchmarkCase intervalConflictBenchmark() {
        List<TimeInterval<Integer>> intervals = new ArrayList<>(INTERVALS);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < INTERVALS; index++) {
            Instant start = base.plusSeconds(index * 60L);
            intervals.add(new TimeInterval<>(index, start, start.plusSeconds(90)));
        }
        BoundedIntervalConflictDetector algorithm = new BoundedIntervalConflictDetector();
        return measure("bounded_interval_conflicts", INTERVALS, INTERVALS - 1, () -> {
            int conflictCount = algorithm.findConflicts(intervals).size();
            if (conflictCount != INTERVALS - 1) {
                throw new IllegalStateException("interval benchmark correctness check failed");
            }
        });
    }

    private static BenchmarkCase priorityRankingBenchmark() {
        List<PrioritizedItem<Integer>> candidates = new ArrayList<>(RANKING_CANDIDATES);
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < RANKING_CANDIDATES; index++) {
            candidates.add(new PrioritizedItem<>(index, index % 17, base.plusSeconds(index * 60L)));
        }
        BoundedPriorityRanker algorithm = new BoundedPriorityRanker();
        return measure("bounded_priority_ranking", RANKING_CANDIDATES, 100, () -> {
            List<PrioritizedItem<Integer>> ranked = algorithm.rank(candidates, 100);
            if (ranked.size() != 100 || ranked.getFirst().priorityScore() != 16) {
                throw new IllegalStateException("ranking benchmark correctness check failed");
            }
        });
    }

    private static BenchmarkCase measure(String name, int inputSize, int expectedResultSize, Runnable operation) {
        for (int iteration = 0; iteration < WARMUP_ITERATIONS; iteration++) {
            operation.run();
        }
        long[] samples = new long[MEASURED_ITERATIONS];
        for (int iteration = 0; iteration < MEASURED_ITERATIONS; iteration++) {
            long startedAt = System.nanoTime();
            operation.run();
            samples[iteration] = System.nanoTime() - startedAt;
        }
        java.util.Arrays.sort(samples);
        return new BenchmarkCase(
                name,
                inputSize,
                expectedResultSize,
                samples[samples.length / 2],
                samples[(int) Math.ceil(samples.length * 0.95D) - 1]);
    }

    private record BenchmarkCase(
            String name, int inputSize, int expectedResultSize, long medianNanos, long p95Nanos) {

        private String toJson() {
            return "{" + "\"name\":\"" + json(name) + "\"," + "\"inputSize\":" + inputSize + ","
                    + "\"expectedResultSize\":" + expectedResultSize + "," + "\"medianNanos\":" + medianNanos
                    + "," + "\"p95Nanos\":" + p95Nanos + "}";
        }
    }

    private record BenchmarkReport(
            Instant recordedAt, int warmupIterations, int measuredIterations, List<BenchmarkCase> cases) {

        private String toJson() {
            String caseJson = cases.stream().map(BenchmarkCase::toJson).collect(java.util.stream.Collectors.joining(","));
            return "{" + "\"recordedAt\":\"" + recordedAt + "\"," + "\"methodology\":{"
                    + "\"warmupIterations\":" + warmupIterations + "," + "\"measuredIterations\":"
                    + measuredIterations + "," + "\"clock\":\"System.nanoTime\"," + "\"limitations\":\""
                    + "dependency-free smoke benchmark; not a JMH baseline\"}," + "\"environment\":{"
                    + "\"javaRuntimeVersion\":\"" + json(System.getProperty("java.runtime.version")) + "\","
                    + "\"osName\":\"" + json(System.getProperty("os.name")) + "\"," + "\"osArch\":\""
                    + json(System.getProperty("os.arch")) + "\"," + "\"availableProcessors\":"
                    + Runtime.getRuntime().availableProcessors() + "}," + "\"cases\":[" + caseJson + "]}";
        }
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
