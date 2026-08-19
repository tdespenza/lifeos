package com.lifeos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LifeOsCliTest {

    @Test
    void hashesAFileWithConstantMemoryOutput() throws Exception {
        Path file = Files.createTempFile("lifeos-cli", ".txt");
        try {
            Files.writeString(file, "LifeOS");
            LifeOsCli.ProofDigest digest = LifeOsCli.hash(file);
            assertEquals(6, digest.bytes());
            assertEquals("72a3d5a0fb309fdb7c0161cf527878fbcee78b8d3899c7d4a46c0585a0e2eb28", digest.digest());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void rejectsMissingAndOversizedInputs() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LifeOsCli.hash(Path.of("does-not-exist")));
        Path file = Files.createTempFile("lifeos-cli", ".bin");
        try {
            try (var channel = java.nio.channels.FileChannel.open(
                    file, java.nio.file.StandardOpenOption.WRITE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.position(64L * 1024L * 1024L + 1L);
                channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
            }
            assertThrows(IllegalArgumentException.class, () -> LifeOsCli.hash(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void exposesOnlyTheSupportedCommands() {
        var output = new java.io.ByteArrayOutputStream();
        var error = new java.io.ByteArrayOutputStream();
        assertEquals(0, LifeOsCli.run(new String[] {"version"}, new java.io.PrintStream(output), new java.io.PrintStream(error)));
        assertEquals(64, LifeOsCli.run(new String[] {"unknown"}, new java.io.PrintStream(output), new java.io.PrintStream(error)));
    }
}
