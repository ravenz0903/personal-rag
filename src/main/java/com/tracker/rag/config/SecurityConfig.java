package com.tracker.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.util.*;

/** A single-owner workspace. Other valid Supabase users cannot read this database. */
@Configuration
public class SecurityConfig {
    @Bean SecurityFilterChain security(HttpSecurity http, Environment environment,
            @Value("${tracker.auth.enabled:true}") boolean enabled,
            @Value("${supabase.url:}") String url,
            @Value("${tracker.auth.owner-id:}") String ownerId) throws Exception {
        boolean cloud = Arrays.asList(environment.getActiveProfiles()).contains("cloud");
        if (cloud && !enabled) throw new IllegalStateException("Cloud deployment requires authentication");
        http.cors(cors -> {}).csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (Arrays.asList(environment.getActiveProfiles()).contains("desktop")) {
            http.addFilterBefore(new DesktopApiGuard(environment.getProperty("tracker.desktop.token", ""),Boolean.parseBoolean(environment.getProperty("ai.enabled","false"))),
                    org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        }
        if (!enabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        } else {
            if (!url.startsWith("https://") || ownerId.isBlank())
                throw new IllegalStateException("Set SUPABASE_URL and TRACKER_OWNER_ID before starting the authenticated backend");
            UUID.fromString(ownerId);
            String issuer = url.replaceAll("/$", "") + "/auth/v1";
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                    .jwsAlgorithm(SignatureAlgorithm.ES256).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(ownerValidator(issuer, ownerId));
            http.authorizeHttpRequests(auth -> auth.requestMatchers("/api/config", "/api/health", "/", "/index.html", "/assets/**", "/favicon.ico", "/error").permitAll()
                    .anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
        }
        return http.build();
    }
    static OAuth2TokenValidator<Jwt> ownerValidator(String issuer, String ownerId) {
            OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains("authenticated")
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid audience", null));
            OAuth2TokenValidator<Jwt> owner = token -> ownerId.equals(token.getSubject())
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "This workspace belongs to a different user", null));
            return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience, owner);
    }
    @Bean CorsConfigurationSource cors(@Value("${tracker.cors.origins:http://localhost:5173}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
