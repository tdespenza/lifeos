# Interval Conflict Detection

## Product framing

Calendar conflict detection compares normalized event occurrences and time blocks. Each interval is
half-open: `[start, end)`. Therefore a block ending at 10:00 and an event beginning at 10:00 are
adjacent, not overlapping.

## Algorithm

`BoundedIntervalConflictDetector` sorts intervals by start, end, and first-seen rank. It keeps
active intervals in two structures:

- a min-heap by end time, used to discard intervals that end at or before the next start;
- a deterministic ordered set, used to enumerate each remaining overlap exactly once.

When an interval starts, every active interval satisfies `active.start < current.end` and
`active.end > current.start`, so it forms one conflict pair with the current interval.

## Complexity

For `N` intervals and `K` returned conflicts, sorting and eviction cost O(N log N), and emitting
pairs costs O(K). Space is O(N + K). Since all pairs can be quadratic for a dense calendar, the
implementation caps both the input (10,000) and emitted result (50,000) and rejects instead of
returning a misleading partial result.

## Edge cases

- Adjacent endpoints are not conflicts.
- Equal starts and nested intervals are detected deterministically.
- Invalid or zero-length intervals fail at construction.
- A caller must normalize local dates/DST policy to `Instant` before calling; the algorithm does
  not guess a timezone.

See `BoundedIntervalConflictDetectorTest` for the overlap, adjacency, output-cap, null, and input
cap cases.
