package com.ourgram.kubex.workspace;

import java.nio.file.Path;
import java.util.List;

public record SyncResult(
    boolean success,
    Path workspaceRoot,
    List<Path> publishedFiles,
    int sourceMapCount,
    String message
) {}