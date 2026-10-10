package com.tracker.rag.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Release the database if the desktop host crashes. */
@Component @Profile("desktop")
public class DesktopParentGuard {
    private Thread watcher;
    @PostConstruct void watch() {
        String pid = System.getenv("FOLIO_PARENT_PID");
        if (pid == null || pid.isBlank()) return;
        long parent = Long.parseLong(pid);
        watcher = Thread.ofVirtual().name("desktop-parent").start(() -> {
            try {
                while (ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) Thread.sleep(5000);
                System.exit(0);
            } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
    }
    @PreDestroy void stop() { if (watcher != null) watcher.interrupt(); }
}
