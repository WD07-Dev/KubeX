package com.ourgram.kubex.sourcemap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.ourgram.kubex.KubeXCore;

public final class KubeXSourceMapService {
    private record SourceMapEntry(int sourceIndex, int sourceLine, int sourceColumn) {}
    private record GeneratedPosition(int line, int column) {}
    private static final List<String> SCRIPT_GROUPS = List.of("client_scripts", "server_scripts", "startup_scripts");
    private static final String KUBEX_LINE_MAP_SUFFIX = ".kubex-lines";

    public record ResolvedSourcePosition(String sourcePath, int sourceLine, int sourceColumn) {}

    public KubeXSourceMapLookupResult lookup(Path gameRoot, String scriptGroup, int generatedLine, int generatedColumn) {
        var paths = KubeXCore.paths(gameRoot);
        Path normalizedGameRoot = paths.gameDirectory();
        Path generatedScriptPath = paths.kubejs().resolve(scriptGroup).resolve("main.js");
        Path sourceMapPath = paths.kubejs().resolve(scriptGroup).resolve("main.js.map");
        if(!Files.exists(sourceMapPath)) {
            return failure(scriptGroup, sourceMapPath, "Source map file was not found");
        }

        try {
            GeneratedPosition adjustedPosition = adjustGeneratedPosition(generatedScriptPath, generatedLine, generatedColumn);
            SourceMapDocument document = SourceMapDocument.parse(sourceMapPath);
            SourceMapEntry entry = document.lookupClosest(adjustedPosition.line(), adjustedPosition.column());
            if(entry == null) {
                return failure(scriptGroup, sourceMapPath, "No source mapping was found for " + generatedLine + ":" + generatedColumn);
            }

            Path sourceFile = resolveSourcePath(normalizedGameRoot, sourceMapPath, document, entry.sourceIndex());
            String sourcePath = formatSourcePath(normalizedGameRoot, sourceFile, document.sources().get(entry.sourceIndex()));
            String snippet = sourceFile == null ? null : readSnippet(sourceFile, entry.sourceLine());

            return new KubeXSourceMapLookupResult(
                true,
                "OK",
                scriptGroup,
                sourceMapPath,
                sourcePath,
                entry.sourceLine(),
                entry.sourceColumn(),
                snippet
            );
        } catch (Exception exception) {
            return failure(scriptGroup, sourceMapPath, failureMessage(exception));
        }
    }

    public KubeXSourceMapLookupResult lookupAny(Path gameRoot, String positionHint, int generatedLine, int generatedColumn) {
        List<String> orderedGroups = orderedGroups(positionHint);
        KubeXSourceMapLookupResult firstFailure = null;

        for(String scriptGroup : orderedGroups) {
            KubeXSourceMapLookupResult result = lookup(gameRoot, scriptGroup, generatedLine, generatedColumn);
            if(result.success()) return result;

            if(firstFailure == null) {
                firstFailure = result;
            }
        }

        return firstFailure == null
        ? new KubeXSourceMapLookupResult(false, "No script groups are configured", null, null, null, 0, 0, null)
        : firstFailure;
    }

    public static ResolvedSourcePosition lookupSource(String sourceMapContent, int generatedLine, int generatedColumn) throws IOException {
        SourceMapDocument document = SourceMapDocument.parse(sourceMapContent);
        SourceMapEntry entry = document.lookupClosest(generatedLine, generatedColumn);
        if(entry == null) {
            return null;
        }
        return new ResolvedSourcePosition(document.sources().get(entry.sourceIndex()), entry.sourceLine(), entry.sourceColumn());
    }

    private GeneratedPosition adjustGeneratedPosition(Path generatedScriptPath, int generatedLine, int generatedColumn) {
        if(generatedLine < 1) {
            return new GeneratedPosition(generatedLine, generatedColumn);
        }

        Path lineMapPath = generatedScriptPath.resolveSibling(generatedScriptPath.getFileName() + KUBEX_LINE_MAP_SUFFIX);
        List<Integer> lineMap = readLineMap(lineMapPath);
        if(lineMap.isEmpty() || generatedLine > lineMap.size()) {
            return new GeneratedPosition(generatedLine, generatedColumn);
        }

        return new GeneratedPosition(Math.max(1, lineMap.get(generatedLine - 1)), generatedColumn);
    }

