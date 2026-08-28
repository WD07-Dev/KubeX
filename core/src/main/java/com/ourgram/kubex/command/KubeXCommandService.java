package com.ourgram.kubex.command;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import com.ourgram.kubex.KubeXDebugManager;
import com.ourgram.kubex.KubeXDoctorManager;
import com.ourgram.kubex.KubeXWorkspaceManager;
import com.ourgram.kubex.sourcemap.KubeXSourceMapLookupResult;
import com.ourgram.kubex.workspace.KubeXInitMode;
import com.ourgram.kubex.workspace.KubeXWorkspaceReloadService;

public final class KubeXCommandService {
    private record ParsedDoctorPosition(int line, int column) {}
    public record KubeXCommandResult(boolean success, String message) {}

    private static final Pattern DOCTOR_POSITION_PATTERN = Pattern.compile(
        "(?:^|[^0-9A-Za-z_./-])(?:[A-Za-z0-9_./-]+\\.js:)?(\\d+)(?::(\\d+))?(?:[^0-9]|$)"
    );

    private final KubeXWorkspaceManager workspaceManager;
    private final KubeXDebugManager debugManager;
    private final KubeXDoctorManager doctorManager;

    public KubeXCommandService(KubeXWorkspaceManager workspaceManager, KubeXDebugManager debugManager, KubeXDoctorManager doctorManager) {
        this.workspaceManager = workspaceManager;
        this.debugManager = debugManager;
        this.doctorManager = doctorManager;
    }

    public KubeXCommandResult initialize(Path gameRoot, KubeXInitMode mode) {
        try {
            var result = workspaceManager.initialize(gameRoot, mode);
            if(!result.success()) {
                return new KubeXCommandResult(false, result.message());
            }

            return new KubeXCommandResult(true, "Initialized " + mode.id() + " workspace: " + result.workspaceRoot());
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult build(Path gameRoot, Consumer<String> progressListener, KubeXWorkspaceReloadService reloadService) {
        try {
            var result = workspaceManager.buildAndSync(gameRoot, progressListener, reloadService);
            return new KubeXCommandResult(result.success(), result.message());
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult sync(Path gameRoot, KubeXWorkspaceReloadService reloadService) {
        try {
            var result = workspaceManager.sync(gameRoot);
            if(!result.success()) {
                return new KubeXCommandResult(false, result.message());
            }

            if(!result.publishedFiles().isEmpty() && reloadService != null && !reloadService.reload()) {
                return new KubeXCommandResult(false, "Sync completed but /reload did not run");
            }

            return new KubeXCommandResult(true, result.message() + " (" + result.sourceMapCount() + " source maps)");
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult doctor(Path gameRoot, String scriptGroup, int line, int column) {
        try {
            var result = doctorManager.lookup(gameRoot, scriptGroup, line, column);
            if(!result.success()) {
                return new KubeXCommandResult(false, result.message());
            }

            return new KubeXCommandResult(true, scriptGroup + " " + line + ":" + column + " -> " + formatDoctorResult(result));
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult doctorAuto(Path gameRoot, String position) {
        try {
            ParsedDoctorPosition parsed = parseDoctorPosition(position);
            if(parsed == null) {
                return new KubeXCommandResult(false, "invalid position '" + position + "'");
            }

            var result = doctorManager.lookupAny(gameRoot, position, parsed.line(), parsed.column());
            if(!result.success()) {
                return new KubeXCommandResult(false, result.message());
            }

            return new KubeXCommandResult(true, "auto " + parsed.line() + ":" + parsed.column() + " -> " + formatDoctorResult(result));
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult doctorPosition(Path gameRoot, String scriptGroup, String position) {
        ParsedDoctorPosition parsed = parseDoctorPosition(position);
        if(parsed == null) {
            return new KubeXCommandResult(false, "invalid position '" + position + "'");
        }

        return doctor(gameRoot, scriptGroup, parsed.line(), parsed.column());
    }

    public KubeXCommandResult toggleDebug(Path gameRoot) {
        try {
            boolean enabled = debugManager.toggle(gameRoot);
            return new KubeXCommandResult(true, "Debug mode " + (enabled ? "enabled" : "disabled"));
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
        }
    }

    public KubeXCommandResult setDebug(Path gameRoot, boolean enabled) {
        try {
            debugManager.setEnabled(gameRoot, enabled);
            return new KubeXCommandResult(true, "Debug mode " + (enabled ? "enabled" : "disabled"));
        } catch (Exception exception) {
            return new KubeXCommandResult(false, failureMessage(exception));
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

    private String formatDoctorResult(KubeXSourceMapLookupResult result) {
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
