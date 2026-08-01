package com.ourgram.kubex.neoforge;

import com.ourgram.kubex.sourcemap.KubeXSourceMapLookupResult;
import com.ourgram.kubex.sourcemap.KubeXSourceMapService;
import net.neoforged.fml.loading.FMLPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class KubeXRuntimeBridge {
    private static final KubeXSourceMapService SOURCE_MAP_SERVICE = new KubeXSourceMapService();

    private KubeXRuntimeBridge() {}

    public static String report(Object error, String scriptGroup, int generatedLine, int generatedColumn, String fallbackSourceFile, int fallbackSourceLine, int fallbackSourceColumn) {
        Path gameRoot = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        String detail = errorMessage(error);
        String generatedPosition = scriptGroup + "/main.js:" + generatedLine + ":" + generatedColumn;

        if(scriptGroup == null || scriptGroup.isBlank() || generatedLine < 1) {
            return "[KubeX] " + fallbackSourceFile + ":" + fallbackSourceLine + ":" + fallbackSourceColumn + "\n" + detail;
        }

        KubeXSourceMapLookupResult result = SOURCE_MAP_SERVICE.lookup(gameRoot, scriptGroup, generatedLine, Math.max(1, generatedColumn));
        if(!result.success()) {
            return "[KubeX] " + generatedPosition + "\n" + detail;
        }

        return formatMappedReport(gameRoot, generatedPosition, detail, result);
    }

    private static String formatMappedReport(Path gameRoot, String generatedPosition, String detail, KubeXSourceMapLookupResult result) {
        StringBuilder message = new StringBuilder()
        .append("[KubeX] ")
        .append(generatedPosition)
        .append(System.lineSeparator())
        .append("-> ")
        .append(result.sourcePath())
        .append(':')
        .append(result.sourceLine())
        .append(':')
        .append(result.sourceColumn())
        .append(System.lineSeparator())
        .append(detail);

        String frame = buildContextFrame(gameRoot.resolve(result.sourcePath()), result.sourceLine(), result.sourceColumn());
        if(frame != null && !frame.isBlank()) {
            message.append(System.lineSeparator()).append(System.lineSeparator()).append(frame);
        }
        return message.toString();
    }

    private static String buildContextFrame(Path sourceFile, int line, int column) {
        try {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            if(line < 1 || line > lines.size()) return null;

            int start = Math.max(1, line - 1);
            int end = Math.min(lines.size(), line + 1);
            int width = String.valueOf(end).length();
            StringBuilder frame = new StringBuilder();

            for(int current = start; current <= end; current++) {
                frame.append(String.format("%" + width + "d | %s", current, lines.get(current - 1)));
                if(current < end || current == line) {
                    frame.append(System.lineSeparator());
                }

                if(current == line) {
                    frame.append(" ".repeat(width)).append(" | ");
                    frame.append(" ".repeat(Math.max(0, column - 1))).append('^');
                    if(current < end) {
                        frame.append(System.lineSeparator());
                    }
                }
            }
            return frame.toString();
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String errorMessage(Object error) {
        if(error == null) return "Unknown error";
        return String.valueOf(error);
    }
}
