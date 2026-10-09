package com.tracker.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OwnerAuthorizationTest {
    private final String issuer = "https://example.supabase.co/auth/v1", owner = "122e4567-e89b-12d3-a456-426614174000";
    Jwt token(String subject, String tokenIssuer, String audience, Instant expires) {
        return Jwt.withTokenValue("test").header("alg", "ES256").subject(subject).issuer(tokenIssuer)
                .audience(List.of(audience)).issuedAt(Instant.now().minusSeconds(3600)).expiresAt(expires).build();
    }
    @Test void onlyOwnerWithExpectedIssuerAndAudienceIsAccepted() {
        var validator = SecurityConfig.ownerValidator(issuer, owner);
        assertFalse(validator.validate(token(owner, issuer, "authenticated", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token("another-user", issuer, "authenticated", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token(owner, "https://wrong.example", "authenticated", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token(owner, issuer, "anon", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token(owner, issuer, "authenticated", Instant.now().minusSeconds(120))).hasErrors());
    }
}
