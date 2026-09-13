package com.ourgram.kubex.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ErrorNotificationTest {
    @Test
    void usesMappedLocationWithoutCodeContext() {
        String message = new ErrorNotification().next("""
        [KubeX] Unhandled Promise rejection
        [KubeX] server_scripts/main.js:412:1
        -> kubex/src/server_scripts/main.ts:37:5
        ReferenceError: missing

        37 | secretCode();
           | ^
        """, 0);
        assertTrue(message.contains("main.ts:37:5"));
        assertTrue(message.contains("ReferenceError: missing"));
        assertFalse(message.contains("secretCode"));
        assertFalse(message.contains("main.js"));
    }

    @Test
    void limitsAllChatAlertsWithoutChangingTheReport() {
        var notifications = new ErrorNotification();
        String report = "[KubeX] Unhandled Promise rejection\n[KubeX] server_scripts/main.js:4:1\nError: failed";
        assertNotNull(notifications.next(report, 0));
        assertNull(notifications.next(report, 1));
        assertNull(notifications.next(report + " again", 4_999_999_999L));
        assertNotNull(notifications.next(report, 5_000_000_000L));
    }
}