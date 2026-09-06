package com.ourgram.kubex.neoforge.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import com.ourgram.kubex.export.ExportConfig;
import com.ourgram.kubex.export.Exporter;

public final class NeoForgeExporter implements Exporter {
    private final PluginTemplate pluginTemplate = new PluginTemplate();
    private final JarWriter jarWriter = new JarWriter();

    @Override
    public void export(Path workspace, Path outputFile, ExportConfig config, Map<String, String> scripts, Consumer<String> progress) throws IOException {
        progress.accept("Writing NeoForge mod JAR...");
        jarWriter.write(
            workspace,
            outputFile,
            config,
            pluginTemplate.create(config.modPackage(), config.modId()),
            scripts
        );
    }
}