package com.ourgram.kubex.app;

import java.nio.file.Path;
import java.util.function.Consumer;
import com.ourgram.kubex.compiler.KubeXCompiler;
import com.ourgram.kubex.workspace.KubeXDebugModeService;
import com.ourgram.kubex.workspace.KubeXInitMode;
import com.ourgram.kubex.workspace.KubeXWorkspaceBuildService;
import com.ourgram.kubex.workspace.KubeXWorkspaceInitResult;
import com.ourgram.kubex.workspace.KubeXWorkspaceInitializer;
import com.ourgram.kubex.workspace.KubeXWorkspacePipelineResult;
import com.ourgram.kubex.workspace.KubeXWorkspacePipelineService;
import com.ourgram.kubex.workspace.KubeXWorkspaceReloadService;
import com.ourgram.kubex.workspace.KubeXWorkspaceSyncResult;
import com.ourgram.kubex.workspace.KubeXWorkspaceSyncService;

public final class KubeXWorkspaceManager {
    private final KubeXWorkspaceInitializer initializer;
    private final KubeXWorkspaceSyncService syncService;
    private final KubeXWorkspacePipelineService pipelineService;

    public KubeXWorkspaceManager(KubeXDebugModeService debugModeService) {
        KubeXCompiler compiler = new KubeXCompiler();
        KubeXWorkspaceBuildService buildService = new KubeXWorkspaceBuildService();

        this.initializer = new KubeXWorkspaceInitializer();
        this.syncService = new KubeXWorkspaceSyncService(compiler, debugModeService);
        this.pipelineService = new KubeXWorkspacePipelineService(buildService, syncService);
    }

    public KubeXWorkspaceInitResult initialize(Path gameRoot, KubeXInitMode mode) {
        return initializer.initialize(gameRoot, mode);
    }

    public KubeXWorkspaceSyncResult sync(Path gameRoot) {
        return syncService.sync(gameRoot);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot) {
        return pipelineService.buildAndSync(gameRoot);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot, Consumer<String> progressListener) {
        return pipelineService.buildAndSync(gameRoot, progressListener);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot, Consumer<String> progressListener, KubeXWorkspaceReloadService reloadService) {
        return pipelineService.buildAndSync(gameRoot, progressListener, reloadService);
    }
}
