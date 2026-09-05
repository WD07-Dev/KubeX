package com.ourgram.kubex.compiler;

public record CompileOptions(
    boolean debugEnabled,
    String sourceMapContent
) {
    public static final CompileOptions DEFAULT = new CompileOptions(false, null);
}