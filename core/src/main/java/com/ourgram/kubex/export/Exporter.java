package com.ourgram.kubex.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

public interface Exporter {
    void export(Path workspace, Path outputFile, ExportConfig config, Map<String, String> scripts, Consumer<String> progress) throws IOException;
}