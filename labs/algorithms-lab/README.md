# Algorithms Lab

This standalone Java 25 lab connects classic data structures and algorithms to bounded LifeOS use
cases. It is teaching code, not a domain service and not an authorization or persistence boundary.
Every example has a deterministic test, explicit input/resource bounds, a complexity statement, and
a linked product context.

| Family | Runnable class | Product context | Complexity |
| --- | --- | --- | --- |
| Arrays / ring buffer | `BoundedRollingAverage` | Finance rolling spend view | add O(1), space O(W) |
| Strings / Unicode scan | `BoundedNormalizedTokenizer` | Document Vault search projection | O(N) time and bounded O(N) output |
| Hash maps | `BoundedFrequencyCounter` | Finance category histogram | O(N) expected time, O(D) space |
| Linked lists + hash map | `BoundedLruCache` | authorized Document metadata cache | get/put O(1) expected, O(C) space |
| Binary search tree | `BoundedBinarySearchTree` | Calendar ordered-index concept | O(H) insert/lookup, O(N) space; worst case O(N) |
| Heap | `BoundedTopKSelector` | Finance highest-category view | O(N log K) time, O(K) space |
| Trie | `BoundedPrefixTrie` | Document Vault term suggestions | O(L) add/lookup; bounded prefix traversal |
| Dynamic programming | `BoundedLevenshteinDistance` | Document typo-tolerant suggestion rank | O(M * N) time, O(min(M, N)) space |
| Backtracking | `BoundedSlotAssignmentPlanner` | Calendar feasible focus-plan search | bounded O(B^T) worst case |
| Greedy intervals | `GreedyIntervalSelector` | Calendar non-overlapping focus blocks | O(N log N) time, O(N) space |
| Union-Find | `BoundedDisjointSet` | Profile/Household relationship projection | amortized O(alpha(N)), O(N) space |
| Segment tree | `BoundedLongRangeSumTree` | Finance bounded day-bucket range totals | update/query O(log N), O(N) space |
| Fenwick tree | `BoundedLongFenwickTree` | Analytics incremental daily totals | add/query O(log N), O(N) space |
| Bloom filter | `BoundedBloomFilter` | Document duplicate-candidate prefilter | O(H) hashes, O(B) fixed memory |
| Graphs | `contracts:algorithm-engine` `BoundedTopologicalOrder` | Task/Goal execution order | O(V + E) time and space |
| Interval sweep | `contracts:algorithm-engine` `BoundedIntervalConflictDetector` | Calendar conflicts | O(N log N + K) time, O(N + K) space |
| Ranking | `contracts:algorithm-engine` `BoundedPriorityRanker` | Calendar focus queue | O(N log N) time, O(N) space |

The module is included in the repository quality gates, so a published class cannot drift from its
tests or static checks. The examples intentionally use bounded local projections and do not replace
service authorization, database constraints, idempotency, or observability.

Run it with:

```text
./gradlew :labs:algorithms-lab:check
```

See the [full product-backed catalog](../../docs/labs/algorithms-lab.md) for correctness rationale,
complexity, failure cases, and direct source/test links for every required family.
