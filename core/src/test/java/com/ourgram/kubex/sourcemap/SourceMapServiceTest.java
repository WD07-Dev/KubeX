package com.ourgram.kubex.sourcemap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceMapServiceTest {
    private final SourceMapService service = new SourceMapService();

    @TempDir
    Path tempDir;

    @Test
    void resolvesGeneratedPositionBackToWorkspaceSource() throws IOException {
        Path sourceFile = tempDir.resolve("kubex/src/server_scripts/main.ts");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join("\n",
            "const ignored = 0;",
            "const alsoIgnored = 1;",
            "function shinyStar() {",
            "  return true;",
            "}"
        ), StandardCharsets.UTF_8);

        writeSourceMap(
            tempDir.resolve("kubejs/server_scripts/main.js.map"),
            List.of("../src/server_scripts/main.ts"),
            encodeMappings(List.of(
                List.of(segment(0, 0, 2, 0))
            ))
        );

        SourceMapLookupResult result = service.lookup(tempDir, "server_scripts", 1, 1);

        assertTrue(result.success());
        assertEquals("kubex/src/server_scripts/main.ts", result.sourcePath());
        assertEquals(3, result.sourceLine());
        assertEquals(1, result.sourceColumn());
        assertEquals("function shinyStar() {", result.sourceSnippet());
        assertNotNull(result.sourceMapPath());
    }

    @Test
    void picksTheBestMappingForTheRequestedColumn() throws IOException {
        Path sourceFile = tempDir.resolve("kubex/src/client_scripts/main.ts");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, String.join("\n",
            "const alpha = 1;",
            "const beta = 2;",
            "const gamma = 3;"
        ), StandardCharsets.UTF_8);

        writeSourceMap(
            tempDir.resolve("kubejs/client_scripts/main.js.map"),
            List.of("../src/client_scripts/main.ts"),
            encodeMappings(List.of(
                List.of(
                    segment(0, 0, 0, 0),
                    segment(8, 0, 1, 6)
                )
            ))
        );

        SourceMapLookupResult result = service.lookup(tempDir, "client_scripts", 1, 10);

        assertTrue(result.success());
        assertEquals(2, result.sourceLine());
        assertEquals(7, result.sourceColumn());
        assertEquals("const beta = 2;", result.sourceSnippet());
    }

    @Test
    void lookupAnyUsesTheHintedScriptGroupFirst() throws IOException {
        Path sourceFile = tempDir.resolve("kubex/src/startup_scripts/main.ts");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, "StartupEvents.registry('item', event => {});\n", StandardCharsets.UTF_8);

        writeSourceMap(
            tempDir.resolve("kubejs/startup_scripts/main.js.map"),
            List.of("../src/startup_scripts/main.ts"),
            encodeMappings(List.of(
                List.of(segment(0, 0, 0, 0))
            ))
        );

        SourceMapLookupResult result = service.lookupAny(tempDir, "main.js:1:1 startup_scripts", 1, 1);

        assertTrue(result.success());
        assertEquals("startup_scripts", result.scriptGroup());
        assertEquals("kubex/src/startup_scripts/main.ts", result.sourcePath());
    }

    @Test
    void reportsMissingSourceMapFilesClearly() {
        SourceMapLookupResult result = service.lookup(tempDir, "server_scripts", 10, 1);

        assertFalse(result.success());
        assertTrue(result.message().contains("not found"));
    }

    private void writeSourceMap(Path sourceMapPath, List<String> sources, String mappings) throws IOException {
        Files.createDirectories(sourceMapPath.getParent());
        Files.writeString(
            sourceMapPath,
            "{\n" +
                "  \"version\": 3,\n" +
                "  \"sources\": [\"" + String.join("\", \"", sources) + "\"],\n" +
                "  \"mappings\": \"" + mappings + "\"\n" +
                "}\n",
            StandardCharsets.UTF_8
        );
    }

    private String encodeMappings(List<List<int[]>> lines) {
        StringBuilder builder = new StringBuilder();
        int previousSourceIndex = 0;
        int previousSourceLine = 0;
        int previousSourceColumn = 0;

        for(int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
            if(lineIndex > 0) {
                builder.append(';');
            }

            int previousGeneratedColumn = 0;
            List<int[]> segments = lines.get(lineIndex);
            for(int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
                if(segmentIndex > 0) {
                    builder.append(',');
                }

                int[] segment = segments.get(segmentIndex);
                builder.append(encodeVlq(segment[0] - previousGeneratedColumn));
                previousGeneratedColumn = segment[0];
                builder.append(encodeVlq(segment[1] - previousSourceIndex));
                previousSourceIndex = segment[1];
                builder.append(encodeVlq(segment[2] - previousSourceLine));
                previousSourceLine = segment[2];
                builder.append(encodeVlq(segment[3] - previousSourceColumn));
                previousSourceColumn = segment[3];
            }
        }
        return builder.toString();
    }

    private int[] segment(int generatedColumn, int sourceIndex, int sourceLine, int sourceColumn) {
        return new int[] { generatedColumn, sourceIndex, sourceLine, sourceColumn };
    }

    private String encodeVlq(int value) {
        final String base64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int signed = value < 0 ? ((-value) << 1) + 1 : value << 1;
        StringBuilder builder = new StringBuilder();

        do {
            int digit = signed & 31;
            signed >>>= 5;
            if(signed > 0) {
                digit |= 32;
            }
            builder.append(base64.charAt(digit));
        } while(signed > 0);

        return builder.toString();
    }
}