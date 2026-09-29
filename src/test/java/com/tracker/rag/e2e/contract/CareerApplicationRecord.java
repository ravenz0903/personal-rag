package com.tracker.rag.e2e.contract;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record CareerApplicationRecord(
        UUID id,
        DomainScope domain,
        String company,
        String position,
        CareerStatus status,
        LocalDate appliedDate,
        String location,
        String salaryRange,
        String jobPostingUrl,
        String notes
) {
    public CareerApplicationRecord {
        Objects.requireNonNull(domain, "Domain cannot be null");
        if (company == null || company.trim().isEmpty()) {
            throw new IllegalArgumentException("Company cannot be null or blank");
        }
        if (position == null || position.trim().isEmpty()) {
            throw new IllegalArgumentException("Position cannot be null or blank");
        }
        if (status == null) {
            status = CareerStatus.WISHLIST;
        }
    }

    public CareerApplicationRecord withStatus(CareerStatus newStatus) {
        if (!this.status.canTransitionTo(newStatus)) {
            throw new InvalidStateTransitionException(
                    "Cannot transition career application from " + this.status + " to " + newStatus
            );
        }
        return new CareerApplicationRecord(
                id, domain, company, position, newStatus, appliedDate, location, salaryRange, jobPostingUrl, notes
        );
    }

    public static CareerApplicationRecord create(String company, String position, CareerStatus status) {
        return new CareerApplicationRecord(
                UUID.randomUUID(), DomainScope.CAREER, company, position, status, LocalDate.now(), null, null, null, null
        );
    }
}
