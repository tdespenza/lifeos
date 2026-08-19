# Algorithms Lab: Product-Backed Practice Catalog

This catalog fulfills the Algorithms Lab's learning goal without treating practice code as a
production data boundary. Every input shown here must already be owner-authorized and bounded by
the calling service. The classes perform no I/O, retain no user data outside their instance, and
emit no logs or metrics; production services retain responsibility for persistence, access control,
idempotency, and observability.

Run all examples and their deterministic tests with:

```text
./gradlew :labs:algorithms-lab:check
```

## Catalog

| Family | Product-backed problem | Implementation and test | Complexity | Edge/failure contract |
| --- | --- | --- | --- | --- |
| Arrays | Smooth a bounded Finance daily-spend view | [`BoundedRollingAverage`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/arrays/BoundedRollingAverage.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/arrays/BoundedRollingAverageTest.java) | O(1) append, O(W) space | empty window returns empty; positive capacity; arithmetic overflow fails rather than wrapping |
| Strings | Normalize a bounded Document Vault query/metadata projection | [`BoundedNormalizedTokenizer`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/strings/BoundedNormalizedTokenizer.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/strings/BoundedNormalizedTokenizerTest.java) | O(N) time and bounded O(N) output | NFKC + root lowercase; Unicode code points; null, input, token-count, and token-length caps reject |
| Hash maps | Count authorized Finance categories | [`BoundedFrequencyCounter`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/hashmaps/BoundedFrequencyCounter.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/hashmaps/BoundedFrequencyCounterTest.java) | O(N) expected time, O(D) space | first-seen deterministic map order; null, submitted, and distinct-value caps reject |
| Linked lists | Retain a small authorized Document metadata cache | [`BoundedLruCache`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/linkedlists/BoundedLruCache.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/linkedlists/BoundedLruCacheTest.java) | O(1) expected get/put, O(C) space | exact LRU eviction; null/capacity rejects; no implicit loader or stale authorization bypass |
| Trees | Explain Calendar's ordered-index concept | [`BoundedBinarySearchTree`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/trees/BoundedBinarySearchTree.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/trees/BoundedBinarySearchTreeTest.java) | O(H) lookup/insert, O(N) space; ordered input worst case O(N) | duplicate normalization; iterative traversal; node cap; real Calendar uses database indexes because this tree is not balanced |
| Graphs | Order persisted Task/Goal dependencies | [`BoundedTopologicalOrder`](../../contracts/algorithm-engine/src/main/java/com/lifeos/algorithms/graph/BoundedTopologicalOrder.java), [`test`](../../contracts/algorithm-engine/src/test/java/com/lifeos/algorithms/graph/BoundedTopologicalOrderTest.java) | O(V + E) time and space | cycles reject whole plans; duplicate edges normalize; explicit node/edge caps |
| Heaps | Keep only the largest authorized Finance insight candidates | [`BoundedTopKSelector`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/heaps/BoundedTopKSelector.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/heaps/BoundedTopKSelectorTest.java) | O(N log K) time, O(K) space | deterministic first-seen ties; comparator/result/input caps reject |
| Tries | Offer bounded Document Vault term suggestions | [`BoundedPrefixTrie`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/tries/BoundedPrefixTrie.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/tries/BoundedPrefixTrieTest.java) | O(L) add/lookup; bounded traversal | lexicographic suggestions; node, word, prefix, and result caps reject |
| Dynamic programming | Rank a typo-tolerant Document search suggestion | [`BoundedLevenshteinDistance`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/dynamicprogramming/BoundedLevenshteinDistance.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/dynamicprogramming/BoundedLevenshteinDistanceTest.java) | O(M × N) time, O(min(M,N)) space | Unicode code points; input and cell-work budgets reject rather than exhaust heap/CPU |
| Backtracking | Find a feasible small Calendar focus plan | [`BoundedSlotAssignmentPlanner`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/backtracking/BoundedSlotAssignmentPlanner.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/backtracking/BoundedSlotAssignmentPlannerTest.java) | O(B^T) worst case, explicitly bounded | deterministic option order; unsatisfiable is empty; tasks/slots/options/search-state caps reject |
| Greedy algorithms | Select maximum-count non-overlapping Calendar blocks | [`GreedyIntervalSelector`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/greedy/GreedyIntervalSelector.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/greedy/GreedyIntervalSelectorTest.java) | O(N log N) time, O(N) space | half-open adjacency is compatible; null/over-bound intervals reject; earliest finish has the standard exchange argument |
| Union-Find | Check connectivity in a Profile/Household relationship projection | [`BoundedDisjointSet`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/unionfind/BoundedDisjointSet.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/unionfind/BoundedDisjointSetTest.java) | amortized O(α(N)), O(N) space | unknown and over-bound values reject; path compression/rank preserve bounded connectivity state |
| Segment trees | Query/update a bounded Finance day-bucket total projection | [`BoundedLongRangeSumTree`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/segmenttrees/BoundedLongRangeSumTree.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/segmenttrees/BoundedLongRangeSumTreeTest.java) | O(log N) point update/range sum, O(N) space | half-open bounds; empty/oversize input and arithmetic overflow fail instead of producing incorrect money totals |
| Fenwick trees | Apply incremental Analytics daily-total deltas | [`BoundedLongFenwickTree`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/fenwick/BoundedLongFenwickTree.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/fenwick/BoundedLongFenwickTreeTest.java) | O(log N) update/prefix/range sum, O(N) space | zero-based public API; invalid ranges/sizes reject; arithmetic overflow fails |
| Bloom filters | Prefilter Document duplicate-digest candidates | [`BoundedBloomFilter`](../../labs/algorithms-lab/src/main/java/com/lifeos/labs/algorithms/bloom/BoundedBloomFilter.java), [`test`](../../labs/algorithms-lab/src/test/java/com/lifeos/labs/algorithms/bloom/BoundedBloomFilterTest.java) | O(H) hashes, O(B) fixed bits | negative is definitive, positive must be confirmed by an authoritative store; no unbounded value retention |

## Design interview takeaway

The repeated theme is not merely selecting an asymptotically appropriate data structure. A real
service also chooses its trusted data projection, caps CPU and memory, defines deterministic ties,
handles malformed and stale input, and explains which decision remains outside the algorithm. That
is why each runnable implementation is deliberately smaller than the corresponding LifeOS service
boundary.
