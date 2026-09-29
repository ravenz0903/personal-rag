package com.tracker.rag.repository;

import com.tracker.rag.domain.ActionType;
import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.domain.EntityType;
import com.tracker.rag.model.EventLogEntry;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventLogRepository extends JpaRepository<EventLogEntry, UUID> {

    List<EventLogEntry> findByDomain(DomainScope domain);

    List<EventLogEntry> findByDomainIn(Collection<DomainScope> domains);

    List<EventLogEntry> findByDomainOrderByTimestampDesc(DomainScope domain);

    List<EventLogEntry> findByDomainOrderByTimestampDesc(DomainScope domain, Pageable pageable);

    List<EventLogEntry> findByDomainInOrderByTimestampDesc(Collection<DomainScope> domains, Pageable pageable);

    List<EventLogEntry> findByDomainInAndUndoneFalseOrderByTimestampDesc(Collection<DomainScope> domains);

    Optional<EventLogEntry> findFirstByDomainOrderByTimestampDesc(DomainScope domain);

    List<EventLogEntry> findByEntityTypeAndEntityIdOrderByTimestampDesc(EntityType entityType, UUID entityId);

    @Query("SELECT e FROM EventLogEntry e WHERE e.domain = :domain AND e.undone = false AND e.action <> com.tracker.rag.domain.ActionType.ROLLBACK ORDER BY e.timestamp DESC")
    List<EventLogEntry> findLatestActiveMutationsByDomain(@Param("domain") DomainScope domain, Pageable pageable);

    default Optional<EventLogEntry> findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope domain) {
        List<EventLogEntry> results = findLatestActiveMutationsByDomain(domain, Pageable.ofSize(1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM EventLogEntry e WHERE e.id = :id")
    Optional<EventLogEntry> findByIdForUpdate(@Param("id") UUID id);

    @Query("SELECT e FROM EventLogEntry e WHERE e.id = :id AND e.undone = false")
    Optional<EventLogEntry> findActiveById(@Param("id") UUID id);
}
