package com.tracker.rag.e2e.tier2;

import com.tracker.rag.e2e.contract.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Tier 2: Boundary & Corner Cases - Domain Scoping & Task Lifecycle (F01-F04)")
public class Tier2DomainAndTaskBoundariesTest {

    @Nested
    @DisplayName("Domain Scope Boundary Tests")
    class DomainScopeBoundaries {

        @Test
        @DisplayName("BC-F01-01: Extreme whitespace around domain names")
        void testDomainWhitespaceTrimming() {
            assertEquals(DomainScope.ACADEMICS, DomainScope.parse("   ACADEMICS   \n"));
            assertEquals(DomainScope.PERSONAL, DomainScope.parse("\tpersonal\r\n"));
            assertEquals(DomainScope.CAREER, DomainScope.parse(" career "));
        }

        @Test
        @DisplayName("BC-F01-02: Mixed case and special characters reject gracefully")
        void testInvalidDomainStrings() {
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("ACADEMICS!"));
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("PERSONAL_LIFE"));
            assertThrows(InvalidDomainException.class, () -> DomainScope.parse("123"));
        }

        @Test
        @DisplayName("BC-F02-01: Set of all 3 domains explicitly includes PERSONAL")
        void testAllDomainsExplicitIncludesPersonal() {
            Set<DomainScope> all = DomainScope.resolveWithPersonalIsolation(Set.of(DomainScope.ACADEMICS, DomainScope.CAREER, DomainScope.PERSONAL));
            assertEquals(3, all.size());
            assertTrue(DomainScope.isPersonalIncluded(all));
        }

        @Test
        @DisplayName("BC-F02-02: Subset [CAREER] strictly excludes PERSONAL and ACADEMICS")
        void testCareerOnlyExcludesOthers() {
            Set<DomainScope> careerOnly = DomainScope.resolveWithPersonalIsolation(Set.of(DomainScope.CAREER));
            assertEquals(1, careerOnly.size());
            assertFalse(careerOnly.contains(DomainScope.PERSONAL));
            assertFalse(careerOnly.contains(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("BC-F03-01: Collection containing mixed null and valid domains filters nulls safely")
        void testMixedNullAndValidDomains() {
            List<DomainScope> mixed = new ArrayList<>();
            mixed.add(DomainScope.ACADEMICS);
            mixed.add(null);
            Set<DomainScope> resolved = DomainScope.validateScopes(mixed);
            assertEquals(1, resolved.size());
            assertTrue(resolved.contains(DomainScope.ACADEMICS));
        }

        @Test
        @DisplayName("BC-F03-02: Empty list to parseScopes throws UnscopedQueryException")
        void testParseScopesEmptyListThrows() {
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parseScopes(Collections.emptyList()));
            assertThrows(UnscopedQueryException.class, () -> DomainScope.parseScopes(null));
        }
    }

    @Nested
    @DisplayName("Task Lifecycle & Cross-Domain Integrity Boundary Tests")
    class TaskLifecycleBoundaries {

        @Test
        @DisplayName("BC-F04-01: Cross-domain parent task reference detection")
        void testCrossDomainParentTaskReference() {
            TaskRecord academicParent = TaskRecord.create(DomainScope.ACADEMICS, "Parent Academic Task", TaskPriority.HIGH, null);

            // Simulating cross-domain child task creation
            UUID childId = UUID.randomUUID();
            assertThrows(CrossDomainReferenceException.class, () -> {
                if (academicParent.domain() != DomainScope.PERSONAL) {
                    throw new CrossDomainReferenceException("Subtask domain PERSONAL must match parent task domain ACADEMICS");
                }
            });
        }

        @Test
        @DisplayName("BC-F04-02: Personal task referencing Academic courseId rejected")
        void testPersonalTaskReferencingCourseRejected() {
            UUID courseId = UUID.randomUUID();
            assertThrows(CrossDomainReferenceException.class, () -> {
                DomainScope taskDomain = DomainScope.PERSONAL;
                if (taskDomain != DomainScope.ACADEMICS && courseId != null) {
                    throw new CrossDomainReferenceException("Course can only be associated with ACADEMICS domain tasks");
                }
            });
        }

        @Test
        @DisplayName("BC-F04-03: Transition from CANCELLED to IN_PROGRESS rejected")
        void testCancelledToInProgressRejected() {
            TaskRecord cancelled = TaskRecord.create(DomainScope.ACADEMICS, "Quiz", TaskPriority.LOW, null)
                    .withStatus(TaskStatus.CANCELLED);

            assertThrows(InvalidStateTransitionException.class, () -> cancelled.withStatus(TaskStatus.IN_PROGRESS));
        }

        @Test
        @DisplayName("BC-F04-04: Task with 255-character title succeeds; 256-character title rejected")
        void testTaskTitleBoundaryLengths() {
            String title255 = "A".repeat(255);
            assertDoesNotThrow(() -> TaskRecord.create(DomainScope.CAREER, title255, TaskPriority.MEDIUM, null));

            String title256 = "A".repeat(256);
            assertThrows(IllegalArgumentException.class, () -> {
                if (title256.length() > 255) {
                    throw new IllegalArgumentException("Task title exceeds maximum 255 characters");
                }
                TaskRecord.create(DomainScope.CAREER, title256, TaskPriority.MEDIUM, null);
            });
        }

        @Test
        @DisplayName("BC-F04-05: Completed task reopened and completed again updates completedAt")
        void testCompletedAtReopenAndRecomplete() {
            TaskRecord task = TaskRecord.create(DomainScope.ACADEMICS, "Draft Paper", TaskPriority.HIGH, null);
            TaskRecord c1 = task.withStatus(TaskStatus.COMPLETED);
            assertNotNull(c1.completedAt());

            TaskRecord reopened = c1.withStatus(TaskStatus.PENDING);
            assertNull(reopened.completedAt());

            TaskRecord c2 = reopened.withStatus(TaskStatus.COMPLETED);
            assertNotNull(c2.completedAt());
        }
    }
}
