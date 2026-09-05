package com.ourgram.kubex;

import java.nio.file.Path;

public final class KubeXCore {
    public static final String MOD_ID = "kubex";

    private KubeXCore() {}

    public static Paths paths(Path gameDirectory) {
        return Paths.fromGameDirectory(gameDirectory);
    }
}