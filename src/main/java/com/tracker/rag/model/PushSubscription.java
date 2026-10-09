package com.tracker.rag.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "push_subscriptions")
public class PushSubscription {
    @Id @GeneratedValue(strategy = GenerationType.UUID) public UUID id;
    @Column(nullable = false, unique = true, length = 2048) public String endpoint;
    @Column(nullable = false, length = 128) public String p256dh;
    @Column(nullable = false, length = 128) public String auth;
    @Column(name = "created_at", nullable = false) public Instant createdAt = Instant.now();
}
