package com.tracker.rag.config;

import com.tracker.rag.repository.vector.EmbeddedVectorStore;
import com.tracker.rag.repository.vector.VectorStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class VectorStoreConfigTest {

    @Test
    @DisplayName("VectorStoreConfig: Embedded mode explicitly returns EmbeddedVectorStore")
    void testExplicitEmbeddedMode() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "mode", "embedded");

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;MODE=PostgreSQL");
        VectorStore store = config.vectorStore(ds, null);

        assertThat(store).isInstanceOf(EmbeddedVectorStore.class);
    }

    @Test
    @DisplayName("VectorStoreConfig: Auto mode falls back to EmbeddedVectorStore when host/port unreachable")
    void testAutoModeFallbackWhenOffline() {
        VectorStoreConfig config = new VectorStoreConfig();
        ReflectionTestUtils.setField(config, "mode", "auto");
        ReflectionTestUtils.setField(config, "dbHost", "127.0.0.1");
        ReflectionTestUtils.setField(config, "dbPort", 59999); // Unused port

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:testdb;MODE=PostgreSQL");
        VectorStore store = config.vectorStore(ds, null);

        assertThat(store).isInstanceOf(EmbeddedVectorStore.class);
    }
}
