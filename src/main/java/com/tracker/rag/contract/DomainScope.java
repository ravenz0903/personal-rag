package com.tracker.rag.contract;

import java.util.*;
import java.util.stream.Collectors;

/**
 * First-class domain partitions for the Personal Tracker and Study RAG system.
 * Enforces strict fail-closed scoping and opt-in isolation for the PERSONAL domain.
 */
public enum DomainScope {
    ACADEMICS,
    PERSONAL,
    CAREER;

    public static final Set<DomainScope> ALL_DOMAINS = Collections.unmodifiableSet(
            EnumSet.allOf(DomainScope.class)
    );

    public static final Set<DomainScope> PUBLIC_DEFAULT = Collections.unmodifiableSet(
            EnumSet.of(ACADEMICS, CAREER)
    );

    public static DomainScope validateScope(DomainScope scope) {
        if (scope == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        return scope;
    }

    public static Set<DomainScope> validateScopes(Collection<DomainScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        Set<DomainScope> filtered = scopes.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        if (filtered.isEmpty()) {
            throw new UnscopedQueryException("Domain scope collection contains no valid domains (fail-closed)");
        }
        return filtered;
    }

    public static Set<DomainScope> resolveWithPersonalIsolation(Collection<DomainScope> requestedScopes) {
        return validateScopes(requestedScopes);
    }

    public static Set<DomainScope> defaultGeneralScope() {
        return PUBLIC_DEFAULT;
    }

    public static boolean isPersonalIncluded(Collection<DomainScope> scopes) {
        return scopes != null && scopes.contains(PERSONAL);
    }

    public static DomainScope parse(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new UnscopedQueryException("Domain scope string cannot be null or blank (fail-closed)");
        }
        try {
            return DomainScope.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new InvalidDomainException("Unknown domain scope: '" + name + "'. Allowed: " + Arrays.toString(values()));
        }
    }

    public static Set<DomainScope> parseScopes(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            throw new UnscopedQueryException("Domain scope list cannot be null or empty (fail-closed)");
        }
        Set<DomainScope> scopes = names.stream()
                .map(DomainScope::parse)
                .collect(Collectors.toUnmodifiableSet());
        return validateScopes(scopes);
    }
}

