package com.lifeos.documentvault.storage;

import com.lifeos.documentvault.config.DocumentVaultOcrProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Runs a deployment-provided Tesseract executable without a shell and with strict process,
 * output, and format bounds. A failure is intentionally treated as an empty search contribution;
 * the verified document object remains available.
 */
@Component
final class BoundedTesseractDocumentOcrExtractor implements DocumentOcrExtractor {

    private final DocumentVaultOcrProperties properties;

    BoundedTesseractDocumentOcrExtractor(DocumentVaultOcrProperties properties) {
        this.properties = properties;
    }

    @Override
    public String extract(Path objectPath, DocumentContentType contentType) {
        if (!properties.isEnabled()
                || objectPath == null
                || contentType == null
                || (contentType != DocumentContentType.PNG && contentType != DocumentContentType.JPEG)) {
            return "";
        }
        Process process = null;
        ExecutorService outputReader = null;
        try {
            // ProcessBuilder receives discrete arguments; no shell or user-controlled command line
            // is ever evaluated. Tesseract reads only the already-verified local object path.
            process = new ProcessBuilder(
                            properties.getExecutable(),
                            "--dpi",
                            "150",
                            "--quiet",
                            objectPath.toString(),
                            "stdout")
                    .redirectErrorStream(true)
                    .start();
            Process runningProcess = process;
            outputReader = Executors.newSingleThreadExecutor();
            Future<String> output = outputReader.submit(
                    () -> readBounded(runningProcess.getInputStream(), properties.getMaxOutputCharacters()));
            Duration timeout = properties.getTimeout();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(250, TimeUnit.MILLISECONDS);
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            try {
                return sanitize(output.get(250, TimeUnit.MILLISECONDS));
            } catch (ExecutionException | java.util.concurrent.TimeoutException exception) {
                return "";
            }
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (outputReader != null) {
                outputReader.shutdownNow();
            }
        }
    }

    private static String readBounded(InputStream input, int maximumCharacters) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximumCharacters, 8_192));
        byte[] buffer = new byte[4_096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) {
                continue;
            }
            if (output.size() > maximumCharacters - read) {
                return "";
            }
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return normalized.length() <= DocumentTextExtractor.MAX_TEXT_CHARS
                ? normalized
                : normalized.substring(0, DocumentTextExtractor.MAX_TEXT_CHARS).strip();
    }
}
