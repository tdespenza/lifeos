package com.lifeos.labs.algorithms.linkedlists;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BoundedLruCacheTest {

    @Test
    void evictsExactlyTheLeastRecentlyUsedEntry() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<>(2);

        cache.put("first", "a");
        cache.put("second", "b");
        assertEquals("a", cache.get("first").orElseThrow());
        cache.put("third", "c");

        assertFalse(cache.get("second").isPresent());
        assertEquals("a", cache.get("first").orElseThrow());
        assertEquals("c", cache.get("third").orElseThrow());
        assertEquals(2, cache.size());
    }

    @Test
    void replacesExistingValuesWithoutGrowingPastCapacity() {
        BoundedLruCache<String, String> cache = new BoundedLruCache<>(1);

        cache.put("document", "v1");
        cache.put("document", "v2");

        assertEquals("v2", cache.get("document").orElseThrow());
        assertEquals(1, cache.size());
    }

    @Test
    void rejectsInvalidCapacityAndNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLruCache<>(0));
        BoundedLruCache<String, String> cache = new BoundedLruCache<>(1);
        assertThrows(NullPointerException.class, () -> cache.put(null, "value"));
        assertThrows(NullPointerException.class, () -> cache.put("key", null));
    }
}
