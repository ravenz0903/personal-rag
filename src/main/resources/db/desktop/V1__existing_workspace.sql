-- Baseline of the existing local schema. No user data or credentials.
CREATE CACHED TABLE "public"."assessment_categories"(
    "id" UUID NOT NULL,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "drop_lowest_n" INTEGER NOT NULL,
    "name" CHARACTER VARYING(128) NOT NULL,
    "weight" NUMERIC(5, 2) NOT NULL,
    "course_id" UUID NOT NULL
);
ALTER TABLE "public"."assessment_categories" ADD CONSTRAINT "public"."CONSTRAINT_3B" PRIMARY KEY("id");
CREATE INDEX "public"."idx_categories_course" ON "public"."assessment_categories"("course_id" NULLS FIRST);
CREATE CACHED TABLE "public"."assessments"(
    "id" UUID NOT NULL,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "due_date" TIMESTAMP(6) WITH TIME ZONE,
    "earned_score" NUMERIC(7, 2),
    "is_completed" BOOLEAN NOT NULL,
    "is_dropped" BOOLEAN NOT NULL,
    "max_score" NUMERIC(7, 2) NOT NULL,
    "title" CHARACTER VARYING(255) NOT NULL,
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "category_id" UUID NOT NULL,
    "course_id" UUID NOT NULL
);
ALTER TABLE "public"."assessments" ADD CONSTRAINT "public"."CONSTRAINT_4" PRIMARY KEY("id");
CREATE INDEX "public"."idx_assessments_course" ON "public"."assessments"("course_id" NULLS FIRST);
CREATE INDEX "public"."idx_assessments_category" ON "public"."assessments"("category_id" NULLS FIRST);
CREATE CACHED TABLE "public"."career_applications"(
    "id" UUID NOT NULL,
    "applied_date" DATE,
    "company" CHARACTER VARYING(255) NOT NULL,
    "contact_person" CHARACTER VARYING(255),
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "job_posting_url" CHARACTER VARYING,
    "location" CHARACTER VARYING(255),
    "notes" CHARACTER VARYING,
    "position" CHARACTER VARYING(255) NOT NULL,
    "salary_range" CHARACTER VARYING(128),
    "status" ENUM('APPLIED', 'INTERVIEW_SCHEDULED', 'OA_RECEIVED', 'OFFER', 'REJECTED', 'WISHLIST', 'WITHDRAWN') NOT NULL,
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
ALTER TABLE "public"."career_applications" ADD CONSTRAINT "public"."CONSTRAINT_B" PRIMARY KEY("id");
CREATE INDEX "public"."idx_career_apps_domain" ON "public"."career_applications"("domain" NULLS FIRST);
CREATE INDEX "public"."idx_career_apps_status" ON "public"."career_applications"("status" NULLS FIRST);
CREATE CACHED TABLE "public"."courses"(
    "id" UUID NOT NULL,
    "code" CHARACTER VARYING(32) NOT NULL,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "credits" NUMERIC(3, 1) NOT NULL,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "name" CHARACTER VARYING(255) NOT NULL,
    "target_grade" NUMERIC(5, 2) NOT NULL,
    "term" CHARACTER VARYING(64) NOT NULL,
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
ALTER TABLE "public"."courses" ADD CONSTRAINT "public"."CONSTRAINT_39" PRIMARY KEY("id");
CREATE INDEX "public"."idx_courses_domain" ON "public"."courses"("domain" NULLS FIRST);
CREATE CACHED TABLE "public"."event_log"(
    "id" UUID NOT NULL,
    "action" ENUM('CREATE', 'DELETE', 'ROLLBACK', 'UPDATE') NOT NULL,
    "diff_json" CHARACTER VARYING,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "entity_id" UUID NOT NULL,
    "entity_type" ENUM('ASSESSMENT', 'ASSESSMENT_CATEGORY', 'CAREER_APPLICATION', 'COURSE', 'DOCUMENT_RECORD', 'HABIT', 'HABIT_LOG', 'INTERVIEW_ROUND', 'TASK') NOT NULL,
    "metadata_json" CHARACTER VARYING,
    "payload_after" CHARACTER VARYING,
    "payload_before" CHARACTER VARYING,
    "timestamp" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "undone" BOOLEAN NOT NULL,
    "undone_by_event_id" UUID
);
ALTER TABLE "public"."event_log" ADD CONSTRAINT "public"."CONSTRAINT_1" PRIMARY KEY("id");
CREATE INDEX "public"."idx_event_log_domain_ts" ON "public"."event_log"("domain" NULLS FIRST, "timestamp" DESC NULLS LAST);
CREATE INDEX "public"."idx_event_log_entity" ON "public"."event_log"("entity_type" NULLS FIRST, "entity_id" NULLS FIRST);
CREATE INDEX "public"."idx_event_log_undone" ON "public"."event_log"("domain" NULLS FIRST, "undone" NULLS FIRST, "timestamp" DESC NULLS LAST);
CREATE CACHED TABLE "public"."habit_logs"(
    "id" UUID NOT NULL,
    "log_date" DATE NOT NULL,
    "logged_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "notes" CHARACTER VARYING,
    "units_completed" INTEGER NOT NULL,
    "habit_id" UUID NOT NULL
);
ALTER TABLE "public"."habit_logs" ADD CONSTRAINT "public"."CONSTRAINT_AD" PRIMARY KEY("id");
CREATE INDEX "public"."idx_habit_logs_date" ON "public"."habit_logs"("habit_id" NULLS FIRST, "log_date" NULLS FIRST);
CREATE CACHED TABLE "public"."habits"(
    "id" UUID NOT NULL,
    "archived" BOOLEAN NOT NULL,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "custom_days_mask" INTEGER,
    "description" CHARACTER VARYING,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "frequency" ENUM('CUSTOM_DAYS', 'DAILY', 'WEEKDAYS', 'WEEKENDS') NOT NULL,
    "name" CHARACTER VARYING(255) NOT NULL,
    "target_count_per_day" INTEGER NOT NULL,
    "timezone" CHARACTER VARYING(64) NOT NULL,
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
ALTER TABLE "public"."habits" ADD CONSTRAINT "public"."CONSTRAINT_B6F" PRIMARY KEY("id");
CREATE INDEX "public"."idx_habits_domain" ON "public"."habits"("domain" NULLS FIRST);
CREATE CACHED TABLE "public"."push_subscriptions"(
    "id" UUID NOT NULL,
    "auth" CHARACTER VARYING(128) NOT NULL,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "endpoint" CHARACTER VARYING(2048) NOT NULL,
    "p256dh" CHARACTER VARYING(128) NOT NULL
);
ALTER TABLE "public"."push_subscriptions" ADD CONSTRAINT "public"."CONSTRAINT_F" PRIMARY KEY("id");
CREATE CACHED TABLE "public"."tasks"(
    "id" UUID NOT NULL,
    "completed_at" TIMESTAMP(6) WITH TIME ZONE,
    "created_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "description" CHARACTER VARYING,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "due_date" TIMESTAMP(6) WITH TIME ZONE,
    "priority" ENUM('HIGH', 'LOW', 'MEDIUM', 'URGENT') NOT NULL,
    "status" ENUM('CANCELLED', 'COMPLETED', 'IN_PROGRESS', 'PENDING') NOT NULL,
    "title" CHARACTER VARYING(255) NOT NULL,
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "career_application_id" UUID,
    "course_id" UUID,
    "parent_task_id" UUID,
    "remind_at" TIMESTAMP(6) WITH TIME ZONE
);
ALTER TABLE "public"."tasks" ADD CONSTRAINT "public"."CONSTRAINT_69" PRIMARY KEY("id");
CREATE INDEX "public"."idx_tasks_domain" ON "public"."tasks"("domain" NULLS FIRST);
CREATE INDEX "public"."idx_tasks_status" ON "public"."tasks"("status" NULLS FIRST);
CREATE INDEX "public"."idx_tasks_due_date" ON "public"."tasks"("due_date" NULLS FIRST);
CREATE CACHED TABLE "public"."vector_chunks"(
    "id" UUID NOT NULL,
    "document_id" CHARACTER VARYING(255) NOT NULL,
    "domain" CHARACTER VARYING(32) NOT NULL,
    "page_number" INTEGER NOT NULL,
    "heading_path" CHARACTER VARYING(1024),
    "content" CHARACTER LARGE OBJECT NOT NULL,
    "embedding_bytes" BINARY VARYING(4096) NOT NULL,
    "created_at" TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE "public"."vector_chunks" ADD CONSTRAINT "public"."CONSTRAINT_1B" PRIMARY KEY("id");
CREATE INDEX "public"."idx_h2_vc_doc_id" ON "public"."vector_chunks"("document_id" NULLS FIRST);
CREATE INDEX "public"."idx_h2_vc_domain" ON "public"."vector_chunks"("domain" NULLS FIRST);
CREATE CACHED TABLE "public"."document_records"(
    "id" UUID NOT NULL,
    "chunk_count" INTEGER NOT NULL,
    "content_hash" CHARACTER VARYING(64) NOT NULL,
    "content_type" CHARACTER VARYING(128) NOT NULL,
    "domain" ENUM('ACADEMICS', 'CAREER', 'PERSONAL') NOT NULL,
    "file_name" CHARACTER VARYING(255) NOT NULL,
    "file_path" CHARACTER VARYING(1024) NOT NULL,
    "file_size" BIGINT NOT NULL,
    "ingested_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "page_count" INTEGER NOT NULL,
    "storage_path" CHARACTER VARYING(1024),
    "updated_at" TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    "embedding_model_id" CHARACTER VARYING(128)
);
ALTER TABLE "public"."document_records" ADD CONSTRAINT "public"."CONSTRAINT_6" PRIMARY KEY("id");
CREATE INDEX "public"."idx_doc_records_domain" ON "public"."document_records"("domain" NULLS FIRST);
CREATE INDEX "public"."idx_doc_records_hash" ON "public"."document_records"("content_hash" NULLS FIRST);
ALTER TABLE "public"."habit_logs" ADD CONSTRAINT "public"."CONSTRAINT_A" CHECK("units_completed" >= 1) NOCHECK;
ALTER TABLE "public"."assessment_categories" ADD CONSTRAINT "public"."CONSTRAINT_3" CHECK("drop_lowest_n" >= 0) NOCHECK;
ALTER TABLE "public"."habits" ADD CONSTRAINT "public"."CONSTRAINT_B6" CHECK("target_count_per_day" >= 1) NOCHECK;
ALTER TABLE "public"."push_subscriptions" ADD CONSTRAINT "public"."uk6vabkyu7g789ehysoff7cfumf" UNIQUE("endpoint");
ALTER TABLE "public"."habit_logs" ADD CONSTRAINT "public"."uq_habit_log_date" UNIQUE("habit_id", "log_date");
ALTER TABLE "public"."courses" ADD CONSTRAINT "public"."idx_courses_code" UNIQUE("code");
ALTER TABLE "public"."tasks" ADD CONSTRAINT "public"."fkmojkydmhtlogkt9oa805t2qy2" FOREIGN KEY("career_application_id") REFERENCES "public"."career_applications"("id") NOCHECK;
ALTER TABLE "public"."assessments" ADD CONSTRAINT "public"."fk4kbcb2x7nlbys293dd0vjysdm" FOREIGN KEY("category_id") REFERENCES "public"."assessment_categories"("id") NOCHECK;
ALTER TABLE "public"."tasks" ADD CONSTRAINT "public"."fk76tiq4q248au3u79a8nkexoth" FOREIGN KEY("parent_task_id") REFERENCES "public"."tasks"("id") NOCHECK;
ALTER TABLE "public"."habit_logs" ADD CONSTRAINT "public"."fksoqj2dqbm162ouaydnbwb21uj" FOREIGN KEY("habit_id") REFERENCES "public"."habits"("id") NOCHECK;
ALTER TABLE "public"."tasks" ADD CONSTRAINT "public"."fkopldg47bgaarlampi2f6wees3" FOREIGN KEY("course_id") REFERENCES "public"."courses"("id") NOCHECK;
ALTER TABLE "public"."assessment_categories" ADD CONSTRAINT "public"."fkh162imo6sl0vgqlg2fmsp0icj" FOREIGN KEY("course_id") REFERENCES "public"."courses"("id") NOCHECK;
ALTER TABLE "public"."assessments" ADD CONSTRAINT "public"."fka2nh608bmj0k0wjf0rw7oiha5" FOREIGN KEY("course_id") REFERENCES "public"."courses"("id") NOCHECK;
