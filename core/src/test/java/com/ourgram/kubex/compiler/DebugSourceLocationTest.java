package com.ourgram.kubex.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DebugSourceLocationTest {
    @Test
    void mapsCallbacksAfterRuntimeInsertionToSrc() {
        String source = """
        Promise.resolve(1);
        register(function () {
            missing();
        });
        """;
        String map = """
        {"version":3,"sources":["../src/server_scripts/main.ts"],"mappings":"AAAA;AACA;AACA;AACA"}
        """;
        String output = new ScriptCompiler().compile("server_scripts.js", source, new CompileOptions(true, map)).outputSource();
        assertTrue(output.contains("file: \"kubex/src/server_scripts/main.ts\", line: 3,"));
    }
}