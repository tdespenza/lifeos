package com.lifeos.taskgoal.goal;

import com.lifeos.taskgoal.goal.algorithm.DependencyEdge;
import com.lifeos.taskgoal.goal.algorithm.TopologicalSortService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GoalService {

    private final GoalRepository repository;
    private final TopologicalSortService topologicalSortService;

    public GoalService(GoalRepository repository, TopologicalSortService topologicalSortService) {
        this.repository = repository;
        this.topologicalSortService = topologicalSortService;
    }

    @Transactional
    public Goal create(String title) {
        return repository.save(new Goal(title));
    }

    @Transactional(readOnly = true)
    public List<Goal> listAll() {
        return repository.findAll();
    }

    public List<String> resolveDependencyOrder(List<String> goals, List<DependencyEdge> dependencies) {
        return topologicalSortService.order(goals, dependencies);
    }
}
