package com.ourgram.kubex.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExportConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void readsThePluginPackage() throws IOException {
        ExportConfig config = load("""
        mod.package=example.kubejs.mod
        mod.id=example_mod
        mod.name=Example Mod
        mod.version=1.0.0
        license=MIT
        mod.icon=example_mod/icon.png
        """);
        assertEquals("example.kubejs.mod", config.modPackage());
        assertEquals("MIT", config.license());
        assertEquals("example_mod/icon.png", config.icon());
    }

    @Test
    void requiresAValidPluginPackage() throws IOException {
        Path file = tempDir.resolve("export.properties");
        Files.writeString(file, """
        mod.package=not-a-package
        mod.id=example_mod
        mod.name=Example Mod
        mod.version=1.0.0
        """);
        assertThrows(IOException.class, () -> ExportConfig.load(file));
    }

    private ExportConfig load(String content) throws IOException {
        Path file = tempDir.resolve("export.properties");
        Files.writeString(file, content);
        return ExportConfig.load(file);
    }
}