package com.tracker.rag.service.desktop;

import com.tracker.rag.service.ai.AiServiceException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit cancellation of backend inference; a disconnected browser alone is insufficient. */
@Service @Profile("desktop")
public class LocalAiJobs {
    private final Map<UUID, Scope> running=new ConcurrentHashMap<>();
    private final com.github.benmanes.caffeine.cache.Cache<UUID,Boolean> cancelled=com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(100).expireAfterWrite(java.time.Duration.ofMinutes(5)).build();
    public synchronized Scope begin(UUID id) {
        if(running.size()>=5)throw new AiServiceException(429,"Local AI is busy. Try again shortly.");
        var scope=new Scope(id==null?UUID.randomUUID():id,Thread.currentThread());
        if(cancelled.getIfPresent(scope.id)!=null)throw new AiServiceException(408,"Local AI request cancelled.");
        if(running.putIfAbsent(scope.id,scope)!=null)throw new AiServiceException(409,"This AI request is already running.");
        return scope;
    }
    public synchronized void cancel(UUID id) {cancelled.put(id,true);var scope=running.get(id);if(scope!=null)scope.cancel();}
    public final class Scope implements AutoCloseable {
        private final UUID id;private final Thread thread;private boolean active=true,cancelled;
        private Scope(UUID id,Thread thread){this.id=id;this.thread=thread;}
        public synchronized void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new AiServiceException(408,"Local AI request cancelled.");}
        private synchronized void cancel(){if(active){cancelled=true;thread.interrupt();}}
        @Override public synchronized void close(){active=false;running.remove(id,this);if(cancelled)Thread.interrupted();}
    }
}
