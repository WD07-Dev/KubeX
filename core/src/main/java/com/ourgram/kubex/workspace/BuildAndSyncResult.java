package com.ourgram.kubex.workspace;

import java.nio.file.Path;

public record BuildAndSyncResult(
    boolean success,
    Path workspaceRoot,
    BuildResult buildResult,
    SyncResult syncResult,
    boolean reloadAttempted,
    boolean reloadSucceeded,
    String message
) {}