package com.ourgram.kubex.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import com.ourgram.kubex.KubeXCore;
import com.ourgram.kubex.compiler.CompileOptions;
import com.ourgram.kubex.compiler.ScriptCompiler;

public final class ExportService {
    private record ScriptGroup(String outputFile, String scriptType) {}

    private static final String CONFIG_FILE = "export.properties";
    private static final String EXPORTERS_RESOURCE = "META-INF/kubex/exporter.properties";
    private static final List<ScriptGroup> SCRIPT_GROUPS = List.of(
        new ScriptGroup("startup_scripts", "startup"),
        new ScriptGroup("server_scripts", "server"),
        new ScriptGroup("client_scripts", "client")
    );

    private final ScriptCompiler compiler = new ScriptCompiler();

    public ExportResult export(Path gameRoot) {
        Path workspace = KubeXCore.paths(gameRoot).workspace();
        Path configFile = workspace.resolve(CONFIG_FILE);
        if(!Files.isRegularFile(configFile)) {
            return new ExportResult(false, null, "Export configuration was not found: kubex/" + CONFIG_FILE);
        }

        try {
            ExportConfig config = ExportConfig.load(configFile);
            validateIcon(workspace, config);
            Map<String, String> scripts = compileScripts(workspace);
            if(scripts.isEmpty()) {
                return new ExportResult(false, null, "No compiled scripts were found in kubex/output. Run /kubex build first.");
            }

            Path outputFile = workspace.resolve("export").resolve(config.modId() + "-" + config.version() + ".jar");
            findExporter().export(workspace, outputFile, config, scripts);
            return new ExportResult(true, outputFile, "Exported " + outputFile.getFileName());
        } catch(Exception exception) {
            return new ExportResult(false, null, failureMessage(exception));
        }
    }

    private Map<String, String> compileScripts(Path workspace) throws IOException {
        Map<String, String> scripts = new LinkedHashMap<>();
        for(ScriptGroup group : SCRIPT_GROUPS) {
            Path source = workspace.resolve("output").resolve(group.outputFile() + ".js");
            if(!Files.isRegularFile(source)) continue;

            String input = Files.readString(source, StandardCharsets.UTF_8);
            String output = compiler.compile(source.getFileName().toString(), input, CompileOptions.DEFAULT).outputSource();
            scripts.put(group.scriptType(), output);
        }
        return scripts;
    }

    private void validateIcon(Path workspace, ExportConfig config) throws IOException {
        if(config.icon().isBlank()) return;
        Path assets = workspace.resolve("src").resolve("assets").toAbsolutePath().normalize();
        Path icon = assets.resolve(config.icon()).normalize();
        if(!icon.startsWith(assets) || !Files.isRegularFile(icon)) {
            throw new IOException("mod.icon was not found in src/assets: " + config.icon());
        }
    }

    private Exporter findExporter() throws IOException {
        Exporter exporter = null;
        try {
            Enumeration<URL> resources = Exporter.class.getClassLoader().getResources(EXPORTERS_RESOURCE);
            while(resources.hasMoreElements()) {
                Properties properties = new Properties();
                try (InputStream input = resources.nextElement().openStream()) {
                    properties.load(input);
                }

                String className = properties.getProperty("exporter");
                if(className == null || className.isBlank()) continue;

                if(exporter != null) {
                    throw new IOException("More than one KubeX exporter is installed");
                }
                exporter = loadExporter(className.trim());
            }
        } catch (ReflectiveOperationException exception) {
            throw new IOException("Failed to load KubeX exporter", exception);
        }

        if(exporter == null) throw new IOException("No KubeX exporter is installed");
        return exporter;
    }

    private Exporter loadExporter(String className) throws ReflectiveOperationException, IOException {
        Class<?> type = Class.forName(className, true, Exporter.class.getClassLoader());
        if(!Exporter.class.isAssignableFrom(type)) {
            throw new IOException("Configured exporter does not implement Exporter: " + className);
        }
        return type.asSubclass(Exporter.class).getDeclaredConstructor().newInstance();
    }

    private String failureMessage(Exception exception) {
        Throwable current = exception;
        while(current != null) {
            if(current.getMessage() != null && !current.getMessage().isBlank()) return current.getMessage();
            current = current.getCause();
        }
        return exception.getClass().getSimpleName();
    }
}