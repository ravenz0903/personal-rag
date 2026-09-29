package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.AssessmentCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssessmentCategoryRepository extends JpaRepository<AssessmentCategory, UUID> {

    List<AssessmentCategory> findByCourseId(UUID courseId);

    Optional<AssessmentCategory> findByCourseIdAndNameIgnoreCase(UUID courseId, String name);

    @Query("SELECT ac FROM AssessmentCategory ac WHERE ac.course.domain IN :domains")
    List<AssessmentCategory> findByCourseDomainIn(@Param("domains") Collection<DomainScope> domains);

    @Query("SELECT ac FROM AssessmentCategory ac WHERE ac.id = :id AND ac.course.domain IN :domains")
    Optional<AssessmentCategory> findByIdAndCourseDomainIn(@Param("id") UUID id, @Param("domains") Collection<DomainScope> domains);
}
