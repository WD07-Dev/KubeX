package com.ourgram.kubex.command;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import com.ourgram.kubex.export.ExportService;
import com.ourgram.kubex.sourcemap.SourceMapLookupResult;
import com.ourgram.kubex.sourcemap.SourceMapService;
import com.ourgram.kubex.workspace.DebugMode;
import com.ourgram.kubex.workspace.InitMode;
import com.ourgram.kubex.workspace.Workspace;
import com.ourgram.kubex.workspace.ReloadService;

public final class CommandService {
    private record ParsedDoctorPosition(int line, int column) {}
    public record CommandResult(boolean success, String message) {}

    private static final Pattern DOCTOR_POSITION_PATTERN = Pattern.compile(
        "(?:^|[^0-9A-Za-z_./-])(?:[A-Za-z0-9_./-]+\\.js:)?(\\d+)(?::(\\d+))?(?:[^0-9]|$)"
    );

    private final Workspace workspace;
    private final DebugMode debugMode;
    private final SourceMapService sourceMaps;
    private final ExportService exportService = new ExportService();

    public CommandService(Workspace workspace, DebugMode debugMode, SourceMapService sourceMaps) {
        this.workspace = workspace;
        this.debugMode = debugMode;
        this.sourceMaps = sourceMaps;
    }

    public CommandResult initialize(Path gameRoot, InitMode mode) {
        try {
            var result = workspace.initialize(gameRoot, mode);
            if(!result.success()) {
                return new CommandResult(false, result.message());
            }

            return new CommandResult(true, "Initialized " + mode.id() + " workspace: " + result.workspaceRoot());
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult build(Path gameRoot, Consumer<String> progressListener, ReloadService reloadService) {
        try {
            var result = workspace.buildAndSync(gameRoot, progressListener, reloadService);
            return new CommandResult(result.success(), result.message());
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult sync(Path gameRoot, ReloadService reloadService) {
        try {
            var result = workspace.sync(gameRoot);
            if(!result.success()) {
                return new CommandResult(false, result.message());
            }

            if(!result.publishedFiles().isEmpty() && reloadService != null && !reloadService.reload()) {
                return new CommandResult(false, "Sync completed but /reload did not run");
            }

            return new CommandResult(true, result.message() + " (" + result.sourceMapCount() + " source maps)");
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult export(Path gameRoot) {
        var result = exportService.export(gameRoot);
        return new CommandResult(result.success(), result.message());
    }

    public CommandResult doctor(Path gameRoot, String scriptGroup, int line, int column) {
        try {
            var result = sourceMaps.lookup(gameRoot, scriptGroup, line, column);
            if(!result.success()) {
                return new CommandResult(false, result.message());
            }

            return new CommandResult(true, scriptGroup + " " + line + ":" + column + " -> " + formatDoctorResult(result));
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult doctorAuto(Path gameRoot, String position) {
        try {
            ParsedDoctorPosition parsed = parseDoctorPosition(position);
            if(parsed == null) {
                return new CommandResult(false, "invalid position '" + position + "'");
            }

            var result = sourceMaps.lookupAny(gameRoot, position, parsed.line(), parsed.column());
            if(!result.success()) {
                return new CommandResult(false, result.message());
            }

            return new CommandResult(true, "auto " + parsed.line() + ":" + parsed.column() + " -> " + formatDoctorResult(result));
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult doctorPosition(Path gameRoot, String scriptGroup, String position) {
        ParsedDoctorPosition parsed = parseDoctorPosition(position);
        if(parsed == null) {
            return new CommandResult(false, "invalid position '" + position + "'");
        }

        return doctor(gameRoot, scriptGroup, parsed.line(), parsed.column());
    }

    public CommandResult toggleDebug(Path gameRoot) {
        try {
            boolean enabled = debugMode.toggle(gameRoot);
            return new CommandResult(true, "Debug mode " + (enabled ? "enabled" : "disabled"));
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    public CommandResult setDebug(Path gameRoot, boolean enabled) {
        try {
            debugMode.setEnabled(gameRoot, enabled);
            return new CommandResult(true, "Debug mode " + (enabled ? "enabled" : "disabled"));
        } catch (Exception exception) {
            return new CommandResult(false, failureMessage(exception));
        }
    }

    private ParsedDoctorPosition parseDoctorPosition(String position) {
        Matcher matcher = DOCTOR_POSITION_PATTERN.matcher(position);
        if(!matcher.find()) return null;

        try {
            int line = Integer.parseInt(matcher.group(1));
            int column = matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2));
            return new ParsedDoctorPosition(line, column);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String formatDoctorResult(SourceMapLookupResult result) {
        String prefix = result.scriptGroup() == null ? "" : result.scriptGroup() + " -> ";
        String mapped = prefix + result.sourcePath() + ":" + result.sourceLine() + ":" + result.sourceColumn();
        if(result.sourceSnippet() == null || result.sourceSnippet().isBlank()) return mapped;
        return mapped + " | " + result.sourceSnippet().trim();
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