package com.lifeos.labs.algorithms.bloom;

import com.lifeos.algorithms.AlgorithmInputException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.Objects;

/**
 * Fixed-memory SHA-256 Bloom filter for duplicate-candidate prefiltering.
 *
 * <p>A Document Vault worker can use it to skip unlikely duplicate digest lookups before a
 * database check. Insertion and membership checks take O(H) cryptographic hashes and O(B) fixed
 * memory for H configured hashes and B bits. A positive is never proof of membership; callers must
 * confirm it against their authoritative, authorized store. A negative has no false-negative
 * result while the filter has not been corrupted.
 */
public final class BoundedBloomFilter {

    private static final String SHA_256 = "SHA-256";

    private final int bitCount;
    private final int hashCount;
    private final int maxValueBytes;
    private final BitSet bits;

    /** Creates a filter with explicit finite bit, hash, and value-size budgets. */
    public BoundedBloomFilter(int bitCount, int hashCount, int maxValueBytes) {
        if (bitCount < 64 || hashCount < 1 || hashCount > 16 || maxValueBytes < 1) {
            throw new IllegalArgumentException("Bloom-filter bounds are invalid");
        }
        this.bitCount = bitCount;
        this.hashCount = hashCount;
        this.maxValueBytes = maxValueBytes;
        bits = new BitSet(bitCount);
    }

    /** Adds a non-empty value without retaining its bytes. */
    public void add(byte[] value) {
        for (int index : indexesFor(value)) {
            bits.set(index);
        }
    }

    /** Returns false only when the value is certainly absent from this filter. */
    public boolean mightContain(byte[] value) {
        for (int index : indexesFor(value)) {
            if (!bits.get(index)) {
                return false;
            }
        }
        return true;
    }

    /** Returns the fixed bit budget, not the variable internal BitSet word count. */
    public int bitCount() {
        return bitCount;
    }

    private int[] indexesFor(byte[] value) {
        Objects.requireNonNull(value, "Bloom-filter values must not be null");
        if (value.length == 0 || value.length > maxValueBytes) {
            throw new AlgorithmInputException("Bloom-filter value is outside the configured bound");
        }
        int[] indexes = new int[hashCount];
        for (int hashNumber = 0; hashNumber < hashCount; hashNumber++) {
            MessageDigest digest = sha256();
            digest.update((byte) hashNumber);
            byte[] hashed = digest.digest(value);
            long unsignedPrefix = ByteBuffer.wrap(hashed).getLong();
            indexes[hashNumber] = (int) Long.remainderUnsigned(unsignedPrefix, bitCount);
        }
        return indexes;
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(SHA_256);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK SHA-256 provider is unavailable", exception);
        }
    }
}
