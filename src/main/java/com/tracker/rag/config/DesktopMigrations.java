package com.tracker.rag.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;

@Configuration @Profile("desktop")
public class DesktopMigrations {
    @Bean FlywayMigrationStrategy safeMigration(Environment environment) {
        return flyway -> {
            if (flyway.info().pending().length > 0) {
                String url = environment.getRequiredProperty("spring.datasource.url");
                if (url.startsWith("jdbc:h2:file:")) {
                    Path file = Path.of(url.substring("jdbc:h2:file:".length()).split(";")[0] + ".mv.db").toAbsolutePath();
                    if (Files.exists(file)) {
                        try {
                            Path backups = file.getParent().resolve("migration-backups"); Files.createDirectories(backups);
                            Path backup = backups.resolve("before-migration-" + Instant.now().toEpochMilli() + ".zip");
                            try (var connection = DriverManager.getConnection(url, environment.getProperty("spring.datasource.username", "sa"), environment.getProperty("spring.datasource.password", "")); var statement = connection.createStatement()) {
                                statement.execute("BACKUP TO '" + backup.toString().replace("'", "''") + "'");
                            }
                        } catch (Exception e) { throw new IllegalStateException("Migration stopped: could not back up the existing database. Your previous workspace has not been migrated.", e); }
                    }
                }
            }
            flyway.migrate();
        };
    }
}
