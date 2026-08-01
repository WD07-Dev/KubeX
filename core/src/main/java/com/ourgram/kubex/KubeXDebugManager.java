package com.ourgram.kubex;

import java.io.IOException;
import java.nio.file.Path;
import com.ourgram.kubex.workspace.KubeXDebugModeService;

public final class KubeXDebugManager {
    private final KubeXDebugModeService debugModeService;

    public KubeXDebugManager(KubeXDebugModeService debugModeService) {
        this.debugModeService = debugModeService;
    }

    public boolean isEnabled(Path gameRoot) {
        return debugModeService.isEnabled(gameRoot);
    }

    public boolean setEnabled(Path gameRoot, boolean enabled) throws IOException {
        return debugModeService.setEnabled(gameRoot, enabled);
    }

    public boolean toggle(Path gameRoot) throws IOException {
        return debugModeService.toggle(gameRoot);
    }
}
