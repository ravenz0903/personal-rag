package com.tracker.rag.controller;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReminderSubscriptionTest {
    private Map<String, String> keys() {
        byte[] publicKey = new byte[65]; publicKey[0] = 4;
        return Map.of("p256dh", Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey), "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]));
    }
    @Test void rejectsArbitraryEndpointsAndMalformedKeys() {
        for (String endpoint : List.of("http://fcm.googleapis.com/send/test", "https://127.0.0.1/private", "https://fcm.googleapis.com.evil.test/send", "https://user@fcm.googleapis.com/send", "https://fcm.googleapis.com:8443/send"))
            assertThrows(IllegalArgumentException.class, () -> ReminderController.validate(new ReminderController.Subscription(endpoint, keys())));
        assertThrows(IllegalArgumentException.class, () -> ReminderController.validate(new ReminderController.Subscription("https://fcm.googleapis.com/send/test", Map.of("auth", "bad", "p256dh", "bad"))));
    }
    @Test void acceptsSupportedPushServices() {
        for (String endpoint : List.of("https://fcm.googleapis.com/send/test", "https://updates.push.services.mozilla.com/wpush/v2/test", "https://web.push.apple.com/test", "https://wns.notify.windows.com/test"))
            assertDoesNotThrow(() -> ReminderController.validate(new ReminderController.Subscription(endpoint, keys())));
    }
}
