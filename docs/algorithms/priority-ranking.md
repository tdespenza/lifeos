# Stable Priority Ranking

## Product framing

Calendar’s local focus suggestions and future planning queues need a deterministic way to rank
already-authorized candidates without pretending an algorithm is an AI recommendation engine.

`BoundedPriorityRanker` orders candidates by:

1. higher integer priority score;
2. earlier non-null deadline;
3. first-seen input order.

Candidates without a deadline come after equally scored candidates with a real deadline. The
caller owns how scores are computed and must expose that explanation to users.

## Complexity and bounds

The ranker sorts at most 10,000 candidates, so it runs in O(N log N) time with O(N) temporary
space. The requested output prefix is validated and capped before sorting. It rejects null or
oversized input rather than silently dropping candidates.

## Edge cases

- Equal priority/deadline preserves stable input order.
- A null deadline is explicit and deterministic; it is not substituted with the current time.
- The ranker has no mutable state, user data access, or randomness, so repeated calls with equal
  input produce the same result.

See `BoundedPriorityRankerTest` for its ordering and failure contracts.
