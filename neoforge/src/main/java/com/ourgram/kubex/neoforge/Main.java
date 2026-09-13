package com.ourgram.kubex.neoforge;

import com.ourgram.kubex.KubeXCore;
import com.ourgram.kubex.command.CommandService;
import com.ourgram.kubex.neoforge.cmd.CommandRegistry;
import com.ourgram.kubex.neoforge.cmd.ReloadBridge;
import com.ourgram.kubex.runtime.RuntimeBridge;
import net.neoforged.fml.loading.FMLPaths;
import com.ourgram.kubex.neoforge.config.Config;
import com.ourgram.kubex.sourcemap.SourceMapService;
import com.ourgram.kubex.workspace.DebugMode;
import com.ourgram.kubex.workspace.Workspace;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(KubeXCore.MOD_ID)
public final class Main {
    public static final Logger LOGGER = LogManager.getLogger(KubeXCore.MOD_ID);
    public static ModContainer MOD_CONTAINER;

    private final Workspace workspace;
    private final CommandRegistry commands;
    private final ReloadBridge reloadBridge;

    public Main(ModContainer modContainer) {
        MOD_CONTAINER = modContainer;
        RuntimeBridge.initialize(FMLPaths.GAMEDIR.get());
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        DebugMode debugMode = new DebugMode();
        this.workspace = new Workspace(debugMode);
        this.reloadBridge = new ReloadBridge();
        this.commands = new CommandRegistry(new CommandService(
            workspace,
            debugMode,
            new SourceMapService()
        ));
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> RuntimeBridge.tickClient(LOGGER::error));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> RuntimeBridge.tickServer(LOGGER::error));
        LOGGER.info("KubeX NeoForge mod initialized.");
    }

    private void registerCommands(RegisterCommandsEvent event) {
        commands.register(event.getDispatcher());
    }

    private void registerClientCommands(RegisterClientCommandsEvent event) {
        commands.registerClient(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        if(!Config.rebuildOnGameStart()) return;
        try {
            var result = workspace.buildAndSync(
                event.getServer().getServerDirectory(),
                ignored -> {},
                reloadBridge::reload
            );
            if(result.success()) {
                LOGGER.info("Startup rebuild completed: {}", result.message());
                return;
            }
            LOGGER.warn("Startup rebuild failed: {}", result.message());
        } catch (Exception exception) {
            LOGGER.error("Startup rebuild failed", exception);
        }
    }
}