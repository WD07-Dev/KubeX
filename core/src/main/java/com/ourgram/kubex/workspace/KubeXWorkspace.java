package com.ourgram.kubex.workspace;

import java.nio.file.Path;
import java.util.function.Consumer;
import com.ourgram.kubex.compiler.KubeXCompiler;

public final class KubeXWorkspace {
    private final KubeXWorkspaceInitializer initializer = new KubeXWorkspaceInitializer();
    private final KubeXWorkspaceBuildService buildService = new KubeXWorkspaceBuildService();
    private final KubeXWorkspaceSyncService syncService;

    public KubeXWorkspace(KubeXDebugModeService debugModeService) {
        syncService = new KubeXWorkspaceSyncService(new KubeXCompiler(), debugModeService);
    }

    public KubeXWorkspaceInitResult initialize(Path gameRoot, KubeXInitMode mode) {
        return initializer.initialize(gameRoot, mode);
    }

    public KubeXWorkspaceSyncResult sync(Path gameRoot) {
        return syncService.sync(gameRoot);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot, Consumer<String> progressListener, KubeXWorkspaceReloadService reloadService) {
        KubeXWorkspaceBuildResult buildResult = buildService.build(gameRoot, progressListener);
        if(!buildResult.success()) {
            return new KubeXWorkspacePipelineResult(false, buildResult.workspaceRoot(), buildResult, null, false, false, buildResult.message());
        }

        KubeXWorkspaceSyncResult syncResult = syncService.sync(gameRoot);
        if(!syncResult.success()) {
            return new KubeXWorkspacePipelineResult(false, syncResult.workspaceRoot(), buildResult, syncResult, false, false, syncResult.message());
        }

        if(reloadService != null && !syncResult.publishedFiles().isEmpty() && !reloadService.reload()) {
            return new KubeXWorkspacePipelineResult(false, syncResult.workspaceRoot(), buildResult, syncResult, true, false, "Sync completed but reload did not run");
        }

        boolean reloaded = reloadService != null && !syncResult.publishedFiles().isEmpty();
        String message = syncResult.publishedFiles().isEmpty()
        ? buildResult.message() + " | " + syncResult.message()
        : buildResult.message() + " | " + syncResult.message() + (reloaded ? " | Reload completed" : "");
        return new KubeXWorkspacePipelineResult(true, syncResult.workspaceRoot(), buildResult, syncResult, reloaded, reloaded, message);
    }
}
