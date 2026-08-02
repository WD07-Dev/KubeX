package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;

public final class KubeXWorkspaceInitializer {
    private static final String TS_INCLUDE = "\"./**/*.ts\"";

    public KubeXWorkspaceInitResult initialize(Path gameRoot, KubeXInitMode mode) {
        Path normalizedGameRoot = gameRoot.toAbsolutePath().normalize();
        Path workspaceRoot = normalizedGameRoot.resolve("kubex");

        try {
            createDirectories(workspaceRoot);
            copyIfExists(normalizedGameRoot.resolve(".probe"), workspaceRoot.resolve(".probe"));
            copyIfExists(normalizedGameRoot.resolve(".vscode"), workspaceRoot.resolve(".vscode"));
            copyIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("client_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("client_scripts").resolve("jsconfig.json")
            );
            copyIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("server_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("server_scripts").resolve("jsconfig.json")
            );
            copyIfExists(
                normalizedGameRoot.resolve("kubejs").resolve("startup_scripts").resolve("jsconfig.json"),
                workspaceRoot.resolve("src").resolve("startup_scripts").resolve("jsconfig.json")
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
            writeIfMissing(workspaceRoot.resolve("package.json"), "kubex/templates/common/package.json");
            writeIfMissing(workspaceRoot.resolve("esbuild.config.mjs"), "kubex/templates/common/esbuild.config.mjs");

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

    private void copyIfExists(Path source, Path target) throws IOException {
        if(!Files.exists(source)) return;

        if(Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (var stream = Files.list(source)) {
                for(Path child : stream.toList()) {
                    copyIfExists(child, target.resolve(child.getFileName()));
                }
            }
            return;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void writeIfMissing(Path path, String resourcePath) throws IOException {
        if(Files.exists(path)) return;

        Files.createDirectories(path.getParent());
        try (InputStream inputStream = resource(resourcePath)) {
            Files.copy(inputStream, path);
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
