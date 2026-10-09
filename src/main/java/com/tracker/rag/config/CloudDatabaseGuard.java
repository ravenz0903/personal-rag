package com.tracker.rag.config;

import com.tracker.rag.repository.vector.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.annotation.PostConstruct;

/** Supabase's browser roles must never bypass the backend's single-owner authorization. */
@Component
@Profile("cloud")
@org.springframework.context.annotation.DependsOn("entityManagerFactory")
public class CloudDatabaseGuard {
    private final JdbcTemplate jdbc;
    public CloudDatabaseGuard(JdbcTemplate jdbc, VectorStore initializedVectorStore) { this.jdbc = jdbc; }
    @PostConstruct void protectTables() {
        for (String table : new String[]{"tasks", "habits", "habit_logs", "courses", "assessment_categories",
                "assessments", "career_applications", "document_records", "event_log", "vector_chunks", "push_subscriptions"}) {
            jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            jdbc.execute("REVOKE ALL ON TABLE " + table + " FROM anon, authenticated");
        }
    }
}
