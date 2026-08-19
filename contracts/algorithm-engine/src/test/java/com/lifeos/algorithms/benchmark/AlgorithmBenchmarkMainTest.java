package com.lifeos.algorithms.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AlgorithmBenchmarkMainTest {

    @Test
    void writesAReportWithMethodologyEnvironmentAndCorrectnessCheckedCases() throws Exception {
        Path report = Files.createTempFile("algorithm-engine-benchmark", ".json");
        try {
            AlgorithmBenchmarkMain.main(new String[] {report.toString()});
            String json = Files.readString(report);

            assertTrue(json.contains("bounded_topological_order"));
            assertTrue(json.contains("bounded_interval_conflicts"));
            assertTrue(json.contains("bounded_priority_ranking"));
            assertTrue(json.contains("warmupIterations"));
            assertTrue(json.contains("javaRuntimeVersion"));
            assertTrue(json.contains("medianNanos"));
        } finally {
            Files.deleteIfExists(report);
        }
    }
}
