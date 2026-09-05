package com.ourgram.kubex.export;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

public record ExportConfig(
    String modPackage,
    String modId,
    String modName,
    String version,
    String description,
    String authors,
    String license,
    String icon,
    String kubeJsVersion,
    List<Dependency> dependencies
) {
    private static final Pattern MOD_ID_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9._+-]+$");

    public record Dependency(String modId, String versionRange, boolean mandatory, String ordering, String side) {}

    public static ExportConfig load(Path file) throws IOException {
        Properties properties = new Properties();
        try (InputStreamReader input = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
            properties.load(input);
        }

        String modPackage = required(properties, "mod.package");
        if(!PACKAGE_PATTERN.matcher(modPackage).matches()) {
            throw new IOException("mod.package must be a valid Java package name");
        }

        String modId = required(properties, "mod.id");
        if(!MOD_ID_PATTERN.matcher(modId).matches()) {
            throw new IOException("mod.id must use lowercase letters, numbers, underscores, or hyphens");
        }
        String version = required(properties, "mod.version");
        if(!VERSION_PATTERN.matcher(version).matches()) {
            throw new IOException("mod.version may only use letters, numbers, dots, underscores, plus signs, or hyphens");
        }
        String icon = icon(properties);

        List<Dependency> dependencies = new ArrayList<>();
        for(String key : properties.stringPropertyNames()) {
            if(!key.startsWith("dependency.") || !key.endsWith(".version")) continue;

            String dependencyId = key.substring("dependency.".length(), key.length() - ".version".length());
            if(!MOD_ID_PATTERN.matcher(dependencyId).matches()) {
                throw new IOException("Invalid dependency mod id: " + dependencyId);
            }
            if("kubejs".equals(dependencyId)) continue;

            dependencies.add(new Dependency(
                dependencyId,
                required(properties, key),
                Boolean.parseBoolean(properties.getProperty("dependency." + dependencyId + ".mandatory", "true")),
                properties.getProperty("dependency." + dependencyId + ".ordering", "AFTER"),
                properties.getProperty("dependency." + dependencyId + ".side", "BOTH")
            ));
        }

        return new ExportConfig(
            modPackage,
            modId,
            required(properties, "mod.name"),
            version,
            properties.getProperty("mod.description", ""),
            properties.getProperty("mod.authors", ""),
            properties.getProperty("license", "All Rights Reserved").trim(),
            icon,
            properties.getProperty("kubejs.version", "[2101.7.2,)"),
            List.copyOf(dependencies)
        );
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key, "").trim();
        if(value.isEmpty()) throw new IOException("Missing export setting: " + key);
        return value;
    }

    private static String icon(Properties properties) throws IOException {
        String value = properties.getProperty("mod.icon", "").trim();
        if(value.isEmpty()) return "";

        try {
            Path path = Path.of(value).normalize();
            if(path.isAbsolute() || path.startsWith("..")) {
                throw new IOException("mod.icon must be a path inside src/assets");
            }
            return path.toString().replace('\\', '/');
        } catch(RuntimeException exception) {
            throw new IOException("mod.icon must be a valid path inside src/assets", exception);
        }
    }
}