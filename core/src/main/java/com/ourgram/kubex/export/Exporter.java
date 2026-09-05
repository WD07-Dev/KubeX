package com.ourgram.kubex.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public interface Exporter {
    void export(Path workspace, Path outputFile, ExportConfig config, Map<String, String> scripts) throws IOException;
}