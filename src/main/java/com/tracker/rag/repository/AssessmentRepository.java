package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.Assessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssessmentRepository extends JpaRepository<Assessment, UUID> {

    List<Assessment> findByCourseId(UUID courseId);

    List<Assessment> findByCategoryId(UUID categoryId);

    List<Assessment> findByCourseIdAndIsCompleted(UUID courseId, boolean isCompleted);

    @Query("SELECT a FROM Assessment a WHERE a.course.domain IN :domains")
    List<Assessment> findByCourseDomainIn(@Param("domains") Collection<DomainScope> domains);

    @Query("SELECT a FROM Assessment a WHERE a.id = :id AND a.course.domain IN :domains")
    Optional<Assessment> findByIdAndCourseDomainIn(@Param("id") UUID id, @Param("domains") Collection<DomainScope> domains);

    @Query("SELECT a FROM Assessment a WHERE a.course.id = :courseId ORDER BY a.dueDate ASC NULLS LAST")
    List<Assessment> findByCourseIdOrderByDueDateAsc(@Param("courseId") UUID courseId);
}
