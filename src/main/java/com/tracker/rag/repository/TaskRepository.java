package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.Task;
import com.tracker.rag.model.TaskPriority;
import com.tracker.rag.model.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    List<Task> findByDomain(DomainScope domain);

    List<Task> findByDomainIn(Collection<DomainScope> domains);

    List<Task> findByDomainAndStatus(DomainScope domain, TaskStatus status);

    List<Task> findByDomainInAndStatus(Collection<DomainScope> domains, TaskStatus status);

    List<Task> findByDomainInAndStatusIn(Collection<DomainScope> domains, Collection<TaskStatus> statuses);

    List<Task> findByDomainAndDueDateBetween(DomainScope domain, Instant start, Instant end);

    List<Task> findByDomainInAndDueDateBetween(Collection<DomainScope> domains, Instant start, Instant end);

    List<Task> findByDomainInAndPriority(Collection<DomainScope> domains, TaskPriority priority);

    Optional<Task> findByIdAndDomain(UUID id, DomainScope domain);

    Optional<Task> findByIdAndDomainIn(UUID id, Collection<DomainScope> domains);

    List<Task> findByCourseId(UUID courseId);

    List<Task> findByCareerApplicationId(UUID careerApplicationId);

    List<Task> findByParentTaskId(UUID parentTaskId);

    @Query("SELECT t FROM Task t WHERE t.domain IN :domains AND t.parentTask IS NULL ORDER BY t.dueDate ASC NULLS LAST")
    List<Task> findRootTasksByDomainIn(@Param("domains") Collection<DomainScope> domains);

    long countByDomainInAndStatus(Collection<DomainScope> domains, TaskStatus status);
}
