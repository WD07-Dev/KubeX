package com.ourgram.kubex.compiler;

public record CompileResult(
    String fileName,
    String inputSource,
    String outputSource,
    int[] generatedToOriginalLineMap
) {
}
