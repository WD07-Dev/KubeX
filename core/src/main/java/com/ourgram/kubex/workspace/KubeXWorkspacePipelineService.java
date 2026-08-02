package com.ourgram.kubex.workspace;

import java.nio.file.Path;
import java.util.function.Consumer;

public final class KubeXWorkspacePipelineService {
    private final KubeXWorkspaceBuildService buildService;
    private final KubeXWorkspaceSyncService syncService;

    public KubeXWorkspacePipelineService(KubeXWorkspaceBuildService buildService, KubeXWorkspaceSyncService syncService) {
        this.buildService = buildService;
        this.syncService = syncService;
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot) {
        return buildAndSync(gameRoot, ignored -> {}, null);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot, Consumer<String> progressListener) {
        return buildAndSync(gameRoot, progressListener, null);
    }

    public KubeXWorkspacePipelineResult buildAndSync(Path gameRoot, Consumer<String> progressListener, KubeXWorkspaceReloadService reloadService) {
        KubeXWorkspaceBuildResult buildResult = buildService.build(gameRoot, progressListener);
        Path workspaceRoot = buildResult.workspaceRoot();
        if(!buildResult.success()) {
            return new KubeXWorkspacePipelineResult(
                false,
                workspaceRoot,
                buildResult,
                null,
                false,
                false,
                buildResult.message()
            );
        }

        KubeXWorkspaceSyncResult syncResult = syncService.sync(gameRoot);
        if(!syncResult.success()) {
            return new KubeXWorkspacePipelineResult(
                false,
                syncResult.workspaceRoot(),
                buildResult,
                syncResult,
                false,
                false,
                syncResult.message()
            );
        }

        boolean reloadAttempted = false;
        boolean reloadSucceeded = false;
        if(reloadService != null && !syncResult.publishedFiles().isEmpty()) {
            reloadAttempted = true;
            reloadSucceeded = reloadService.reload();
            if(!reloadSucceeded) {
                return new KubeXWorkspacePipelineResult(
                    false,
                    syncResult.workspaceRoot(),
                    buildResult,
                    syncResult,
                    true,
                    false,
                    "Sync completed but reload did not run"
                );
            }
        }

        String message = syncResult.publishedFiles().isEmpty()
        ? buildResult.message() + " | " + syncResult.message()
        : buildResult.message() + " | " + syncResult.message() + (reloadAttempted ? " | Reload completed" : "");

        return new KubeXWorkspacePipelineResult(
            true,
            syncResult.workspaceRoot(),
            buildResult,
            syncResult,
            reloadAttempted,
            reloadSucceeded,
            message
        );
    }
}
