package com.lifeos.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Small Java 25 command-line boundary for local trust/proof workflows.
 *
 * <p>The CLI never uploads content or writes to a service. It reads a bounded local file and
 * emits only the algorithm, byte count, and digest, which is safe to pass to the Trust Ledger
 * proof API. The bounded streaming implementation uses O(1) memory with respect to file size.
 */
public final class LifeOsCli {

    private static final long MAX_INPUT_BYTES = 64L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 32 * 1024;

    private LifeOsCli() {}

    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = run(args, System.out, System.err);
        } catch (RuntimeException exception) {
            System.err.println("lifeos-cli: " + exception.getMessage());
            exitCode = 64;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length == 1 && "version".equals(args[0])) {
            out.println("lifeos-cli 0.1.0");
            return 0;
        }
        if (args.length == 2 && "hash".equals(args[0])) {
            ProofDigest digest = hash(Path.of(args[1]));
            out.printf("{\"algorithm\":\"SHA-256\",\"bytes\":%d,\"digest\":\"%s\"}%n",
                    digest.bytes(), digest.digest());
            return 0;
        }
        err.println("Usage: lifeos-cli version | hash <file>");
        return 64;
    }

    static ProofDigest hash(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("file path is required");
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalArgumentException("file is not a regular file");
        }
        try {
            long size = Files.size(normalized);
            if (size > MAX_INPUT_BYTES) {
                throw new IllegalArgumentException("file exceeds the 64 MiB safety limit");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream input = Files.newInputStream(normalized)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes = Math.addExact(bytes, read);
                    if (bytes > MAX_INPUT_BYTES) {
                        throw new IllegalArgumentException("file exceeds the 64 MiB safety limit");
                    }
                    digest.update(buffer, 0, read);
                }
            }
            return new ProofDigest(bytes, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("file could not be read safely", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    record ProofDigest(long bytes, String digest) {
        ProofDigest {
            if (bytes < 0 || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("proof digest is invalid");
            }
        }
    }
}
