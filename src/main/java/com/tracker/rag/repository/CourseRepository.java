package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CourseRepository extends JpaRepository<Course, UUID> {

    List<Course> findByDomain(DomainScope domain);

    List<Course> findByDomainIn(Collection<DomainScope> domains);

    Optional<Course> findByCode(String code);

    Optional<Course> findByCodeIgnoreCase(String code);

    Optional<Course> findByIdAndDomain(UUID id, DomainScope domain);

    Optional<Course> findByIdAndDomainIn(UUID id, Collection<DomainScope> domains);

    boolean existsByCodeIgnoreCase(String code);

    List<Course> findByTermIgnoreCase(String term);

    @Query("SELECT c FROM Course c LEFT JOIN FETCH c.categories WHERE c.id = :id AND c.domain IN :domains")
    Optional<Course> findByIdWithCategoriesAndDomainIn(@Param("id") UUID id, @Param("domains") Collection<DomainScope> domains);
}
