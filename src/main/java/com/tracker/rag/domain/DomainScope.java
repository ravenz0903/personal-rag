package com.tracker.rag.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * First-class domain partitions for the Personal Tracker and Study RAG system.
 * Enforces strict fail-closed scoping and opt-in isolation for the PERSONAL domain.
 */
public enum DomainScope {
    ACADEMICS,
    PERSONAL,
    CAREER;

    /**
     * Unmodifiable set containing all three valid domains.
     */
    public static final Set<DomainScope> ALL_DOMAINS = Collections.unmodifiableSet(
            EnumSet.allOf(DomainScope.class)
    );

    /**
     * Public default domain scope excluding the PERSONAL domain.
     * General and wildcard queries without explicit PERSONAL opt-in resolve to this set.
     */
    public static final Set<DomainScope> PUBLIC_DEFAULT = Collections.unmodifiableSet(
            EnumSet.of(ACADEMICS, CAREER)
    );

    /**
     * Fail-closed validation for a single domain scope.
     *
     * @param scope the domain scope to validate
     * @return the non-null domain scope
     * @throws UnscopedQueryException if scope is null
     */
    public static DomainScope validateScope(DomainScope scope) {
        if (scope == null) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        return scope;
    }

    /**
     * Fail-closed validation for a collection of domain scopes.
     * Rejects null or empty collections immediately with HTTP 400 semantics.
     *
     * @param scopes the set of domain scopes
     * @return an unmodifiable, non-empty Set of DomainScope
     * @throws UnscopedQueryException if scopes is null, empty, or contains only null elements
     */
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

    /**
     * Contract method from PROJECT.md:
     * Validates that domain scope is non-null and non-empty, otherwise throws UnscopedQueryException.
     *
     * @param scopes set of requested scopes
     * @return validated non-empty set of scopes
     */
    public static Set<DomainScope> validateAndResolve(Set<DomainScope> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new UnscopedQueryException("Domain scope must be explicitly specified (fail-closed)");
        }
        return validateScopes(scopes);
    }

    /**
     * Resolves requested scopes with strict Personal Domain Isolation.
     * - If requestedScopes is null or empty, throws UnscopedQueryException (fail-closed).
     * - If PERSONAL is explicitly present, PERSONAL is returned alongside any other requested scopes.
     * - If PERSONAL is not present, only the requested non-personal scopes are returned (zero personal leakage).
     *
     * @param requestedScopes the scopes requested by caller/API
     * @return validated, isolated set of domain scopes
     */
    public static Set<DomainScope> resolveWithPersonalIsolation(Collection<DomainScope> requestedScopes) {
        return validateScopes(requestedScopes);
    }

    /**
     * Helper returning safe default general scopes excluding PERSONAL.
     * Guaranteed to return only [ACADEMICS, CAREER].
     */
    public static Set<DomainScope> defaultGeneralScope() {
        return PUBLIC_DEFAULT;
    }

    /**
     * Checks whether the PERSONAL domain is explicitly included in the given scope collection.
     *
     * @param scopes collection of scopes
     * @return true if PERSONAL is present, false otherwise
     */
    public static boolean isPersonalIncluded(Collection<DomainScope> scopes) {
        return scopes != null && scopes.contains(PERSONAL);
    }

    /**
     * Parses a string token into a DomainScope with fail-closed validation.
     *
     * @param name domain name string (e.g. "ACADEMICS", "Personal", "career")
     * @return matching DomainScope
     * @throws UnscopedQueryException if name is null or blank
     * @throws InvalidDomainException if name is unrecognized
     */
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

    /**
     * Parses a collection of string tokens into a validated Set of DomainScopes.
     *
     * @param names collection of domain name strings
     * @return validated, unmodifiable Set of DomainScope
     * @throws UnscopedQueryException if collection is null or empty
     * @throws InvalidDomainException if any token is invalid
     */
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
