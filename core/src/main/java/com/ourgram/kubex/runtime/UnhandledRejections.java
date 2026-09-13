package com.ourgram.kubex.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class UnhandledRejections {
    private List<Pending> incoming = new ArrayList<>();
    private List<Pending> ready = new ArrayList<>();

    public synchronized Pending track(String message) {
        Pending pending = new Pending(message);
        incoming.add(pending);
        return pending;
    }

    public synchronized void tick(Consumer<String> reporter) {
        List<Pending> reports = ready;
        ready = incoming;
        incoming = new ArrayList<>();
        for(Pending pending : reports) {
            if(!pending.handled) reporter.accept(pending.message);
        }
    }

    public static final class Pending {
        private final String message;
        private volatile boolean handled;

        private Pending(String message) {
            this.message = message;
        }

        public void cancel() {
            handled = true;
        }
    }
}