package com.tracker.rag.config;

import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.PgVectorStore;
import com.tracker.rag.repository.vector.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * Auto-configuration and dynamic factory for dual-mode vector storage.
 * Seamlessly selects PgVectorStore when PostgreSQL is running with pgvector,
 * and falls back to EmbeddedVectorStore for H2 / offline Docker environments.
 */
@Configuration
public class VectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreConfig.class);

    @Value("${tracker.vector-store.mode:auto}")
    private String mode;

    @Value("${spring.datasource.host:localhost}")
    private String dbHost;

    @Value("${spring.datasource.port:5432}")
    private int dbPort;

    @Bean
    @Primary
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        String selectedMode = (mode != null) ? mode.trim().toLowerCase() : "auto";

        log.info("[VectorStoreConfig] Configuring VectorStore. Mode preference: '{}'", selectedMode);

        if ("embedded".equals(selectedMode)) {
            log.info("[VectorStoreConfig] Explicitly configured for EmbeddedVectorStore.");
            return new EmbeddedVectorStore(jdbcTemplate);
        }

        if ("postgres".equals(selectedMode)) {
            log.info("[VectorStoreConfig] Explicitly configured for PgVectorStore.");
            return new PgVectorStore(jdbcTemplate);
        }

        // Auto-detection logic
        log.info("[VectorStoreConfig] AUTO mode active. Inspecting database connection...");
        if (isPostgreSqlWithPgVector(dataSource)) {
            try {
                log.info("[VectorStoreConfig] PostgreSQL with pgvector confirmed. Activating PgVectorStore.");
                return new PgVectorStore(jdbcTemplate);
            } catch (Exception e) {
                log.warn("[VectorStoreConfig] Failed to initialize PgVectorStore: {}. Falling back to EmbeddedVectorStore.", e.getMessage());
            }
        }

        log.warn("[VectorStoreConfig] PostgreSQL/pgvector unavailable or inactive. Activating EmbeddedVectorStore fallback.");
        return new EmbeddedVectorStore(jdbcTemplate);
    }

    private boolean isPostgreSqlWithPgVector(DataSource dataSource) {
        if (!isSocketReachable(dbHost, dbPort, 500)) {
            log.debug("[VectorStoreConfig] Port {}:{} unreachable via socket probe.", dbHost, dbPort);
            return false;
        }

        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            String dbProduct = metaData.getDatabaseProductName();
            if (dbProduct == null || !dbProduct.toLowerCase().contains("postgresql")) {
                log.debug("[VectorStoreConfig] Connected database is '{}', not PostgreSQL.", dbProduct);
                return false;
            }

            try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT count(*) FROM pg_available_extensions WHERE name = 'vector'"
            )) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.debug("[VectorStoreConfig] PostgreSQL check failed: {}", e.getMessage());
        }
        return false;
    }

    public static boolean isSocketReachable(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
