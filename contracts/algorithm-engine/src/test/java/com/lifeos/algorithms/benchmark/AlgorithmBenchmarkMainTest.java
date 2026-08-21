package com.lifeos.algorithms.benchmark;

import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void escapesJsonControlCharactersInEnvironmentValues() throws Exception {
        Path report = Files.createTempFile("algorithm-engine-benchmark-control-char", ".json");
        String originalOsName = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "control\u0001char");
            AlgorithmBenchmarkMain.main(new String[] {report.toString()});
            String json = Files.readString(report);

            assertTrue(json.contains("control\\u0001char"));
        } finally {
            if (originalOsName == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", originalOsName);
            }
            Files.deleteIfExists(report);
        }
    }

    @Test
    void rejectsMoreThanOneArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmBenchmarkMain.main(new String[] {"a", "b"}));
    }
}
