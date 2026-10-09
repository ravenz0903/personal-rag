package com.tracker.rag.repository;

import com.tracker.rag.model.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {
    Optional<PushSubscription> findByEndpoint(String endpoint);
}
