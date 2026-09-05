package com.ourgram.kubex;

import java.nio.file.Path;

public record Paths(Path gameDirectory, Path workspace, Path kubejs) {
    public static Paths fromGameDirectory(Path gameDirectory) {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        return new Paths(
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