package com.lifeos.trust.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.lifeos.trust.ProofInputException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DocumentHasherTest {

    private static final CanonicalDocumentMetadata PDF = new CanonicalDocumentMetadata("application/pdf", "document-proof");

    @Test
    void producesAStableDomainSeparatedStreamingDigest() throws Exception {
        byte[] document = "lifeos canonical proof".getBytes(StandardCharsets.UTF_8);

        DocumentProof first = DocumentHasher.hash(new ByteArrayInputStream(document), PDF);
        DocumentProof second = DocumentHasher.hash(new ChunkedInputStream(document, 3), PDF);
        DocumentProof differentContext = DocumentHasher.hash(
                new ByteArrayInputStream(document), new CanonicalDocumentMetadata("text/plain", "document-proof"));
        DocumentProof normalizedContext = DocumentHasher.hash(
                new ByteArrayInputStream(document), new CanonicalDocumentMetadata("APPLICATION/PDF", "DOCUMENT-PROOF"));

        assertEquals(DocumentHasher.ALGORITHM, first.algorithm());
        assertEquals(document.length, first.contentBytes());
        assertEquals(first.digest(), second.digest());
        assertEquals(first.digest(), normalizedContext.digest());
        assertNotEquals(first.digest(), differentContext.digest());
        assertEquals(64, first.digest().toHex().length());
        assertEquals("6b3bb188ddcd3b095e5f45b748ff92a20fc5128e9be59f6110b2c5ee183aff48", first.digest().toHex());
    }

    @Test
    void rejectsEmptyAndOversizedContentWithoutReturningAPartialProof() {
        assertThrows(ProofInputException.class, () -> DocumentHasher.hash(new ByteArrayInputStream(new byte[0]), PDF));
        assertThrows(
                ProofInputException.class,
                () -> DocumentHasher.hash(new ByteArrayInputStream(new byte[] {1, 2, 3}), PDF, 2));
        assertThrows(ProofInputException.class, () -> DocumentHasher.hash(null, PDF));
        assertThrows(
                ProofInputException.class,
                () -> DocumentHasher.hash(new ByteArrayInputStream(new byte[] {1}), null));
        assertThrows(
                ProofInputException.class,
                () -> DocumentHasher.hash(new ByteArrayInputStream(new byte[] {1}), PDF, 0));
    }

    @Test
    void acceptsContentExactlyAtTheInclusiveLimit() throws Exception {
        DocumentProof proof = DocumentHasher.hash(new ByteArrayInputStream(new byte[] {1, 2, 3}), PDF, 3);

        assertEquals(3, proof.contentBytes());
    }

    @Test
    void hashesStreamsThatReturnZeroLengthReads() throws Exception {
        byte[] document = "lifeos canonical proof".getBytes(StandardCharsets.UTF_8);

        DocumentProof expected = DocumentHasher.hash(new ByteArrayInputStream(document), PDF);
        DocumentProof actual = DocumentHasher.hash(new ZeroReadInputStream(document), PDF);

        assertEquals(expected.digest(), actual.digest());
    }

    @Test
    void preservesReadFailuresForTheOwningServiceToMapSafely() {
        InputStream unreadable = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("source unavailable");
            }
        };

        assertThrows(IOException.class, () -> DocumentHasher.hash(unreadable, PDF));
    }

    private static final class ChunkedInputStream extends InputStream {

        private final byte[] bytes;
        private final int chunkSize;
        private int position;

        private ChunkedInputStream(byte[] bytes, int chunkSize) {
            this.bytes = bytes;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(Math.min(length, chunkSize), bytes.length - position);
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }

        @Override
        public int read() {
            return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }
    }

    private static final class ZeroReadInputStream extends InputStream {

        private final byte[] bytes;
        private int position;
        private boolean returnZero = true;

        private ZeroReadInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position == bytes.length) {
                return -1;
            }
            if (returnZero) {
                returnZero = false;
                return 0;
            }
            buffer[offset] = bytes[position++];
            returnZero = true;
            return 1;
        }

        @Override
        public int read() {
            return position == bytes.length ? -1 : bytes[position++] & 0xff;
        }
    }
}
