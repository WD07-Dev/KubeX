package com.ourgram.kubex;

import java.nio.file.Path;

public final class KubeXCore {
    public static final String MOD_ID = "kubex";

    private KubeXCore() {}

    public static KubeXPaths paths(Path gameDirectory) {
        return KubeXPaths.fromGameDirectory(gameDirectory);
    }
}