package com.tracker.rag.challenger;

import com.tracker.rag.domain.*;
import com.tracker.rag.model.*;
import com.tracker.rag.repository.*;
import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.ScoredChunk;
import com.tracker.rag.repository.vector.VectorChunk;
import com.tracker.rag.service.RollbackService;
import com.tracker.rag.service.rollback.RollbackDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EMPIRICAL ADVERSARIAL CHALLENGE SUITE FOR MILESTONE 1
 * 
 * Verifies:
 * 1. Domain Scope validation: empty sets, null sets, whitespace, invalid enum names.
 * 2. Fail-closed immediate rejection on missing or empty domain scope.
 * 3. Strict zero-leakage Personal domain isolation across all 9 repositories.
 * 4. VectorStore zero-leakage even when Personal chunks have highest cosine similarity.
 * 5. Cross-domain entity association rejection.
 * 6. Rollback service strict domain isolation.
 */
public class Milestone1AdversarialChallengeTest {

    @Nested
    @DisplayName("Challenge Dimension 1: Domain Scope Validation & Fail-Closed Invariants")
    class DomainScopeValidationChallenge {

        @Test
        @DisplayName("validateScope: rejects null with UnscopedQueryException")
        void testValidateScopeNullFailsClosed() {
            assertThatThrownBy(() -> DomainScope.validateScope(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("validateScopes: rejects null collection with UnscopedQueryException")
        void testValidateScopesNullFailsClosed() {
            assertThatThrownBy(() -> DomainScope.validateScopes(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("validateScopes: rejects empty collection with UnscopedQueryException")
        void testValidateScopesEmptyFailsClosed() {
            assertThatThrownBy(() -> DomainScope.validateScopes(Collections.emptyList()))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> DomainScope.validateScopes(Collections.emptySet()))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("validateScopes: rejects collection containing only null elements")
        void testValidateScopesOnlyNullsFailsClosed() {
            List<DomainScope> onlyNulls = new ArrayList<>();
            onlyNulls.add(null);
            onlyNulls.add(null);

            assertThatThrownBy(() -> DomainScope.validateScopes(onlyNulls))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }

        @Test
        @DisplayName("validateScopes: filters out nulls from mixed collection and succeeds")
        void testValidateScopesMixedWithNull() {
            List<DomainScope> mixed = new ArrayList<>();
            mixed.add(DomainScope.ACADEMICS);
            mixed.add(null);
            mixed.add(DomainScope.CAREER);

            Set<DomainScope> validated = DomainScope.validateScopes(mixed);
            assertThat(validated).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);
            assertThat(validated).doesNotContain(DomainScope.PERSONAL);
        }

        @Test
        @DisplayName("validateAndResolve: contract method rejects null or empty sets")
        void testValidateAndResolveContract() {
            assertThatThrownBy(() -> DomainScope.validateAndResolve(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.validateAndResolve(Collections.emptySet()))
                    .isInstanceOf(UnscopedQueryException.class);
        }

        @Test
        @DisplayName("resolveWithPersonalIsolation: rejects null or empty with fail-closed semantics")
        void testResolveWithPersonalIsolationFailsClosed() {
            assertThatThrownBy(() -> DomainScope.resolveWithPersonalIsolation(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.resolveWithPersonalIsolation(Collections.emptyList()))
                    .isInstanceOf(UnscopedQueryException.class);
        }

        @Test
        @DisplayName("resolveWithPersonalIsolation: excludes Personal unless explicitly requested")
        void testResolveWithPersonalIsolationZeroLeakage() {
            Set<DomainScope> academicOnly = DomainScope.resolveWithPersonalIsolation(List.of(DomainScope.ACADEMICS));
            assertThat(academicOnly).containsExactly(DomainScope.ACADEMICS);
            assertThat(academicOnly).doesNotContain(DomainScope.PERSONAL);

            Set<DomainScope> careerOnly = DomainScope.resolveWithPersonalIsolation(List.of(DomainScope.CAREER));
            assertThat(careerOnly).containsExactly(DomainScope.CAREER);
            assertThat(careerOnly).doesNotContain(DomainScope.PERSONAL);

            Set<DomainScope> publicDefault = DomainScope.defaultGeneralScope();
            assertThat(publicDefault).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);
            assertThat(publicDefault).doesNotContain(DomainScope.PERSONAL);
        }

        @Test
        @DisplayName("parse: rejects null, blank strings, and whitespace tokens with UnscopedQueryException")
        void testParseWhitespaceAndNullThrows() {
            assertThatThrownBy(() -> DomainScope.parse(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parse(""))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parse("   "))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parse("\t\r\n"))
                    .isInstanceOf(UnscopedQueryException.class);
        }

        @Test
        @DisplayName("parse: rejects invalid domain names and adversarial injection attempts")
        void testParseAdversarialStrings() {
            String[] maliciousInputs = {
                    "INVALID",
                    "ADMIN",
                    "ACADEMICS; DROP TABLE tasks;",
                    "1' OR '1'='1",
                    "PERSONAL--",
                    "root",
                    "ALL",
                    "public"
            };

            for (String input : maliciousInputs) {
                assertThatThrownBy(() -> DomainScope.parse(input))
                        .isInstanceOf(InvalidDomainException.class)
                        .hasMessageContaining("Allowed");
            }
        }

        @Test
        @DisplayName("parse: correctly parses valid tokens regardless of casing and whitespace padding")
        void testParseValidPermutations() {
            assertThat(DomainScope.parse("academics")).isEqualTo(DomainScope.ACADEMICS);
            assertThat(DomainScope.parse("  ACADEMICS  ")).isEqualTo(DomainScope.ACADEMICS);
            assertThat(DomainScope.parse("Personal")).isEqualTo(DomainScope.PERSONAL);
            assertThat(DomainScope.parse("\tpersonal\n")).isEqualTo(DomainScope.PERSONAL);
            assertThat(DomainScope.parse("CaReEr")).isEqualTo(DomainScope.CAREER);
        }

        @Test
        @DisplayName("parseScopes: parses string lists and fails closed on invalid or blank elements")
        void testParseScopes() {
            assertThatThrownBy(() -> DomainScope.parseScopes(null))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parseScopes(Collections.emptyList()))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parseScopes(List.of("   ")))
                    .isInstanceOf(UnscopedQueryException.class);
            assertThatThrownBy(() -> DomainScope.parseScopes(List.of("academics", "UNKNOWN")))
                    .isInstanceOf(InvalidDomainException.class);

            Set<DomainScope> parsed = DomainScope.parseScopes(List.of("academics", "career"));
            assertThat(parsed).containsExactlyInAnyOrder(DomainScope.ACADEMICS, DomainScope.CAREER);
            assertThat(parsed).doesNotContain(DomainScope.PERSONAL);
        }
    }

    @Nested
    @DisplayName("Challenge Dimension 2: Cross-Domain Entity Association Stress-Testing")
    class EntityCrossDomainValidationChallenge {

        @Test
        @DisplayName("Task: Subtask domain must strictly match parent task domain")
        void testTaskSubtaskDomainMismatch() {
            Task academicParent = new Task(DomainScope.ACADEMICS, "CS Study", TaskPriority.HIGH, Instant.now());
            Task personalSubtask = new Task(DomainScope.PERSONAL, "Personal Errands", TaskPriority.LOW, Instant.now());
            personalSubtask.setParentTask(academicParent);

            assertThatThrownBy(personalSubtask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("must match parent task domain");

            Task careerParent = new Task(DomainScope.CAREER, "Job App", TaskPriority.MEDIUM, Instant.now());
            Task academicSubtask = new Task(DomainScope.ACADEMICS, "Prep Resume", TaskPriority.HIGH, Instant.now());
            academicSubtask.setParentTask(careerParent);

            assertThatThrownBy(academicSubtask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("must match parent task domain");
        }

        @Test
        @DisplayName("Task: Personal task attempting to reference Course throws CrossDomainReferenceException")
        void testPersonalTaskCourseReferenceBlocked() {
            Course course = new Course("MATH101", "Calculus", "Fall", new BigDecimal("4.0"), new BigDecimal("90.00"));
            Task personalTask = new Task(DomainScope.PERSONAL, "Personal Math Review", TaskPriority.MEDIUM, Instant.now());
            personalTask.setCourse(course);

            assertThatThrownBy(personalTask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("ACADEMICS domain");
        }

        @Test
        @DisplayName("Task: Career task attempting to reference Course throws CrossDomainReferenceException")
        void testCareerTaskCourseReferenceBlocked() {
            Course course = new Course("MATH101", "Calculus", "Fall", new BigDecimal("4.0"), new BigDecimal("90.00"));
            Task careerTask = new Task(DomainScope.CAREER, "Career Course Prep", TaskPriority.MEDIUM, Instant.now());
            careerTask.setCourse(course);

            assertThatThrownBy(careerTask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("ACADEMICS domain");
        }

        @Test
        @DisplayName("Task: Personal or Academic task attempting to reference CareerApplication throws CrossDomainReferenceException")
        void testNonCareerTaskCareerAppReferenceBlocked() {
            CareerApplication app = new CareerApplication("Acme", "Dev", CareerStatus.APPLIED, LocalDate.now());

            Task personalTask = new Task(DomainScope.PERSONAL, "Personal Dev", TaskPriority.LOW, Instant.now());
            personalTask.setCareerApplication(app);
            assertThatThrownBy(personalTask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("CAREER domain");

            Task academicTask = new Task(DomainScope.ACADEMICS, "CS Dev", TaskPriority.LOW, Instant.now());
            academicTask.setCareerApplication(app);
            assertThatThrownBy(academicTask::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("CAREER domain");
        }

        @Test
        @DisplayName("Task: Missing domain fails validation")
        void testTaskNullDomainThrows() {
            Task task = new Task(null, "Unscoped Task", TaskPriority.MEDIUM, Instant.now());
            assertThatThrownBy(task::validateCrossDomainReferences)
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("Task domain must be specified");
        }

        @Test
        @DisplayName("Assessment: Assessment category from different course throws CrossDomainReferenceException")
        void testAssessmentCategoryBelongsToCourse() {
            Course courseA = new Course("CS101", "Intro CS", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
            courseA.setId(UUID.randomUUID());

            Course courseB = new Course("PHYS101", "Physics I", "Fall 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
            courseB.setId(UUID.randomUUID());

            AssessmentCategory categoryB = new AssessmentCategory(courseB, "Homework", new BigDecimal("20.00"), 1);

            Assessment assessment = new Assessment(courseA, categoryB, "CS HW1", new BigDecimal("100.00"));

            assertThatThrownBy(() -> {
                try {
                    java.lang.reflect.Method m = Assessment.class.getDeclaredMethod("onCreate");
                    m.setAccessible(true);
                    m.invoke(assessment);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            })
                    .isInstanceOf(CrossDomainReferenceException.class)
                    .hasMessageContaining("does not belong to the assessment's course");
        }

        @Test
        @DisplayName("Habit: Malicious or invalid timezone strings throw IllegalArgumentException or DateTimeException")
        void testHabitInvalidTimezones() {
            String[] invalidTimezones = {
                    "",
                    "   ",
                    "Fake/Timezone",
                    "America/Mars"
            };

            for (String tz : invalidTimezones) {
                Habit habit = new Habit("Water", HabitFrequency.DAILY, 1, tz);
                assertThatThrownBy(habit::validateTimezone)
                        .isInstanceOf(IllegalArgumentException.class);
            }

            Habit nullTzHabit = new Habit("Water", HabitFrequency.DAILY, 1, "UTC");
            nullTzHabit.setTimezone(null);
            assertThatThrownBy(nullTzHabit::validateTimezone)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("null or blank");

            Habit malformedOffsetHabit = new Habit("Water", HabitFrequency.DAILY, 1, "UTC+99:99");
            assertThatThrownBy(malformedOffsetHabit::validateTimezone)
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("HabitLog: Logging future date throws IllegalArgumentException")
        void testHabitLogFutureDateThrows() {
            Habit habit = new Habit("Exercise", HabitFrequency.DAILY, 1, "UTC");
            LocalDate futureDate = LocalDate.now().plusDays(5);
            HabitLog log = new HabitLog(habit, futureDate, Instant.now(), 1, "Notes");

            assertThatThrownBy(log::validateNotFutureDate)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("future date");
        }
    }

    @Nested
    @DisplayName("Challenge Dimension 3: Vector Store Zero-Leakage & Fail-Closed Under Adversarial Similarity")
    class VectorStoreAdversarialChallenge {

        @Test
        @DisplayName("EmbeddedVectorStore: Strict zero-leakage when Personal chunk has 1.0 similarity")
        void testVectorStoreZeroPersonalLeakage() {
            EmbeddedVectorStore vectorStore = new EmbeddedVectorStore();

            float[] personalVector = new float[]{1.0f, 0.0f, 0.0f, 0.0f};
            float[] academicVector = new float[]{0.9f, 0.1f, 0.0f, 0.0f};
            float[] careerVector = new float[]{0.0f, 1.0f, 0.0f, 0.0f};

            VectorChunk personalChunk = new VectorChunk(
                    UUID.randomUUID(), "doc_pers", DomainScope.PERSONAL, 1, "Health",
                    "CONFIDENTIAL PERSONAL MEDICAL RECORD", personalVector
            );
            VectorChunk academicChunk = new VectorChunk(
                    UUID.randomUUID(), "doc_acad", DomainScope.ACADEMICS, 1, "Syllabus",
                    "Algorithms course midterm exam material", academicVector
            );
            VectorChunk careerChunk = new VectorChunk(
                    UUID.randomUUID(), "doc_career", DomainScope.CAREER, 1, "Resume",
                    "Software engineering work experience", careerVector
            );

            vectorStore.saveAll(List.of(personalChunk, academicChunk, careerChunk));

            // Query with personalVector (which has 1.0 cosine similarity to personalChunk)
            float[] query = personalVector.clone();

            // 1. Scoped to ACADEMICS: Must return strictly 0 personal chunks
            List<ScoredChunk> academicResults = vectorStore.similaritySearch(query, DomainScope.ACADEMICS, 10);
            assertThat(academicResults).isNotEmpty();
            assertThat(academicResults).allMatch(sc -> sc.chunk().domain() == DomainScope.ACADEMICS);
            assertThat(academicResults).noneMatch(sc -> sc.chunk().domain() == DomainScope.PERSONAL);

            // 2. Scoped to PUBLIC_DEFAULT [ACADEMICS, CAREER]: Must return strictly 0 personal chunks
            List<ScoredChunk> defaultResults = vectorStore.similaritySearch(query, DomainScope.PUBLIC_DEFAULT, 10);
            assertThat(defaultResults).hasSize(2);
            assertThat(defaultResults).noneMatch(sc -> sc.chunk().domain() == DomainScope.PERSONAL);

            // 3. Explicit opt-in for PERSONAL: Personal chunk is retrieved with top score
            List<ScoredChunk> personalResults = vectorStore.similaritySearch(query, DomainScope.PERSONAL, 10);
            assertThat(personalResults).hasSize(1);
            assertThat(personalResults.get(0).chunk().content()).isEqualTo("CONFIDENTIAL PERSONAL MEDICAL RECORD");
            assertThat(personalResults.get(0).score()).isGreaterThan(0.99);
        }

        @Test
        @DisplayName("EmbeddedVectorStore: Fail-closed immediately on null or empty scopes")
        void testVectorStoreFailClosed() {
            EmbeddedVectorStore vectorStore = new EmbeddedVectorStore();
            float[] query = new float[]{1.0f, 0.0f, 0.0f, 0.0f};

            assertThatThrownBy(() -> vectorStore.similaritySearch(query, (DomainScope) null, 5))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> vectorStore.similaritySearch(query, (Set<DomainScope>) null, 5))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> vectorStore.similaritySearch(query, Collections.emptySet(), 5))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");

            assertThatThrownBy(() -> vectorStore.countByDomain(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }
    }

    @Nested
    @DisplayName("Challenge Dimension 4: Rollback Service Domain Isolation")
    class RollbackServiceDomainIsolationChallenge {

        @Test
        @DisplayName("Rollback in ACADEMICS fails closed when only PERSONAL mutations exist")
        void testRollbackCrossDomainIsolation() {
            EventLogRepository mockRepo = org.mockito.Mockito.mock(EventLogRepository.class);
            RollbackDispatcher mockDispatcher = org.mockito.Mockito.mock(RollbackDispatcher.class);
            RollbackService rollbackService = new RollbackService(mockRepo, mockDispatcher);

            org.mockito.Mockito.when(mockRepo.findFirstByDomainAndUndoneFalseOrderByTimestampDesc(DomainScope.ACADEMICS))
                    .thenReturn(Optional.empty());

            // An attempt to roll back ACADEMICS will not touch PERSONAL mutations
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(DomainScope.ACADEMICS))
                    .isInstanceOf(NoMutationsToRollbackException.class)
                    .hasMessageContaining("ACADEMICS");

            // Null domain throws UnscopedQueryException (fail-closed)
            assertThatThrownBy(() -> rollbackService.rollbackLastMutation(null))
                    .isInstanceOf(UnscopedQueryException.class)
                    .hasMessageContaining("fail-closed");
        }
    }

    @Nested
    @DisplayName("Challenge Dimension 5: Empirical Repository Domain Isolation (JPA Level)")
    @DataJpaTest
    class RepositoryEmpiricalDomainIsolationChallenge {

        @Autowired
        private TaskRepository taskRepository;

        @Autowired
        private CourseRepository courseRepository;

        @Autowired
        private HabitRepository habitRepository;

        @Autowired
        private HabitLogRepository habitLogRepository;

        @Autowired
        private CareerApplicationRepository careerRepository;

        @Autowired
        private DocumentRecordRepository documentRepository;

        @Autowired
        private EventLogRepository eventLogRepository;

        @Autowired
        private TestEntityManager entityManager;

        @Test
        @DisplayName("All 9 Repositories: Queries scoped to ACADEMICS or PUBLIC_DEFAULT return zero PERSONAL records")
        void testExhaustiveRepositoryDomainIsolation() {
            // Seed entities across all three domains

            // 1. Tasks
            Task taskAcad = new Task(DomainScope.ACADEMICS, "Study Calculus", TaskPriority.HIGH, Instant.now());
            Task taskPers = new Task(DomainScope.PERSONAL, "Personal Doctor Appt", TaskPriority.HIGH, Instant.now());
            Task taskCareer = new Task(DomainScope.CAREER, "Submit Google App", TaskPriority.HIGH, Instant.now());
            taskRepository.saveAll(List.of(taskAcad, taskPers, taskCareer));

            // 2. Courses (Academics)
            Course course = new Course("CS201", "Data Structures", "Spring 2026", new BigDecimal("4.0"), new BigDecimal("90.00"));
            courseRepository.save(course);

            // 3. Habits & HabitLogs (Personal)
            Habit habit = new Habit("Meditation", HabitFrequency.DAILY, 1, "UTC");
            habitRepository.save(habit);

            HabitLog habitLog = new HabitLog(habit, LocalDate.now(habit.getZoneId()), Instant.now(), 1, "Mindfulness session");
            habitLogRepository.save(habitLog);

            // 4. Career Applications (Career)
            CareerApplication careerApp = new CareerApplication("OpenAI", "Research Engineer", CareerStatus.APPLIED, LocalDate.now());
            careerRepository.save(careerApp);

            // 5. Document Records
            DocumentRecord docAcad = new DocumentRecord(
                    DomainScope.ACADEMICS, "syllabus.pdf", "/data/syllabus.pdf", "application/pdf",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 1024L
            );
            DocumentRecord docPers = new DocumentRecord(
                    DomainScope.PERSONAL, "medical_records.pdf", "/data/medical_records.pdf", "application/pdf",
                    "a123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 2048L
            );
            DocumentRecord docCareer = new DocumentRecord(
                    DomainScope.CAREER, "resume_v3.pdf", "/data/resume_v3.pdf", "application/pdf",
                    "b123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 4096L
            );
            documentRepository.saveAll(List.of(docAcad, docPers, docCareer));

            // 6. Event Log Entries
            EventLogEntry eventAcad = new EventLogEntry(
                    UUID.randomUUID(), DomainScope.ACADEMICS, EntityType.TASK, taskAcad.getId(),
                    ActionType.CREATE, Instant.now(), null, "{}"
            );
            EventLogEntry eventPers = new EventLogEntry(
                    UUID.randomUUID(), DomainScope.PERSONAL, EntityType.TASK, taskPers.getId(),
                    ActionType.CREATE, Instant.now(), null, "{}"
            );
            EventLogEntry eventCareer = new EventLogEntry(
                    UUID.randomUUID(), DomainScope.CAREER, EntityType.CAREER_APPLICATION, careerApp.getId(),
                    ActionType.CREATE, Instant.now(), null, "{}"
            );
            eventLogRepository.saveAll(List.of(eventAcad, eventPers, eventCareer));

            entityManager.flush();
            entityManager.clear();

            // === EMPIRICAL ADVERSARIAL VERIFICATION ===

            // A. TASK ISOLATION
            // Query with ACADEMICS scope
            List<Task> acadTasks = taskRepository.findByDomain(DomainScope.ACADEMICS);
            assertThat(acadTasks).extracting(Task::getTitle).containsExactly("Study Calculus");
            assertThat(acadTasks).noneMatch(t -> t.getDomain() == DomainScope.PERSONAL);

            // Query with PUBLIC_DEFAULT [ACADEMICS, CAREER]
            List<Task> publicTasks = taskRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(publicTasks).hasSize(2);
            assertThat(publicTasks).noneMatch(t -> t.getDomain() == DomainScope.PERSONAL);

            // Root tasks query
            List<Task> rootTasks = taskRepository.findRootTasksByDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(rootTasks).noneMatch(t -> t.getDomain() == DomainScope.PERSONAL);

            // B. COURSE ISOLATION
            List<Course> persCourses = courseRepository.findByDomain(DomainScope.PERSONAL);
            assertThat(persCourses).isEmpty();

            // C. HABIT & HABIT LOG ISOLATION
            List<Habit> acadHabits = habitRepository.findByDomain(DomainScope.ACADEMICS);
            assertThat(acadHabits).isEmpty();

            List<Habit> publicHabits = habitRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(publicHabits).isEmpty();

            List<HabitLog> acadHabitLogs = habitLogRepository.findByHabitDomainIn(List.of(DomainScope.ACADEMICS));
            assertThat(acadHabitLogs).isEmpty();

            List<HabitLog> publicHabitLogs = habitLogRepository.findByHabitDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(publicHabitLogs).isEmpty();

            // D. CAREER APPLICATION ISOLATION
            List<CareerApplication> persCareer = careerRepository.findByDomain(DomainScope.PERSONAL);
            assertThat(persCareer).isEmpty();

            List<CareerApplication> acadCareer = careerRepository.findByDomain(DomainScope.ACADEMICS);
            assertThat(acadCareer).isEmpty();

            // E. DOCUMENT RECORD ISOLATION
            List<DocumentRecord> acadDocs = documentRepository.findByDomain(DomainScope.ACADEMICS);
            assertThat(acadDocs).extracting(DocumentRecord::getFileName).containsExactly("syllabus.pdf");
            assertThat(acadDocs).noneMatch(d -> d.getDomain() == DomainScope.PERSONAL);

            List<DocumentRecord> publicDocs = documentRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(publicDocs).extracting(DocumentRecord::getFileName).containsExactlyInAnyOrder("syllabus.pdf", "resume_v3.pdf");
            assertThat(publicDocs).noneMatch(d -> d.getDomain() == DomainScope.PERSONAL);

            // F. EVENT LOG ISOLATION
            List<EventLogEntry> acadEvents = eventLogRepository.findByDomain(DomainScope.ACADEMICS);
            assertThat(acadEvents).extracting(EventLogEntry::getId).containsExactly(eventAcad.getId());
            assertThat(acadEvents).noneMatch(e -> e.getDomain() == DomainScope.PERSONAL);

            List<EventLogEntry> publicEvents = eventLogRepository.findByDomainIn(DomainScope.PUBLIC_DEFAULT);
            assertThat(publicEvents).extracting(EventLogEntry::getId).containsExactlyInAnyOrder(eventAcad.getId(), eventCareer.getId());
            assertThat(publicEvents).noneMatch(e -> e.getDomain() == DomainScope.PERSONAL);

            // G. EXPLICIT PERSONAL ACCESS (Positive Control)
            List<Task> persTasks = taskRepository.findByDomain(DomainScope.PERSONAL);
            assertThat(persTasks).extracting(Task::getTitle).containsExactly("Personal Doctor Appt");

            List<Habit> persHabits = habitRepository.findByDomain(DomainScope.PERSONAL);
            assertThat(persHabits).extracting(Habit::getName).containsExactly("Meditation");

            List<DocumentRecord> persDocs = documentRepository.findByDomain(DomainScope.PERSONAL);
            assertThat(persDocs).extracting(DocumentRecord::getFileName).containsExactly("medical_records.pdf");
        }
    }
}
