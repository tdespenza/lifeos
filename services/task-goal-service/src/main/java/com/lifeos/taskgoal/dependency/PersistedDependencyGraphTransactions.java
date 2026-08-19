package com.lifeos.taskgoal.dependency;

import com.lifeos.algorithms.AlgorithmCycleException;
import com.lifeos.algorithms.AlgorithmInputException;
import com.lifeos.algorithms.graph.BoundedTopologicalOrder;
import com.lifeos.algorithms.graph.DirectedEdge;
import com.lifeos.taskgoal.goal.Goal;
import com.lifeos.taskgoal.goal.GoalRepository;
import com.lifeos.taskgoal.task.Task;
import com.lifeos.taskgoal.task.TaskRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds the scoped graph guard only while validating and committing an edge mutation.
 *
 * <p>The orderer is the shared bounded Kahn implementation. After repository retrieval, both
 * validation and ordering are O(V + E); deterministic repository ordering supplies stable ties.
 */
@Service
public class PersistedDependencyGraphTransactions {

    private static final int TRANSACTION_TIMEOUT_SECONDS = 5;

    private final TaskGoalDependencyGuardRepository guardRepository;
    private final TaskGoalDependencyRepository dependencyRepository;
    private final TaskRepository taskRepository;
    private final GoalRepository goalRepository;
    private final BoundedTopologicalOrder orderer = new BoundedTopologicalOrder();

    public PersistedDependencyGraphTransactions(
            TaskGoalDependencyGuardRepository guardRepository,
            TaskGoalDependencyRepository dependencyRepository,
            TaskRepository taskRepository,
            GoalRepository goalRepository) {
        this.guardRepository = guardRepository;
        this.dependencyRepository = dependencyRepository;
        this.taskRepository = taskRepository;
        this.goalRepository = goalRepository;
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public boolean add(
            UUID guardId,
            UUID ownerAccountId,
            String tenantId,
            PersistedDependencyNode predecessor,
            PersistedDependencyNode dependent) {
        lockGuard(guardId, ownerAccountId, tenantId);
        GraphProjection graph = loadGraph(ownerAccountId, tenantId);
        requireAccessibleNodes(graph, predecessor, dependent);
        if (predecessor.equals(dependent)) {
            throw new SelfDependencyException();
        }
        if (dependencyRepository
                .existsByOwnerAccountIdAndTenantIdAndPredecessorTypeAndPredecessorIdAndDependentTypeAndDependentId(
                        ownerAccountId,
                        tenantId,
                        predecessor.type(),
                        predecessor.id(),
                        dependent.type(),
                        dependent.id())) {
            return false;
        }

        List<DirectedEdge<PersistedDependencyNode>> candidateEdges = new ArrayList<>(graph.edges());
        candidateEdges.add(new DirectedEdge<>(predecessor, dependent));
        order(graph.nodes().keySet(), candidateEdges);
        dependencyRepository.saveAndFlush(new TaskGoalDependency(ownerAccountId, tenantId, predecessor, dependent));
        return true;
    }

    @Transactional(timeout = TRANSACTION_TIMEOUT_SECONDS)
    public boolean remove(
            UUID guardId,
            UUID ownerAccountId,
            String tenantId,
            PersistedDependencyNode predecessor,
            PersistedDependencyNode dependent) {
        lockGuard(guardId, ownerAccountId, tenantId);
        GraphProjection graph = loadGraph(ownerAccountId, tenantId);
        requireAccessibleNodes(graph, predecessor, dependent);
        if (predecessor.equals(dependent)) {
            throw new SelfDependencyException();
        }
        return dependencyRepository.deleteByScopedEdge(
                        ownerAccountId,
                        tenantId,
                        predecessor.type(),
                        predecessor.id(),
                        dependent.type(),
                        dependent.id())
                > 0;
    }

    @Transactional(readOnly = true, timeout = TRANSACTION_TIMEOUT_SECONDS)
    public List<PersistedDependencyNode> order(UUID ownerAccountId, String tenantId) {
        GraphProjection graph = loadGraph(ownerAccountId, tenantId);
        return order(graph.nodes().keySet(), graph.edges());
    }

    private void lockGuard(UUID guardId, UUID ownerAccountId, String tenantId) {
        guardRepository.findByIdAndScopeForUpdate(guardId, ownerAccountId, tenantId)
                .orElseThrow(DependencyPersistenceUnavailableException::new);
    }

    private GraphProjection loadGraph(UUID ownerAccountId, String tenantId) {
        Map<PersistedDependencyNode, Boolean> nodes = new LinkedHashMap<>();
        // This fixed resource-family order plus indexed created_at/id order is the deterministic
        // first-seen tie-breaker supplied to the O(V+E) shared Kahn primitive.
        for (Task task : taskRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(ownerAccountId, tenantId)) {
            nodes.put(new PersistedDependencyNode(DependencyNodeType.TASK, task.getId()), Boolean.TRUE);
        }
        for (Goal goal : goalRepository.findAllByOwnerAccountIdAndTenantIdOrderByCreatedAtAscIdAsc(ownerAccountId, tenantId)) {
            nodes.put(new PersistedDependencyNode(DependencyNodeType.GOAL, goal.getId()), Boolean.TRUE);
        }
        List<DirectedEdge<PersistedDependencyNode>> edges = dependencyRepository
                .findAllByOwnerAccountIdAndTenantIdOrderByPredecessorTypeAscPredecessorIdAscDependentTypeAscDependentIdAsc(
                        ownerAccountId, tenantId)
                .stream()
                .map(edge -> new DirectedEdge<>(edge.predecessor(), edge.dependent()))
                .toList();
        return new GraphProjection(nodes, edges);
    }

    private static void requireAccessibleNodes(
            GraphProjection graph, PersistedDependencyNode predecessor, PersistedDependencyNode dependent) {
        if (!graph.nodes().containsKey(predecessor) || !graph.nodes().containsKey(dependent)) {
            // This can happen only after the public service's scope check if an operator removed
            // a row concurrently. Keep it a generic no-disclosure decision instead of exposing
            // which endpoint disappeared.
            throw new com.lifeos.taskgoal.authorization.TaskAuthorizationDenied();
        }
    }

    private List<PersistedDependencyNode> order(
            Iterable<PersistedDependencyNode> nodes, List<DirectedEdge<PersistedDependencyNode>> edges) {
        try {
            return orderer.order(toList(nodes), edges);
        } catch (AlgorithmCycleException exception) {
            throw new DependencyCycleException();
        } catch (AlgorithmInputException exception) {
            throw new DependencyGraphTooLargeException();
        } catch (DataAccessException exception) {
            throw new DependencyPersistenceUnavailableException();
        }
    }

    private static List<PersistedDependencyNode> toList(Iterable<PersistedDependencyNode> nodes) {
        List<PersistedDependencyNode> result = new ArrayList<>();
        nodes.forEach(result::add);
        return result;
    }

    private record GraphProjection(
            Map<PersistedDependencyNode, Boolean> nodes, List<DirectedEdge<PersistedDependencyNode>> edges) {
    }
}
