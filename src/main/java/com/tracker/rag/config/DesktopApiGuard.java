package com.tracker.rag.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A per-launch secret keeps other websites and local browsers out of the desktop API. */
public class DesktopApiGuard extends OncePerRequestFilter {
    private final byte[] token;
    private final boolean aiEnabled;
    public DesktopApiGuard(String token) {
        this(token,false);
    }
    public DesktopApiGuard(String token,boolean aiEnabled) {
        if (token == null || token.length() < 32) throw new IllegalStateException("Launch desktop mode through Folio so a private API token is supplied");
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.aiEnabled=aiEnabled;
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; base-uri 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (request.getRequestURI().startsWith("/api/")) {
            String provided = request.getHeader("X-Folio-Desktop-Token");
            if (provided == null || !MessageDigest.isEqual(token, provided.getBytes(StandardCharsets.UTF_8))) {
                response.sendError(401); return;
            }
            if (!aiEnabled && (request.getRequestURI().startsWith("/api/chat/") || request.getRequestURI().startsWith("/api/assistant/") || request.getRequestURI().equals("/api/capture/preview"))) {
                response.sendError(503, "AI is postponed in the local app. Use the record forms."); return;
            }
        }
        chain.doFilter(request, response);
    }
}
