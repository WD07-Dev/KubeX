package com.ourgram.kubex;

import java.nio.file.Path;

public record KubeXPaths(Path gameDirectory, Path workspace, Path kubejs) {
    public static KubeXPaths fromGameDirectory(Path gameDirectory) {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        return new KubeXPaths(
            normalized,
            normalized.resolve(KubeXCore.MOD_ID),
            normalized.resolve("kubejs")
        );
    }

    public Path output() {
        return workspace.resolve("output");
    }

    public Path source() {
        return workspace.resolve("src");
    }
}