package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.HabitLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HabitLogRepository extends JpaRepository<HabitLog, UUID> {

    List<HabitLog> findByHabitIdOrderByLogDateAsc(UUID habitId);

    List<HabitLog> findByHabitIdOrderByLogDateDesc(UUID habitId);

    Optional<HabitLog> findByHabitIdAndLogDate(UUID habitId, LocalDate logDate);

    List<HabitLog> findByHabitIdAndLogDateBetweenOrderByLogDateAsc(UUID habitId, LocalDate startDate, LocalDate endDate);

    @Query("SELECT hl FROM HabitLog hl WHERE hl.habit.domain IN :domains")
    List<HabitLog> findByHabitDomainIn(@Param("domains") Collection<DomainScope> domains);

    @Query("SELECT hl FROM HabitLog hl WHERE hl.habit.id = :habitId AND hl.habit.domain IN :domains ORDER BY hl.logDate ASC")
    List<HabitLog> findByHabitIdAndHabitDomainIn(@Param("habitId") UUID habitId, @Param("domains") Collection<DomainScope> domains);

    boolean existsByHabitIdAndLogDate(UUID habitId, LocalDate logDate);
}
