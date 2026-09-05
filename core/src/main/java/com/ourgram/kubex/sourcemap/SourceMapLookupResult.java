package com.ourgram.kubex.sourcemap;

import java.nio.file.Path;

public record SourceMapLookupResult(
    boolean success,
    String message,
    String scriptGroup,
    Path sourceMapPath,
    String sourcePath,
    int sourceLine,
    int sourceColumn,
    String sourceSnippet
) {}