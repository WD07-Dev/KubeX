package com.ourgram.kubex.workspace;

import java.nio.file.Path;

public record KubeXWorkspacePipelineResult(
    boolean success,
    Path workspaceRoot,
    KubeXWorkspaceBuildResult buildResult,
    KubeXWorkspaceSyncResult syncResult,
    boolean reloadAttempted,
    boolean reloadSucceeded,
    String message
) {
}
