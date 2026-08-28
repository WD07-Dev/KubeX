package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.ourgram.kubex.KubeXCore;

public final class KubeXDebugModeService {
    private static final String DEBUG_FILE_NAME = ".debug-mode";

    public boolean isEnabled(Path gameRoot) {
        Path debugFile = debugFile(gameRoot);
        if(!Files.exists(debugFile)) {
            return false;
        }

        try {
            return "on".equalsIgnoreCase(Files.readString(debugFile, StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return false;
        }
    }

    public boolean setEnabled(Path gameRoot, boolean enabled) throws IOException {
        Path debugFile = debugFile(gameRoot);
        Files.createDirectories(debugFile.getParent());
        Files.writeString(debugFile, enabled ? "on\n" : "off\n", StandardCharsets.UTF_8);
        return enabled;
    }

    public boolean toggle(Path gameRoot) throws IOException {
        return setEnabled(gameRoot, !isEnabled(gameRoot));
    }

    private Path debugFile(Path gameRoot) {
        return KubeXCore.paths(gameRoot).workspace().resolve(DEBUG_FILE_NAME);
    }
}