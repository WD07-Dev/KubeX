package com.ourgram.kubex.neoforge;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ourgram.kubex.app.KubeXDebugManager;
import com.ourgram.kubex.app.KubeXDoctorManager;
import com.ourgram.kubex.app.KubeXWorkspaceManager;
import com.ourgram.kubex.workspace.KubeXInitMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

public final class KubeXCommands {
    private record ParsedDoctorPosition(int line, int column) {}

    private static final Pattern DOCTOR_POSITION_PATTERN = Pattern.compile(
        "(?:^|[^0-9A-Za-z_./-])(?:[A-Za-z0-9_./-]+\\.js:)?(\\d+)(?::(\\d+))?(?:[^0-9]|$)"
    );

    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubex-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final KubeXWorkspaceManager workspaceManager;
    private final KubeXDebugManager debugManager;
    private final KubeXDoctorManager doctorManager;
    private final KubeXReloadBridge reloadBridge;

    public KubeXCommands(KubeXWorkspaceManager workspaceManager, KubeXDebugManager debugManager, KubeXDoctorManager doctorManager) {
        this.workspaceManager = workspaceManager;
        this.debugManager = debugManager;
        this.doctorManager = doctorManager;
        this.reloadBridge = new KubeXReloadBridge();
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher, source -> source.hasPermission(2));
    }

    public void registerClient(CommandDispatcher<CommandSourceStack> dispatcher) {
        register(dispatcher, source -> true);
    }

    private void register(CommandDispatcher<CommandSourceStack> dispatcher, Predicate<CommandSourceStack> requirement) {
        dispatcher.register(
            Commands.literal("kubex")
            .requires(requirement::test)
            .then(
                Commands.literal("init")
                .then(Commands.literal("js").executes(context -> init(context.getSource(), KubeXInitMode.JS)))
                .then(Commands.literal("ts").executes(context -> init(context.getSource(), KubeXInitMode.TS)))
            )
            .then(
                Commands.literal("sync")
                .executes(context -> sync(context.getSource()))
            )
            .then(
                Commands.literal("build")
                .executes(context -> build(context.getSource()))
            )
            .then(
                Commands.literal("debug")
                .executes(context -> toggleDebug(context.getSource()))
                .then(Commands.literal("on").executes(context -> setDebug(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setDebug(context.getSource(), false)))
            )
            .then(
                Commands.literal("doctor")
                .then(
                    Commands.argument("position", StringArgumentType.greedyString())
                    .executes(context -> doctorAuto(context.getSource(), StringArgumentType.getString(context, "position")))
                )
                .then(doctorGroup("client_scripts"))
                .then(doctorGroup("server_scripts"))
                .then(doctorGroup("startup_scripts"))
            )
        );
    }

    private LiteralArgumentBuilder<CommandSourceStack> doctorGroup(String scriptGroup) {
        return Commands.literal(scriptGroup)
            .then(
                Commands.argument("line", IntegerArgumentType.integer(1))
                .executes(context -> doctor(context.getSource(), scriptGroup, IntegerArgumentType.getInteger(context, "line"), 1))
                .then(
                    Commands.argument("column", IntegerArgumentType.integer(1))
                    .executes(context -> doctor(
                        context.getSource(),
                        scriptGroup,
                        IntegerArgumentType.getInteger(context, "line"),
                        IntegerArgumentType.getInteger(context, "column")
                    ))
                )
            )
            .then(
                Commands.argument("position", StringArgumentType.greedyString())
                .executes(context -> doctorPosition(context.getSource(), scriptGroup, StringArgumentType.getString(context, "position")))
            );
    }

    private int init(CommandSourceStack source, KubeXInitMode mode) {
        Path gameRoot = resolveGameRoot(source);
        source.sendSuccess(() -> Component.literal("[KubeX] Initializing " + mode.id() + " workspace..."), false);

        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                var result = workspaceManager.initialize(gameRoot, mode);
                if(!result.success()) {
                    source.sendFailure(Component.literal("[KubeX] Init failed: " + result.message()));
                    return;
                }

                source.sendSuccess(
                    () -> Component.literal("[KubeX] Initialized " + mode.id() + " workspace: " + result.workspaceRoot()),
                    false
                );
                source.sendSuccess(() -> Component.literal("[KubeX] Next: edit kubex/src/... then run /kubex build"), false);
            } catch (Exception exception) {
                source.sendFailure(Component.literal("[KubeX] Init failed: " + failureMessage(exception)));
            }
        });

        return 1;
    }

    private int build(CommandSourceStack source) {
        Path gameRoot = resolveGameRoot(source);
        source.sendSuccess(() -> Component.literal("[KubeX] Running workspace build..."), false);

        BACKGROUND_EXECUTOR.execute(() -> {
            try {
                var result = workspaceManager.buildAndSync(
                    gameRoot,
                    message -> source.sendSuccess(() -> Component.literal("[KubeX] " + message), false),
                    reloadBridge.fromSource(source)
                );
                if(!result.success()) {
                    source.sendFailure(Component.literal("[KubeX] Build failed: " + result.message()));
                    return;
                }

                source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
            } catch (Exception exception) {
                source.sendFailure(Component.literal("[KubeX] Build failed: " + failureMessage(exception)));
            }
        });

        return 1;
    }

    private int sync(CommandSourceStack source) {
        try {
            var result = workspaceManager.sync(resolveGameRoot(source));
            if(!result.success()) {
                source.sendFailure(Component.literal("[KubeX] Sync failed: " + result.message()));
                return 0;
            }

            source.sendSuccess(
                () -> Component.literal("[KubeX] " + result.message() + " (" + result.sourceMapCount() + " source maps)"),
                false
            );

            if(!result.publishedFiles().isEmpty() && !reloadBridge.reload(source)) {
                source.sendFailure(Component.literal("[KubeX] Sync completed but /reload did not run"));
            }

            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("[KubeX] Sync failed: " + failureMessage(exception)));
            return 0;
        }
    }

    private int doctor(CommandSourceStack source, String scriptGroup, int line, int column) {
        try {
            var result = doctorManager.lookup(resolveGameRoot(source), scriptGroup, line, column);
            if(!result.success()) {
                source.sendFailure(Component.literal("[KubeX] Doctor failed: " + result.message()));
                return 0;
            }

            source.sendSuccess(
                () -> Component.literal("[KubeX] " + scriptGroup + " " + line + ":" + column + " -> " + formatDoctorResult(result)),
                false
            );
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: " + failureMessage(exception)));
            return 0;
        }
    }

    private int toggleDebug(CommandSourceStack source) {
        try {
            boolean enabled = debugManager.toggle(resolveGameRoot(source));
            source.sendSuccess(() -> Component.literal("[KubeX] Debug mode " + (enabled ? "enabled" : "disabled")), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("[KubeX] Debug mode failed: " + failureMessage(exception)));
            return 0;
        }
    }

    private int setDebug(CommandSourceStack source, boolean enabled) {
        try {
            debugManager.setEnabled(resolveGameRoot(source), enabled);
            source.sendSuccess(() -> Component.literal("[KubeX] Debug mode " + (enabled ? "enabled" : "disabled")), false);
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("[KubeX] Debug mode failed: " + failureMessage(exception)));
            return 0;
        }
    }

    private int doctorAuto(CommandSourceStack source, String position) {
        try {
            Path gameRoot = resolveGameRoot(source);
            ParsedDoctorPosition parsed = parseDoctorPosition(position);
            if(parsed == null) {
                source.sendFailure(Component.literal("[KubeX] Doctor failed: invalid position '" + position + "'"));
                return 0;
            }

            var result = doctorManager.lookupAny(gameRoot, position, parsed.line(), parsed.column());
            if(!result.success()) {
                source.sendFailure(Component.literal("[KubeX] Doctor failed: " + result.message()));
                return 0;
            }

            source.sendSuccess(
                () -> Component.literal("[KubeX] auto " + parsed.line() + ":" + parsed.column() + " -> " + formatDoctorResult(result)),
                false
            );
            return 1;
        } catch (Exception exception) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: " + failureMessage(exception)));
            return 0;
        }
    }

    private int doctorPosition(CommandSourceStack source, String scriptGroup, String position) {
        ParsedDoctorPosition parsed = parseDoctorPosition(position);
        if(parsed == null) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: invalid position '" + position + "'"));
            return 0;
        }

        return doctor(source, scriptGroup, parsed.line(), parsed.column());
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

    private Path resolveGameRoot(CommandSourceStack source) {
        try {
            return source.getServer().getServerDirectory().toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        }
    }

    private String formatDoctorResult(com.ourgram.kubex.sourcemap.KubeXSourceMapLookupResult result) {
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
