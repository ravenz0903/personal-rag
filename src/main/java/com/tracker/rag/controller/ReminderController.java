package com.tracker.rag.controller;

import com.tracker.rag.model.PushSubscription;
import com.tracker.rag.repository.PushSubscriptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {
    private final PushSubscriptionRepository subscriptions;
    @Value("${reminders.vapid-public-key:}") private String publicKey;
    @Value("${reminders.enabled:false}") private boolean enabled;
    public ReminderController(PushSubscriptionRepository subscriptions) { this.subscriptions = subscriptions; }
    @GetMapping("/config") public Map<String, Object> config() { return Map.of("enabled", enabled && !publicKey.isBlank(), "publicKey", publicKey); }
    @PostMapping("/subscribe") @Transactional
    public Map<String, String> subscribe(@RequestBody Subscription request) {
        if (!enabled || publicKey.isBlank()) throw new IllegalArgumentException("Online reminders have not been configured yet");
        validate(request);
        PushSubscription row = subscriptions.findByEndpoint(request.endpoint()).orElseGet(PushSubscription::new);
        row.endpoint = request.endpoint(); row.p256dh = request.keys().get("p256dh"); row.auth = request.keys().get("auth");
        if (row.id == null && subscriptions.count() >= 10) throw new IllegalArgumentException("Remove an old device before registering another");
        subscriptions.save(row);
        return Map.of("status", "SUBSCRIBED");
    }
    @PostMapping("/unsubscribe") @Transactional
    public Map<String, String> unsubscribe(@RequestBody Map<String, String> request) {
        subscriptions.findByEndpoint(Objects.toString(request.get("endpoint"), "")).ifPresent(subscriptions::delete);
        return Map.of("status", "UNSUBSCRIBED");
    }
    public static void validate(Subscription request) {
        if (request.endpoint() == null || request.endpoint().length() > 2048 || request.keys() == null) throw new IllegalArgumentException("Invalid push subscription");
        URI uri = URI.create(request.endpoint());
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        boolean trusted = host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com") ||
            host.endsWith(".notify.windows.com") || host.equals("web.push.apple.com");
        if (!trusted || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null) throw new IllegalArgumentException("Unsupported browser push service");
        for (String key : List.of("p256dh", "auth")) {
            String value = request.keys().get(key);
            try {
                byte[] bytes = Base64.getUrlDecoder().decode(Objects.toString(value, ""));
                if (key.equals("p256dh") ? bytes.length != 65 || bytes[0] != 4 : bytes.length != 16) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid push encryption keys"); }
        }
    }
    public record Subscription(String endpoint, Map<String, String> keys) {}
}
