package com.ourgram.kubex;

import com.ourgram.kubex.sourcemap.KubeXSourceMapLookupResult;
import com.ourgram.kubex.sourcemap.KubeXSourceMapService;
import com.ourgram.kubex.workspace.KubeXDebugModeService;
import com.ourgram.kubex.workspace.KubeXInitMode;
import com.ourgram.kubex.workspace.KubeXWorkspaceBuildResult;
import com.ourgram.kubex.workspace.KubeXWorkspaceBuildService;
import com.ourgram.kubex.workspace.KubeXWorkspaceInitResult;
import com.ourgram.kubex.workspace.KubeXWorkspaceInitializer;
import com.ourgram.kubex.workspace.KubeXWorkspaceSyncResult;
import com.ourgram.kubex.workspace.KubeXWorkspaceSyncService;
import com.ourgram.kubex.compiler.KubeXCompiler;
import java.util.function.Consumer;

public final class KubeXCore {
    private final KubeXWorkspaceInitializer workspaceInitializer;
    private final KubeXCompiler compiler;
    private final KubeXWorkspaceSyncService workspaceSyncService;
    private final KubeXWorkspaceBuildService workspaceBuildService;
    private final KubeXSourceMapService sourceMapService;
    private final KubeXDebugModeService debugModeService;

    public KubeXCore() {
        this.workspaceInitializer = new KubeXWorkspaceInitializer();
        this.compiler = new KubeXCompiler();
        this.debugModeService = new KubeXDebugModeService();
        this.workspaceSyncService = new KubeXWorkspaceSyncService(compiler, debugModeService);
        this.workspaceBuildService = new KubeXWorkspaceBuildService();
        this.sourceMapService = new KubeXSourceMapService();
    }

    public ProjectSnapshot snapshot() {
        return new ProjectSnapshot(
            "KubeX",
            "workspace",
            "expandable",
            "workspace-init",
            "workspace-sync",
            "workspace-init",
            "single-mod"
        );
    }

    public KubeXWorkspaceInitResult initializeWorkspace(java.nio.file.Path gameRoot, KubeXInitMode mode) {
        return workspaceInitializer.initialize(gameRoot, mode);
    }

    public KubeXWorkspaceBuildResult buildWorkspace(java.nio.file.Path gameRoot) {
        return workspaceBuildService.build(gameRoot);
    }

    public KubeXWorkspaceBuildResult buildWorkspace(java.nio.file.Path gameRoot, Consumer<String> progressListener) {
        return workspaceBuildService.build(gameRoot, progressListener);
    }

    public KubeXWorkspaceSyncResult syncWorkspace(java.nio.file.Path gameRoot) {
        return workspaceSyncService.sync(gameRoot);
    }

    public boolean isDebugModeEnabled(java.nio.file.Path gameRoot) {
        return debugModeService.isEnabled(gameRoot);
    }

    public boolean setDebugMode(java.nio.file.Path gameRoot, boolean enabled) throws java.io.IOException {
        return debugModeService.setEnabled(gameRoot, enabled);
    }

    public boolean toggleDebugMode(java.nio.file.Path gameRoot) throws java.io.IOException {
        return debugModeService.toggle(gameRoot);
    }

    public KubeXSourceMapLookupResult doctorSourceMap(java.nio.file.Path gameRoot, String scriptGroup, int generatedLine, int generatedColumn) {
        return sourceMapService.lookup(gameRoot, scriptGroup, generatedLine, generatedColumn);
    }

    public KubeXSourceMapLookupResult doctorSourceMapAuto(java.nio.file.Path gameRoot, String positionHint, int generatedLine, int generatedColumn) {
        return sourceMapService.lookupAny(gameRoot, positionHint, generatedLine, generatedColumn);
    }
}
