package com.lifeos.labs.algorithms.backtracking;

import com.lifeos.algorithms.AlgorithmInputException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Bounded depth-first assignment of planning items to mutually exclusive Calendar slots.
 *
 * <p>This models a small "find a feasible focus plan" search after Calendar has already projected
 * authorized availability. Its worst case is exponential, O(B^T) for branch factor B and T items,
 * so task count, slot count, per-task options, and visited search states are all explicit bounds.
 * Recursive depth is at most the configured task cap.
 */
public final class BoundedSlotAssignmentPlanner {

    private final int maxTasks;
    private final int maxSlots;
    private final int maxOptionsPerTask;
    private final long maxSearchStates;

    /** Creates a planner with finite task, slot, branch, and search-state budgets. */
    public BoundedSlotAssignmentPlanner(
            int maxTasks, int maxSlots, int maxOptionsPerTask, long maxSearchStates) {
        if (maxTasks < 1 || maxSlots < 1 || maxOptionsPerTask < 1 || maxSearchStates < 1) {
            throw new IllegalArgumentException("slot-assignment bounds must be positive");
        }
        this.maxTasks = maxTasks;
        this.maxSlots = maxSlots;
        this.maxOptionsPerTask = maxOptionsPerTask;
        this.maxSearchStates = maxSearchStates;
    }

    /**
     * Finds the first deterministic feasible assignment or returns empty if no feasible assignment
     * exists within valid input bounds.
     */
    public <T> Optional<List<SlotAssignment<T>>> assign(
            List<? extends SlotCandidate<T>> candidates, int slotCount) {
        validateCandidates(candidates, slotCount);
        boolean[] occupied = new boolean[slotCount];
        List<SlotAssignment<T>> assignments = new ArrayList<>(candidates.size());
        SearchBudget budget = new SearchBudget(maxSearchStates);
        if (!search(candidates, 0, occupied, assignments, budget)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(assignments));
    }

    private <T> boolean search(
            List<? extends SlotCandidate<T>> candidates,
            int candidateIndex,
            boolean[] occupied,
            List<SlotAssignment<T>> assignments,
            SearchBudget budget) {
        budget.visit();
        if (candidateIndex == candidates.size()) {
            return true;
        }
        SlotCandidate<T> candidate = candidates.get(candidateIndex);
        for (Integer slot : candidate.allowedSlots()) {
            if (occupied[slot]) {
                continue;
            }
            occupied[slot] = true;
            assignments.add(new SlotAssignment<>(candidate.task(), slot));
            if (search(candidates, candidateIndex + 1, occupied, assignments, budget)) {
                return true;
            }
            assignments.removeLast();
            occupied[slot] = false;
        }
        return false;
    }

    private <T> void validateCandidates(List<? extends SlotCandidate<T>> candidates, int slotCount) {
        if (candidates == null) {
            throw new AlgorithmInputException("slot-assignment candidates are required");
        }
        if (candidates.size() > maxTasks || slotCount < 1 || slotCount > maxSlots) {
            throw new AlgorithmInputException("slot-assignment input exceeds the configured limit");
        }
        for (SlotCandidate<T> candidate : candidates) {
            if (candidate == null || candidate.task() == null || candidate.allowedSlots() == null) {
                throw new AlgorithmInputException("slot-assignment candidates must be complete");
            }
            if (candidate.allowedSlots().isEmpty() || candidate.allowedSlots().size() > maxOptionsPerTask) {
                throw new AlgorithmInputException("slot-assignment options exceed the configured limit");
            }
            LinkedHashSet<Integer> distinctSlots = new LinkedHashSet<>(candidate.allowedSlots());
            if (distinctSlots.size() != candidate.allowedSlots().size()
                    || distinctSlots.stream().anyMatch(slot -> slot == null || slot < 0 || slot >= slotCount)) {
                throw new AlgorithmInputException("slot-assignment slot options are invalid");
            }
        }
    }

    /** One planning item plus its deterministic permitted slot order. */
    public record SlotCandidate<T>(T task, List<Integer> allowedSlots) {

        public SlotCandidate {
            allowedSlots = allowedSlots == null ? null : List.copyOf(allowedSlots);
        }
    }

    /** One selected task/slot pair in the original candidate order. */
    public record SlotAssignment<T>(T task, int slot) {}

    private static final class SearchBudget {

        private final long maximum;
        private long visited;

        private SearchBudget(long maximum) {
            this.maximum = maximum;
        }

        private void visit() {
            if (++visited > maximum) {
                throw new AlgorithmInputException("slot-assignment search exceeds the configured limit");
            }
        }
    }
}
