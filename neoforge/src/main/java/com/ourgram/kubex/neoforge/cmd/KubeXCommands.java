package com.ourgram.kubex.neoforge.cmd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.ourgram.kubex.command.KubeXCommandService;
import com.ourgram.kubex.workspace.KubeXInitMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

public final class KubeXCommands {
    private static final String BUILD_STATUS_FILE = ".kubex-build-status.json";
    private static final ExecutorService BACKGROUND_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kubex-worker");
        thread.setDaemon(true);
        return thread;
    });

    private final KubeXCommandService commandService;
    private final KubeXReloadBridge reloadBridge;

    public KubeXCommands(KubeXCommandService commandService) {
        this.commandService = commandService;
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
            ).then(
                Commands.literal("sync")
                .executes(context -> sync(context.getSource()))
            ).then(
                Commands.literal("build")
                .executes(context -> build(context.getSource()))
            ).then(
                Commands.literal("debug")
                .executes(context -> toggleDebug(context.getSource()))
                .then(Commands.literal("on").executes(context -> setDebug(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> setDebug(context.getSource(), false)))
            ).then(
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
        ).then(
            Commands.argument("position", StringArgumentType.greedyString())
            .executes(context -> doctorPosition(context.getSource(), scriptGroup, StringArgumentType.getString(context, "position")))
        );
    }

    private int init(CommandSourceStack source, KubeXInitMode mode) {
        Path gameRoot = resolveGameRoot(source);
        source.sendSuccess(() -> Component.literal("[KubeX] Initializing " + mode.id() + " workspace..."), false);

        BACKGROUND_EXECUTOR.execute(() -> {
            var result = commandService.initialize(gameRoot, mode);
            if(!result.success()) {
                source.sendFailure(Component.literal("[KubeX] Init failed: " + result.message()));
                return;
            }

            source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
            source.sendSuccess(() -> Component.literal("[KubeX] Next: edit kubex/src/... then run /kubex build"), false);
        });

        return 1;
    }

    private int build(CommandSourceStack source) {
        Path gameRoot = resolveGameRoot(source);
        source.sendSuccess(() -> Component.literal("[KubeX] Running workspace build..."), false);

        BACKGROUND_EXECUTOR.execute(() -> {
            long startedAt = System.currentTimeMillis();
            writeBuildStatus(gameRoot, "running", "KubeX workspace build is running.", startedAt);
            var result = commandService.build(
                gameRoot,
                message -> {
                    writeBuildStatus(gameRoot, "running", message, startedAt);
                    source.sendSuccess(() -> Component.literal("[KubeX] " + message), false);
                },
                reloadBridge.fromSource(source)
            );
            if(!result.success()) {
                writeBuildStatus(gameRoot, "failed", result.message(), startedAt);
                source.sendFailure(Component.literal("[KubeX] Build failed: " + result.message()));
                return;
            }

            writeBuildStatus(gameRoot, "complete", result.message(), startedAt);
            source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        });

        return 1;
    }

    private static void writeBuildStatus(Path gameRoot, String state, String message, long startedAt) {
        Path status = gameRoot.resolve("kubex").resolve(BUILD_STATUS_FILE);
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        String payload = "{\"state\":\"" + state + "\",\"message\":\"" + escaped
        + "\",\"startedAt\":" + startedAt + ",\"updatedAt\":" + System.currentTimeMillis() + "}";
        try {
            Files.writeString(status, payload, StandardCharsets.UTF_8);
        } catch(IOException ignored) {
            // A command build must still run when the optional status file cannot be written.
        }
    }

    private int sync(CommandSourceStack source) {
        var result = commandService.sync(resolveGameRoot(source), reloadBridge.fromSource(source));
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Sync failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private int doctor(CommandSourceStack source, String scriptGroup, int line, int column) {
        var result = commandService.doctor(resolveGameRoot(source), scriptGroup, line, column);
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private int toggleDebug(CommandSourceStack source) {
        var result = commandService.toggleDebug(resolveGameRoot(source));
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Debug mode failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private int setDebug(CommandSourceStack source, boolean enabled) {
        var result = commandService.setDebug(resolveGameRoot(source), enabled);
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Debug mode failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private int doctorAuto(CommandSourceStack source, String position) {
        var result = commandService.doctorAuto(resolveGameRoot(source), position);
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private int doctorPosition(CommandSourceStack source, String scriptGroup, String position) {
        var result = commandService.doctorPosition(resolveGameRoot(source), scriptGroup, position);
        if(!result.success()) {
            source.sendFailure(Component.literal("[KubeX] Doctor failed: " + result.message()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("[KubeX] " + result.message()), false);
        return 1;
    }

    private Path resolveGameRoot(CommandSourceStack source) {
        try {
            return source.getServer().getServerDirectory().toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        }
    }
}