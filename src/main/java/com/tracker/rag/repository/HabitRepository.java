package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.Habit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HabitRepository extends JpaRepository<Habit, UUID> {

    List<Habit> findByDomain(DomainScope domain);

    List<Habit> findByDomainIn(Collection<DomainScope> domains);

    List<Habit> findByDomainInAndArchivedFalse(Collection<DomainScope> domains);

    Optional<Habit> findByIdAndDomain(UUID id, DomainScope domain);

    Optional<Habit> findByIdAndDomainIn(UUID id, Collection<DomainScope> domains);

    Optional<Habit> findByNameIgnoreCaseAndArchivedFalse(String name);

    @Query("SELECT h FROM Habit h LEFT JOIN FETCH h.logs WHERE h.id = :id AND h.domain IN :domains")
    Optional<Habit> findByIdWithLogsAndDomainIn(@Param("id") UUID id, @Param("domains") Collection<DomainScope> domains);
}