    private List<Integer> readLineMap(Path lineMapPath) {
        if(!Files.exists(lineMapPath)) {
            return Collections.emptyList();
        }

        try {
            List<String> lines = Files.readAllLines(lineMapPath, StandardCharsets.UTF_8);
            List<Integer> lineMap = new ArrayList<>(lines.size());
            for(String line : lines) {
                if(line == null || line.isBlank()) continue;
                lineMap.add(Integer.parseInt(line.trim()));
            }
            return lineMap;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<String> orderedGroups(String positionHint) {
        if(positionHint == null || positionHint.isBlank()) {
            return SCRIPT_GROUPS;
        }

        String normalized = positionHint.toLowerCase(Locale.ROOT);
        List<String> ordered = new ArrayList<>();

        for(String scriptGroup : SCRIPT_GROUPS) {
            if(normalized.contains(scriptGroup.toLowerCase(Locale.ROOT))) {
                ordered.add(scriptGroup);
            }
        }

        for(String scriptGroup : SCRIPT_GROUPS) {
            if(!ordered.contains(scriptGroup)) {
                ordered.add(scriptGroup);
            }
        }
        return ordered;
    }

    private Path resolveSourcePath(Path gameRoot, Path sourceMapPath, SourceMapDocument document, int sourceIndex) {
        if(sourceIndex < 0 || sourceIndex >= document.sources().size()) {
            return null;
        }

        String source = document.sources().get(sourceIndex);
        String sourceRoot = document.sourceRoot();
        List<Path> candidates = new ArrayList<>();
        Path workspaceOutputRoot = KubeXCore.paths(gameRoot).output();

        addCandidate(candidates, workspaceOutputRoot, sourceRoot, source);
        addCandidate(candidates, sourceMapPath.getParent(), sourceRoot, source);
        addCandidate(candidates, gameRoot, sourceRoot, source);

        for(Path candidate : candidates) {
            if(Files.exists(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0).toAbsolutePath().normalize();
    }

    private void addCandidate(List<Path> candidates, Path base, String sourceRoot, String source) {
        try {
            Path path = base;
            if(sourceRoot != null && !sourceRoot.isBlank()) {
                path = path.resolve(sourceRoot);
            }
            candidates.add(path.resolve(source).normalize());
        } catch (InvalidPathException ignored) {
        }
    }

    private String formatSourcePath(Path gameRoot, Path sourceFile, String fallback) {
        if(sourceFile == null) {
            return fallback;
        }

        try {
            return gameRoot.relativize(sourceFile).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return sourceFile.toString().replace('\\', '/');
        }
    }

    private String readSnippet(Path sourceFile, int sourceLine) {
        try {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            if(sourceLine < 1 || sourceLine > lines.size()) {
                return null;
            }
            return lines.get(sourceLine - 1);
        } catch (IOException ignored) {
            return null;
        }
    }

    private KubeXSourceMapLookupResult failure(String scriptGroup, Path sourceMapPath, String message) {
        return new KubeXSourceMapLookupResult(false, message, scriptGroup, sourceMapPath, null, 0, 0, null);
    }

    private String failureMessage(Exception exception) {
        Throwable current = exception;
        while(current != null) {
            String message = current.getMessage();
            if(message != null && !message.isBlank()) return message;
            current = current.getCause();
        }
        return exception.getClass().getSimpleName();
    }

    private record SourceMapDocument(String sourceRoot, List<String> sources, String mappings) {
        private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        static SourceMapDocument parse(Path sourceMapPath) throws IOException {
            return parse(Files.readString(sourceMapPath, StandardCharsets.UTF_8));
        }

        static SourceMapDocument parse(String sourceMapContent) throws IOException {
            Object parsed = new JsonParser(sourceMapContent).parse();
            if(!(parsed instanceof Map<?, ?> root)) {
                throw new IOException("Source map root is not an object");
            }

            Object sourcesValue = root.get("sources");
            Object mappingsValue = root.get("mappings");
            if(!(sourcesValue instanceof List<?> sourceList) || !(mappingsValue instanceof String mappings)) {
                throw new IOException("Source map is missing sources or mappings");
            }

            List<String> sources = new ArrayList<>();
            for(Object entry : sourceList) {
                if(!(entry instanceof String source)) {
                    throw new IOException("Source map contains a non-string source entry");
                }
                sources.add(source);
            }

            Object sourceRootValue = root.get("sourceRoot");
            String sourceRoot = sourceRootValue instanceof String ? (String) sourceRootValue : null;
            return new SourceMapDocument(sourceRoot, sources, mappings);
        }

        SourceMapEntry lookup(int generatedLine, int generatedColumn) throws IOException {
            if(generatedLine < 1 || generatedColumn < 1) {
                throw new IOException("Generated positions are 1-based and must be positive");
            }

            int targetLine = generatedLine - 1;
            int targetColumn = generatedColumn - 1;

            int generatedLineState = 0;
            int previousSourceIndex = 0;
            int previousSourceLine = 0;
            int previousSourceColumn = 0;

            String[] lines = mappings.split(";", -1);
            for(String line : lines) {
                int previousGeneratedColumn = 0;
                SourceMapEntry bestEntry = null;
                int bestGeneratedColumn = -1;

                if(!line.isEmpty()) {
                    String[] segments = line.split(",", -1);
                    for(String segment : segments) {
                        if(segment.isEmpty()) {
                            continue;
                        }

                        int[] values = decodeSegment(segment);
                        previousGeneratedColumn += values[0];
                        if(values.length < 4) {
                            continue;
                        }

                        previousSourceIndex += values[1];
                        previousSourceLine += values[2];
                        previousSourceColumn += values[3];

                        if(generatedLineState == targetLine && previousGeneratedColumn <= targetColumn && previousGeneratedColumn >= bestGeneratedColumn) {
                            bestGeneratedColumn = previousGeneratedColumn;
                            bestEntry = new SourceMapEntry(previousSourceIndex, previousSourceLine + 1, previousSourceColumn + 1);
                        }
                    }
                }

                if(generatedLineState == targetLine) {
                    return bestEntry;
                }
                generatedLineState++;
            }
            return null;
        }

        SourceMapEntry lookupClosest(int generatedLine, int generatedColumn) throws IOException {
            for(int line = generatedLine; line >= 1; line--) {
                int column = line == generatedLine ? generatedColumn : Integer.MAX_VALUE;
                SourceMapEntry entry = lookup(line, column);
                if(entry != null) {
                    return entry;
                }
            }
            return null;
        }

        private int[] decodeSegment(String segment) throws IOException {
            List<Integer> values = new ArrayList<>();
            int index = 0;

            while(index < segment.length()) {
                int result = 0;
                int shift = 0;
                boolean continuation;

                do {
                    if(index >= segment.length()) {
                        throw new IOException("Unexpected end of VLQ segment");
                    }
                    int digit = BASE64.indexOf(segment.charAt(index++));
                    if(digit < 0) {
                        throw new IOException("Invalid VLQ character in source map");
                    }
                    continuation = (digit & 32) != 0;
                    digit &= 31;
                    result += digit << shift;
                    shift += 5;
                } while(continuation);

                boolean negative = (result & 1) == 1;
                result >>= 1;
                values.add(negative ? -result : result);
            }

            int[] decoded = new int[values.size()];
            for(int i = 0; i < values.size(); i++) {
                decoded[i] = values.get(i);
            }
            return decoded;
        }
    }

    private static final class JsonParser {
        private final String source;
        private int index;

        private JsonParser(String source) {
            this.source = source;
        }

        Object parse() throws IOException {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if(index != source.length()) {
                throw new IOException("Unexpected trailing JSON content");
            }
            return value;
        }

        private Object parseValue() throws IOException {
            if(index >= source.length()) {
                throw new IOException("Unexpected end of JSON");
            }

            char current = source.charAt(index);
            return switch (current) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            skipWhitespace();

            Map<String, Object> object = new LinkedHashMap<>();
            if(peek('}')) {
                expect('}');
                return object;
            }

            while(true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                object.put(key, parseValue());
                skipWhitespace();
                if(peek('}')) {
                    expect('}');
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            skipWhitespace();

            List<Object> array = new ArrayList<>();
            if(peek(']')) {
                expect(']');
                return array;
            }

            while(true) {
                skipWhitespace();
                array.add(parseValue());
                skipWhitespace();
                if(peek(']')) {
                    expect(']');
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();

            while(index < source.length()) {
                char current = source.charAt(index++);
                if(current == '"') {
                    return builder.toString();
                }
                if(current != '\\') {
                    builder.append(current);
                    continue;
                }
                if(index >= source.length()) {
                    throw new IOException("Unexpected end of JSON string");
                }

                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> {
                        if(index + 4 > source.length()) {
                            throw new IOException("Invalid unicode escape");
                        }
                        String hex = source.substring(index, index + 4);
                        builder.append((char) Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw new IOException("Invalid JSON escape");
                }
            }

            throw new IOException("Unterminated JSON string");
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if(source.startsWith(literal, index)) {
                index += literal.length();
                return value;
            }
            throw new IOException("Invalid JSON literal");
        }

        private Number parseNumber() throws IOException {
            int start = index;
            if(source.charAt(index) == '-') {
                index++;
            }

            while(index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }

            if(index < source.length() && source.charAt(index) == '.') {
                index++;
                while(index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }

            if(index < source.length() && (source.charAt(index) == 'e' || source.charAt(index) == 'E')) {
                index++;
                if(index < source.length() && (source.charAt(index) == '+' || source.charAt(index) == '-')) {
                    index++;
                }
                while(index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }

            String number = source.substring(start, index);
            try {
                if(number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException exception) {
                throw new IOException("Invalid JSON number", exception);
            }
        }

        private void skipWhitespace() {
            while(index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char expected) {
            return index < source.length() && source.charAt(index) == expected;
        }

        private void expect(char expected) throws IOException {
            if(index >= source.length() || source.charAt(index) != expected) {
                throw new IOException("Expected '" + expected + "'");
            }
            index++;
        }
    }
}
