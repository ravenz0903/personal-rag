package com.tracker.rag.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainScopeTest {

    @Test
    @DisplayName("validateScope: valid scopes return unchanged")
    void testValidateScopeSuccess() {
        assertThat(DomainScope.validateScope(DomainScope.ACADEMICS)).isEqualTo(DomainScope.ACADEMICS);
        assertThat(DomainScope.validateScope(DomainScope.PERSONAL)).isEqualTo(DomainScope.PERSONAL);
        assertThat(DomainScope.validateScope(DomainScope.CAREER)).isEqualTo(DomainScope.CAREER);
    }

    @Test
    @DisplayName("validateScope: null scope throws UnscopedQueryException (fail-closed)")
    void testValidateScopeNullThrows() {
        assertThatThrownBy(() -> DomainScope.validateScope(null))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    @DisplayName("validateScopes: valid collections return unmodifiable non-empty set")
    void testValidateScopesSuccess() {
        Set<DomainScope> scopes = DomainScope.validateScopes(List.of(DomainScope.ACADEMICS, DomainScope.CAREER));
        assertThat(scopes).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);
    }

    @Test
    @DisplayName("validateScopes: null or empty collection throws UnscopedQueryException (fail-closed)")
    void testValidateScopesEmptyThrows() {
        assertThatThrownBy(() -> DomainScope.validateScopes(null))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");

        assertThatThrownBy(() -> DomainScope.validateScopes(Collections.emptyList()))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");

        List<DomainScope> nullList = Collections.singletonList(null);
        assertThatThrownBy(() -> DomainScope.validateScopes(nullList))
                .isInstanceOf(UnscopedQueryException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    @DisplayName("validateAndResolve: contract method validates non-null and non-empty")
    void testValidateAndResolve() {
        Set<DomainScope> valid = Set.of(DomainScope.ACADEMICS);
        assertThat(DomainScope.validateAndResolve(valid)).containsExactly(DomainScope.ACADEMICS);

        assertThatThrownBy(() -> DomainScope.validateAndResolve(null))
                .isInstanceOf(UnscopedQueryException.class);

        assertThatThrownBy(() -> DomainScope.validateAndResolve(Collections.emptySet()))
                .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("resolveWithPersonalIsolation: Personal is included if and only if explicitly requested")
    void testResolveWithPersonalIsolation() {
        // Query scoped to Academics only returns Academics (0 Personal)
        Set<DomainScope> academicScope = DomainScope.resolveWithPersonalIsolation(List.of(DomainScope.ACADEMICS));
        assertThat(academicScope).containsExactly(DomainScope.ACADEMICS);
        assertThat(academicScope).doesNotContain(DomainScope.PERSONAL);

        // General default scope excludes Personal
        Set<DomainScope> general = DomainScope.defaultGeneralScope();
        assertThat(general).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);
        assertThat(general).doesNotContain(DomainScope.PERSONAL);

        // Explicit opt-in returns Personal
        Set<DomainScope> personalScope = DomainScope.resolveWithPersonalIsolation(List.of(DomainScope.PERSONAL));
        assertThat(personalScope).containsExactly(DomainScope.PERSONAL);

        Set<DomainScope> multiScope = DomainScope.resolveWithPersonalIsolation(
                List.of(DomainScope.ACADEMICS, DomainScope.PERSONAL)
        );
        assertThat(multiScope).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.PERSONAL);
    }

    @Test
    @DisplayName("isPersonalIncluded: accurately detects presence of PERSONAL domain")
    void testIsPersonalIncluded() {
        assertThat(DomainScope.isPersonalIncluded(List.of(DomainScope.ACADEMICS))).isFalse();
        assertThat(DomainScope.isPersonalIncluded(DomainScope.defaultGeneralScope())).isFalse();
        assertThat(DomainScope.isPersonalIncluded(null)).isFalse();
        assertThat(DomainScope.isPersonalIncluded(List.of(DomainScope.PERSONAL))).isTrue();
        assertThat(DomainScope.isPersonalIncluded(DomainScope.ALL_DOMAINS)).isTrue();
    }

    @Test
    @DisplayName("parse: parses case-insensitively and trims input")
    void testParseValid() {
        assertThat(DomainScope.parse("ACADEMICS")).isEqualTo(DomainScope.ACADEMICS);
        assertThat(DomainScope.parse("personal")).isEqualTo(DomainScope.PERSONAL);
        assertThat(DomainScope.parse("  Career  ")).isEqualTo(DomainScope.CAREER);
    }

    @Test
    @DisplayName("parse: null or blank string throws UnscopedQueryException (fail-closed)")
    void testParseNullOrBlankThrows() {
        assertThatThrownBy(() -> DomainScope.parse(null))
                .isInstanceOf(UnscopedQueryException.class);
        assertThatThrownBy(() -> DomainScope.parse("   "))
                .isInstanceOf(UnscopedQueryException.class);
    }

    @Test
    @DisplayName("parse: invalid domain string throws InvalidDomainException")
    void testParseInvalidThrows() {
        assertThatThrownBy(() -> DomainScope.parse("FINANCE"))
                .isInstanceOf(InvalidDomainException.class)
                .hasMessageContaining("Allowed");
    }

    @Test
    @DisplayName("parseScopes: parses list of string tokens")
    void testParseScopes() {
        Set<DomainScope> result = DomainScope.parseScopes(List.of("academics", "career"));
        assertThat(result).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);

        assertThatThrownBy(() -> DomainScope.parseScopes(null))
                .isInstanceOf(UnscopedQueryException.class);
        assertThatThrownBy(() -> DomainScope.parseScopes(Collections.emptyList()))
                .isInstanceOf(UnscopedQueryException.class);
        assertThatThrownBy(() -> DomainScope.parseScopes(List.of("academics", "bogus")))
                .isInstanceOf(InvalidDomainException.class);
    }
}
