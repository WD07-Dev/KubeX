package com.ourgram.kubex.workspace;

import java.nio.file.Path;

public record BuildResult(
    boolean success,
    Path workspaceRoot,
    String message
) {}