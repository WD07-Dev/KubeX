package com.ourgram.kubex.workspace;

import java.nio.file.Path;

public record InitResult(
    boolean success,
    Path workspaceRoot,
    InitMode mode,
    String message
) {}