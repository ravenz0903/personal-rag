package com.tracker.rag.e2e.tier1;

import com.tracker.rag.e2e.contract.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 1: Domain Scoping and Strict Isolation Tests (F01, F02, F03)")
public class Tier1DomainScopingAndIsolationTest {

    @Nested
    @DisplayName("F01: Three-Domain Scoping Contract Tests")
    class ThreeDomainScopingTests {

        @Test
        @DisplayName("TC-F01-01: DomainScope contains exactly ACADEMICS, PERSONAL, CAREER")
        void testDomainScopeEnumValues() {
            DomainScope[] values = DomainScope.values();
            assertEquals(3, values.length, "DomainScope must contain exactly 3 first-class partitions");
            assertTrue(Arrays.asList(values).contains(DomainScope.ACADEMICS));
            assertTrue(Arrays.asList(values).contains(DomainScope.PERSONAL));
            assertTrue(Arrays.asList(values).contains(DomainScope.CAREER));
        }

        @Test
        @DisplayName("TC-F01-02: ALL_DOMAINS contains all 3 partitions unmodifiable")
        void testAllDomainsSet() {
            Set<DomainScope> all = DomainScope.ALL_DOMAINS;
            assertEquals(3, all.size());
            assertTrue(all.containsAll(Set.of(DomainScope.ACADEMICS, DomainScope.PERSONAL, DomainScope.CAREER)));
            assertThrows(UnsupportedOperationException.class, () -> all.add(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("TC-F01-03: PUBLIC_DEFAULT contains only ACADEMICS and CAREER")
        void testPublicDefaultExcludesPersonal() {
            Set<DomainScope> defaults = DomainScope.PUBLIC_DEFAULT;
            assertEquals(2, defaults.size());
            assertTrue(defaults.contains(DomainScope.ACADEMICS));
            assertTrue(defaults.contains(DomainScope.CAREER));
            assertFalse(defaults.contains(DomainScope.PERSONAL), "PUBLIC_DEFAULT must strictly exclude PERSONAL");
        }

        @Test
        @DisplayName("TC-F01-04: Entity records correctly tag their domain partition")
        void testEntityRecordDomainTagging() {
            TaskRecord task = TaskRecord.create(DomainScope.ACADEMICS, "CS Homework", TaskPriority.HIGH, null);
            assertEquals(DomainScope.ACADEMICS, task.domain());

            CareerApplicationRecord career = CareerApplicationRecord.create("Google", "SWE", CareerStatus.APPLIED);
            assertEquals(DomainScope.CAREER, career.domain());

            HabitRecord habit = HabitRecord.create("Exercise", HabitFrequency.DAILY, 1, "UTC");
            assertEquals(DomainScope.PERSONAL, habit.domain());
        }

        @Test
        @DisplayName("TC-F01-05: Parse string domain case-insensitively")
        void testParseDomainCaseInsensitive() {
            assertEquals(DomainScope.ACADEMICS, DomainScope.parse("academics"));
            assertEquals(DomainScope.PERSONAL, DomainScope.parse("Personal"));
            assertEquals(DomainScope.CAREER, DomainScope.parse("CAREER"));
        }
    }

    @Nested
    @DisplayName("F02: Personal Domain Isolation Contract Tests")
    class PersonalDomainIsolationTests {

        @Test
        @DisplayName("TC-F02-01: Academics-scoped query returns 0 items from Personal domain")
        void testAcademicsScopeReturnsZeroPersonal() {
            List<TaskRecord> dataset = List.of(
                    TaskRecord.create(DomainScope.ACADEMICS, "Study Math", TaskPriority.HIGH, null),
                    TaskRecord.create(DomainScope.PERSONAL, "Doctor Appointment", TaskPriority.MEDIUM, null),
                    TaskRecord.create(DomainScope.CAREER, "Submit Resume", TaskPriority.HIGH, null)
            );

            Set<DomainScope> queryScope = DomainScope.resolveWithPersonalIsolation(Set.of(DomainScope.ACADEMICS));
            List<TaskRecord> filtered = dataset.stream().filter(t -> queryScope.contains(t.domain())).toList();

            assertEquals(1, filtered.size());
            assertEquals("Study Math", filtered.get(0).title());
            assertTrue(filtered.stream().noneMatch(t -> t.domain() == DomainScope.PERSONAL));
        }

        @Test
        @DisplayName("TC-F02-02: Career-scoped query returns 0 items from Personal domain")
        void testCareerScopeReturnsZeroPersonal() {
            List<TaskRecord> dataset = List.of(
                    TaskRecord.create(DomainScope.ACADEMICS, "Study Math", TaskPriority.HIGH, null),
                    TaskRecord.create(DomainScope.PERSONAL, "Dentist", TaskPriority.LOW, null),
                    TaskRecord.create(DomainScope.CAREER, "OA Prep", TaskPriority.HIGH, null)
            );

            Set<DomainScope> queryScope = DomainScope.resolveWithPersonalIsolation(Set.of(DomainScope.CAREER));
            List<TaskRecord> filtered = dataset.stream().filter(t -> queryScope.contains(t.domain())).toList();

            assertEquals(1, filtered.size());
            assertEquals("OA Prep", filtered.get(0).title());
            assertTrue(filtered.stream().noneMatch(t -> t.domain() == DomainScope.PERSONAL));
        }

        @Test
        @DisplayName("TC-F02-03: General scope [ACADEMICS, CAREER] returns 0 items from Personal domain")
        void testPublicDefaultScopeReturnsZeroPersonal() {
            List<TaskRecord> dataset = List.of(
                    TaskRecord.create(DomainScope.ACADEMICS, "Chemistry Lab", TaskPriority.HIGH, null),
                    TaskRecord.create(DomainScope.PERSONAL, "Buy Groceries", TaskPriority.MEDIUM, null),
                    TaskRecord.create(DomainScope.CAREER, "Interview at Meta", TaskPriority.URGENT, null)
            );

            Set<DomainScope> queryScope = DomainScope.defaultGeneralScope();
            List<TaskRecord> filtered = dataset.stream().filter(t -> queryScope.contains(t.domain())).toList();

            assertEquals(2, filtered.size());
            assertTrue(filtered.stream().noneMatch(t -> t.domain() == DomainScope.PERSONAL));
        }

        @Test
        @DisplayName("TC-F02-04: Explicit opt-in returns Personal domain records")
        void testExplicitPersonalOptInReturnsPersonal() {
            List<TaskRecord> dataset = List.of(
                    TaskRecord.create(DomainScope.ACADEMICS, "Chemistry Lab", TaskPriority.HIGH, null),
                    TaskRecord.create(DomainScope.PERSONAL, "Buy Groceries", TaskPriority.MEDIUM, null),
                    TaskRecord.create(DomainScope.CAREER, "Interview at Meta", TaskPriority.URGENT, null)
            );

            Set<DomainScope> queryScope = DomainScope.resolveWithPersonalIsolation(Set.of(DomainScope.PERSONAL));
            List<TaskRecord> filtered = dataset.stream().filter(t -> queryScope.contains(t.domain())).toList();

            assertEquals(1, filtered.size());
            assertEquals("Buy Groceries", filtered.get(0).title());
            assertEquals(DomainScope.PERSONAL, filtered.get(0).domain());
        }

        @Test
        @DisplayName("TC-F02-05: isPersonalIncluded helper correctly distinguishes scope containment")
        void testIsPersonalIncludedHelper() {
            assertFalse(DomainScope.isPersonalIncluded(DomainScope.PUBLIC_DEFAULT));
            assertFalse(DomainScope.isPersonalIncluded(Set.of(DomainScope.ACADEMICS)));
            assertFalse(DomainScope.isPersonalIncluded(Set.of(DomainScope.CAREER)));
            assertTrue(DomainScope.isPersonalIncluded(Set.of(DomainScope.PERSONAL)));
            assertTrue(DomainScope.isPersonalIncluded(DomainScope.ALL_DOMAINS));
            assertFalse(DomainScope.isPersonalIncluded(null));
        }
    }

    @Nested
    @DisplayName("F03: Fail-Closed Scope Validation Contract Tests")
    class FailClosedScopeValidationTests {

        @Test
        @DisplayName("TC-F03-01: Null scope collection throws UnscopedQueryException")
        void testNullScopeCollectionThrows() {
            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(null));
        }

        @Test
        @DisplayName("TC-F03-02: Empty scope collection throws UnscopedQueryException")
        void testEmptyScopeCollectionThrows() {
            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(Collections.emptySet()));
        }

        @Test
        @DisplayName("TC-F03-03: Collection containing only null elements throws UnscopedQueryException")
        void testCollectionWithOnlyNullsThrows() {
            List<DomainScope> nullList = new ArrayList<>();
            nullList.add(null);
            nullList.add(null);
            assertThrows(UnscopedQueryException.class, () -> DomainScope.validateScopes(nullList));
        }

        @Test
        @DisplayName("TC-F03-04: Null or blank string domain throws UnscopedQueryException")
        void testBlankDomainStringThrows() {
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse(null));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse(""));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parse("   "));
        }

        @Test
        @DisplayName("TC-F03-05: Unrecognized domain token throws InvalidDomainException")
        void testInvalidDomainTokenThrows() {
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("UNKNOWN_DOMAIN"));
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("WORK"));
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("ALL"));
        }
    }
}
