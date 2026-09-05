package com.ourgram.kubex.workspace;

public enum InitMode {
    JS("js", "main.js"),
    TS("ts", "main.ts");

    private final String id;
    private final String entryFileName;

    InitMode(String id, String entryFileName) {
        this.id = id;
        this.entryFileName = entryFileName;
    }

    public String id() {
        return id;
    }

    public String entryFileName() {
        return entryFileName;
    }
}