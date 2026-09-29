package com.tracker.rag.repository;

import com.tracker.rag.domain.DomainScope;
import com.tracker.rag.model.CareerApplication;
import com.tracker.rag.model.CareerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CareerApplicationRepository extends JpaRepository<CareerApplication, UUID> {

    List<CareerApplication> findByDomain(DomainScope domain);

    List<CareerApplication> findByDomainIn(Collection<DomainScope> domains);

    List<CareerApplication> findByDomainInAndStatus(Collection<DomainScope> domains, CareerStatus status);

    List<CareerApplication> findByDomainInAndStatusIn(Collection<DomainScope> domains, Collection<CareerStatus> statuses);

    Optional<CareerApplication> findByIdAndDomain(UUID id, DomainScope domain);

    Optional<CareerApplication> findByIdAndDomainIn(UUID id, Collection<DomainScope> domains);

    List<CareerApplication> findByCompanyIgnoreCase(String company);
}
