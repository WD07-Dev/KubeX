package com.ourgram.kubex.neoforge.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import com.ourgram.kubex.export.ExportConfig;

final class JarWriter {
    public void write(
        Path workspace,
        Path outputFile,
        ExportConfig config,
        PluginTemplate.PluginClass plugin,
        Map<String, String> scripts
    ) throws IOException {
        Files.createDirectories(outputFile.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outputFile), manifest())) {
            writeText(jar, "META-INF/neoforge.mods.toml", modsToml(config));
            writeText(jar, "kubejs.plugins.txt", plugin.className() + "\n");
            writeBytes(jar, plugin.className().replace('.', '/') + ".class", plugin.bytecode());

            for(Map.Entry<String, String> script : scripts.entrySet()) {
                writeText(jar, "kubex/scripts/" + script.getKey() + "/" + config.modId() + "/main.js", script.getValue());
            }

            copyDirectory(jar, workspace.resolve("src").resolve("assets"), "assets");
            copyDirectory(jar, workspace.resolve("src").resolve("data"), "data");
        }
    }

    private Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    private void copyDirectory(JarOutputStream jar, Path sourceRoot, String targetRoot) throws IOException {
        if(!Files.isDirectory(sourceRoot)) return;

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for(Path source : paths.filter(Files::isRegularFile).toList()) {
                String relative = sourceRoot.relativize(source).toString().replace('\\', '/');
                writeBytes(jar, targetRoot + "/" + relative, Files.readAllBytes(source));
            }
        }
    }

    private void writeText(JarOutputStream jar, String path, String content) throws IOException {
        writeBytes(jar, path, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(JarOutputStream jar, String path, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(path));
        jar.write(bytes);
        jar.closeEntry();
    }

    private String modsToml(ExportConfig config) {
        StringBuilder toml = new StringBuilder();
        toml.append("modLoader = \"javafml\"\n");
        toml.append("loaderVersion = \"[2,)\"\n");
        toml.append("license = \"All Rights Reserved\"\n\n");
        toml.append("[[mods]]\n");
        toml.append("modId = \"").append(toml(config.modId())).append("\"\n");
        toml.append("version = \"").append(toml(config.version())).append("\"\n");
        toml.append("displayName = \"").append(toml(config.modName())).append("\"\n");
        toml.append("authors = \"").append(toml(config.authors())).append("\"\n");
        toml.append("description = \"").append(toml(config.description())).append("\"\n\n");
        appendDependency(toml, config.modId(), new ExportConfig.Dependency("kubejs", config.kubeJsVersion(), true, "AFTER", "BOTH"));
        for(ExportConfig.Dependency dependency : config.dependencies()) {
            appendDependency(toml, config.modId(), dependency);
        }
        return toml.toString();
    }

    private void appendDependency(StringBuilder toml, String owner, ExportConfig.Dependency dependency) {
        toml.append("[[dependencies.").append(owner).append("]]\n");
        toml.append("modId = \"").append(toml(dependency.modId())).append("\"\n");
        toml.append("mandatory = ").append(dependency.mandatory()).append("\n");
        toml.append("versionRange = \"").append(toml(dependency.versionRange())).append("\"\n");
        toml.append("ordering = \"").append(toml(dependency.ordering())).append("\"\n");
        toml.append("side = \"").append(toml(dependency.side())).append("\"\n\n");
    }

    private String toml(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}