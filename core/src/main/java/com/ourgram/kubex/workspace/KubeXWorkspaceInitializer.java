package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class KubeXWorkspaceInitializer {
    private static final String TS_INCLUDE = "\"./**/*.ts\"";

    public KubeXWorkspaceInitResult initialize(Path gameRoot, KubeXInitMode mode) {
        Path normalizedGameRoot = gameRoot.toAbsolutePath().normalize();
        Path workspaceRoot = normalizedGameRoot.resolve("kubex");

        try {
            createDirectories(workspaceRoot);
            syncWorkspaceRoot(normalizedGameRoot, workspaceRoot);
            syncFileIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("client_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("client_scripts").resolve("jsconfig.json")
            );
            syncFileIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("server_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("server_scripts").resolve("jsconfig.json")
            );
            syncFileIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("startup_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("startup_scripts").resolve("jsconfig.json")
            );
            syncDirectoryIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("config"),
                workspaceRoot.resolve("src").resolve("config")
            );
            ensureTypeScriptInclude(workspaceRoot.resolve("src").resolve("client_scripts").resolve("jsconfig.json"));
            ensureTypeScriptInclude(workspaceRoot.resolve("src").resolve("server_scripts").resolve("jsconfig.json"));
            ensureTypeScriptInclude(workspaceRoot.resolve("src").resolve("startup_scripts").resolve("jsconfig.json"));

            writeEntryIfMissing(
                workspaceRoot.resolve("src").resolve("client_scripts"),
                mode,
                templatePath(mode, "client_main")
            );
            writeEntryIfMissing(
                workspaceRoot.resolve("src").resolve("server_scripts"),
                mode,
                templatePath(mode, "server_main")
            );
            writeEntryIfMissing(
                workspaceRoot.resolve("src").resolve("startup_scripts"),
                mode,
                templatePath(mode, "startup_main")
            );
            return new KubeXWorkspaceInitResult(true, workspaceRoot, mode, "Initialized KubeX workspace");
        } catch (IOException exception) {
            return new KubeXWorkspaceInitResult(false, workspaceRoot, mode, exception.getMessage());
        }
    }

    private void createDirectories(Path workspaceRoot) throws IOException {
        Files.createDirectories(workspaceRoot.resolve("output"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("assets"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("client_scripts"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("config"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("data"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("server_scripts"));
        Files.createDirectories(workspaceRoot.resolve("src").resolve("startup_scripts"));
    }

    private void syncWorkspaceRoot(Path gameRoot, Path workspaceRoot) throws IOException {
        syncDirectoryIfExists(gameRoot.resolve(".probe"), workspaceRoot.resolve(".probe"));
        copyPathIfExists(gameRoot.resolve(".vscode"), workspaceRoot.resolve(".vscode"));
        writeResource(workspaceRoot.resolve("package.json"), "kubex/templates/common/package.json");
        writeResource(workspaceRoot.resolve("esbuild.config.mjs"), "kubex/templates/common/esbuild.config.mjs");
    }

    private void syncDirectoryIfExists(Path source, Path target) throws IOException {
        if(!Files.exists(source)) return;

        if(Files.isDirectory(source)) {
            syncDirectory(source, target);
            return;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyPathIfExists(Path source, Path target) throws IOException {
        if(!Files.exists(source)) return;

        if(Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (Stream<Path> stream = Files.list(source)) {
                for(Path child : stream.toList()) {
                    copyPathIfExists(child, target.resolve(child.getFileName()));
                }
            }
            return;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void syncFileIfExists(Path source, Path target) throws IOException {
        if(!Files.exists(source) || Files.isDirectory(source)) return;

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void syncDirectory(Path sourceRoot, Path targetRoot) throws IOException {
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
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }

        deleteMissingTargets(sourceRoot, targetRoot);
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

    private void writeIfMissing(Path path, String resourcePath) throws IOException {
        if(Files.exists(path)) return;

        Files.createDirectories(path.getParent());
        try (InputStream inputStream = resource(resourcePath)) {
            Files.copy(inputStream, path);
        }
    }

    private void writeResource(Path path, String resourcePath) throws IOException {
        Files.createDirectories(path.getParent());
        try (InputStream inputStream = resource(resourcePath)) {
            Files.copy(inputStream, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void writeEntryIfMissing(Path scriptsRoot, KubeXInitMode mode, String resourcePath) throws IOException {
        if(Files.exists(scriptsRoot.resolve("main.js")) || Files.exists(scriptsRoot.resolve("main.ts"))) return;
        writeIfMissing(scriptsRoot.resolve(mode.entryFileName()), resourcePath);
    }

    private void ensureTypeScriptInclude(Path jsconfigPath) throws IOException {
        if(!Files.exists(jsconfigPath) || Files.isDirectory(jsconfigPath)) return;

        String content = Files.readString(jsconfigPath, StandardCharsets.UTF_8);
        if(content.contains(TS_INCLUDE)) return;

        String updated = content.replace("\"./**/*.js\",", "\"./**/*.js\",\n        " + TS_INCLUDE + ",");
        if(updated.equals(content)) {
            updated = content.replace("\"include\": [", "\"include\": [\n        " + TS_INCLUDE + ",");
        }

        if(!updated.equals(content)) {
            Files.writeString(jsconfigPath, updated, StandardCharsets.UTF_8);
        }
    }

    private InputStream resource(String path) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(path);
        if(inputStream == null) {
            throw new IOException("Missing template resource: " + path);
        }
        return inputStream;
    }

    private String templatePath(KubeXInitMode mode, String baseName) {
        String folder = mode == KubeXInitMode.TS ? "ts" : "js";
        String extension = mode == KubeXInitMode.TS ? ".ts" : ".js";
        return "kubex/templates/" + folder + "/" + baseName + extension;
    }
}
