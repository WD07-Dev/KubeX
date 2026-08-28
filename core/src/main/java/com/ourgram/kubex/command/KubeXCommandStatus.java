package com.ourgram.kubex.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class KubeXCommandStatus {
    private static final String STATUS_FILE = ".kubex-status.json";

    public enum State {
        IDLE,
        RUNNING,
        COMPLETE,
        FAILED
    }

    public record Snapshot(State state, String message, long startedAt, long updatedAt) {}

    private State state = State.IDLE;
    private String message = "";
    private long startedAt;
    private long updatedAt;
    private Path statusFile;

    public synchronized void start(Path gameRoot, String message) {
        statusFile = gameRoot.resolve("kubex").resolve(STATUS_FILE);
        startedAt = System.currentTimeMillis();
        update(State.RUNNING, message);
    }

    public synchronized void progress(String message) {
        update(State.RUNNING, message);
    }

    public synchronized void complete(String message) {
        update(State.COMPLETE, message);
    }

    public synchronized void fail(String message) {
        update(State.FAILED, message);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(state, message, startedAt, updatedAt);
    }

    private void update(State state, String message) {
        this.state = state;
        this.message = message == null ? "" : message;
        updatedAt = System.currentTimeMillis();
        writeStatusFile();
    }

    private void writeStatusFile() {
        if(statusFile == null) return;

        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        String payload = "{\"state\":\"" + state.name().toLowerCase() + "\",\"message\":\"" + escaped
        + "\",\"startedAt\":" + startedAt + ",\"updatedAt\":" + updatedAt + "}";
        try {
            Files.createDirectories(statusFile.getParent());
            Files.writeString(statusFile, payload, StandardCharsets.UTF_8);
        } catch(IOException ignored) {
            // Build progress must not fail only because its optional status file cannot be written.
        }
    }
}