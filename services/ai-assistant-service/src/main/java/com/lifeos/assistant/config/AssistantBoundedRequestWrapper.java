package com.lifeos.assistant.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Enforces the body bound for chunked requests that omit Content-Length. */
final class AssistantBoundedRequestWrapper extends HttpServletRequestWrapper {

    private final long maximumBytes;
    private ServletInputStream inputStream;
    private BufferedReader reader;

    AssistantBoundedRequestWrapper(HttpServletRequest request, long maximumBytes) {
        super(request);
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (inputStream == null) {
            inputStream = new BoundedServletInputStream(super.getInputStream(), maximumBytes);
        }
        return inputStream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(getInputStream(), requestCharset()));
        }
        return reader;
    }

    private Charset requestCharset() {
        String encoding = getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (RuntimeException exception) {
            return StandardCharsets.UTF_8;
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maximumBytes;
        private long bytesRead;

        private BoundedServletInputStream(ServletInputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remainingPlusOne = maximumBytes + 1 - bytesRead;
            if (remainingPlusOne <= 0) {
                int value = delegate.read();
                if (value >= 0) {
                    throw new AssistantPayloadTooLargeException();
                }
                return value;
            }
            int boundedLength = (int) Math.min(length, remainingPlusOne);
            int read = delegate.read(buffer, offset, boundedLength);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void count(int count) {
            bytesRead += count;
            if (bytesRead > maximumBytes) {
                throw new AssistantPayloadTooLargeException();
            }
        }
    }
}
