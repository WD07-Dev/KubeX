package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import com.ourgram.kubex.compiler.CompileOptions;
import com.ourgram.kubex.compiler.KubeXCompiler;

public final class KubeXWorkspaceSyncService {
    private static final String SOURCE_MAP_COMMENT = "//# sourceMappingURL=";
    private final KubeXCompiler compiler;
    private final KubeXDebugModeService debugModeService;

    public KubeXWorkspaceSyncService(KubeXCompiler compiler, KubeXDebugModeService debugModeService) {
        this.compiler = compiler;
        this.debugModeService = debugModeService;
    }

    public KubeXWorkspaceSyncResult sync(Path gameRoot) {
        Path normalizedGameRoot = gameRoot.toAbsolutePath().normalize();
        Path workspaceRoot = normalizedGameRoot.resolve("kubex");
        Path outputRoot = workspaceRoot.resolve("output");
        Path kubeJsRoot = normalizedGameRoot.resolve("kubejs");
        boolean debugEnabled = debugModeService.isEnabled(normalizedGameRoot);

        try {
            List<Path> publishedFiles = new ArrayList<>();
            int sourceMapCount = 0;
            sourceMapCount += syncGroup(
                outputRoot.resolve("client_scripts.js"),
                kubeJsRoot.resolve("client_scripts").resolve("main.js"),
                publishedFiles,
                debugEnabled
            );
            sourceMapCount += syncGroup(
                outputRoot.resolve("server_scripts.js"),
                kubeJsRoot.resolve("server_scripts").resolve("main.js"),
                publishedFiles,
                debugEnabled
            );
            sourceMapCount += syncGroup(
                outputRoot.resolve("startup_scripts.js"),
                kubeJsRoot.resolve("startup_scripts").resolve("main.js"),
                publishedFiles,
                debugEnabled
            );
            syncDirectory(
                workspaceRoot.resolve("src").resolve("assets"),
                kubeJsRoot.resolve("assets"),
                publishedFiles
            );
            syncDirectory(
                workspaceRoot.resolve("src").resolve("config"),
                kubeJsRoot.resolve("config"),
                publishedFiles
            );
            syncDirectory(
                workspaceRoot.resolve("src").resolve("data"),
                kubeJsRoot.resolve("data"),
                publishedFiles
            );

            return new KubeXWorkspaceSyncResult(
                true,
                workspaceRoot,
                publishedFiles,
                sourceMapCount,
                publishedFiles.isEmpty()
                ? "No compiled output files were found in kubex/output"
                : "Synchronized KubeX output into kubejs"
            );
        } catch (Exception exception) {
            String message = failureMessage(exception);
            return new KubeXWorkspaceSyncResult(false, workspaceRoot, List.of(), 0, message);
        }
    }

    private int syncGroup(Path sourceFile, Path targetFile, List<Path> publishedFiles, boolean debugEnabled) throws IOException {
        if(!Files.exists(sourceFile)) return 0;

        String source = Files.readString(sourceFile, StandardCharsets.UTF_8);

        try {
            Path sourceMapFile = sourceFile.resolveSibling(sourceFile.getFileName() + ".map");
            boolean hasSourceMap = Files.exists(sourceMapFile);
            String sourceMapContent = hasSourceMap ? Files.readString(sourceMapFile, StandardCharsets.UTF_8) : null;

            String transformed = compiler.compile(
                sourceFile.getFileName().toString(),
                source,
                new CompileOptions(debugEnabled, sourceMapContent)
            ).outputSource();

            if(hasSourceMap) {
                transformed = rewriteSourceMapComment(transformed, targetFile.getFileName() + ".map");
            }

            Files.createDirectories(targetFile.getParent());
            Files.writeString(targetFile, transformed, StandardCharsets.UTF_8);
            publishedFiles.add(targetFile);

            if(hasSourceMap) {
                Path targetSourceMapFile = targetFile.resolveSibling(targetFile.getFileName() + ".map");
                Files.copy(sourceMapFile, targetSourceMapFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                publishedFiles.add(targetSourceMapFile);
                return 1;
            }
            return 0;
        } catch (Exception exception) {
            throw new IOException("Failed to sync " + sourceFile.getFileName() + ": " + failureMessage(exception), exception);
        }
    }

    private void syncDirectory(Path sourceRoot, Path targetRoot, List<Path> publishedFiles) throws IOException {
        if(!Files.isDirectory(sourceRoot)) return;

        try {
            Files.createDirectories(targetRoot);

            List<Path> sourcePaths;
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                sourcePaths = stream.toList();
            }

            for(Path sourcePath : sourcePaths) {
                Path relativePath = sourceRoot.relativize(sourcePath);
                Path targetPath = targetRoot.resolve(relativePath.toString());

                if(Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                    continue;
                }

                Files.createDirectories(targetPath.getParent());
                Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                publishedFiles.add(targetPath);
            }

            deleteMissingTargets(sourceRoot, targetRoot);
        } catch (Exception exception) {
            throw new IOException("Failed to sync " + sourceRoot.getFileName() + ": " + failureMessage(exception), exception);
        }
    }

    private void deleteMissingTargets(Path sourceRoot, Path targetRoot) throws IOException {
        if(!Files.exists(targetRoot)) return;

        List<Path> stalePaths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(targetRoot)) {
            for(Path targetPath : (Iterable<Path>) stream::iterator) {
                if(targetPath.equals(targetRoot)) continue;

                Path relativePath = targetRoot.relativize(targetPath);
                if(Files.exists(sourceRoot.resolve(relativePath.toString()))) continue;
                stalePaths.add(targetPath);
            }
        }

        stalePaths.sort(Comparator.reverseOrder());
        for(Path stalePath : stalePaths) {
            Files.deleteIfExists(stalePath);
        }
    }

    private String rewriteSourceMapComment(String source, String targetSourceMapFileName) {
        String rewrittenComment = SOURCE_MAP_COMMENT + targetSourceMapFileName;
        int commentIndex = source.lastIndexOf(SOURCE_MAP_COMMENT);
        if(commentIndex < 0) {
            return source + System.lineSeparator() + rewrittenComment;
        }

        int lineEnd = source.indexOf('\n', commentIndex);
        if(lineEnd < 0) {
            return source.substring(0, commentIndex) + rewrittenComment;
        }

        return source.substring(0, commentIndex) + rewrittenComment + source.substring(lineEnd);
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
}
