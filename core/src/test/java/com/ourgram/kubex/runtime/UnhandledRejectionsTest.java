package com.ourgram.kubex.runtime;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnhandledRejectionsTest {
    @Test
    void waitsForHandlersAndReportsOnlyOnce() {
        var tracker = new UnhandledRejections();
        var reports = new ArrayList<String>();
        var caught = tracker.track("caught");
        tracker.track("unhandled");
        tracker.tick(reports::add);
        assertTrue(reports.isEmpty());
        caught.cancel();
        tracker.tick(reports::add);
        tracker.tick(reports::add);
        assertEquals(java.util.List.of("unhandled"), reports);
    }
}