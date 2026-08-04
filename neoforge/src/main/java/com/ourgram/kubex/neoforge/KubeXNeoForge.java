package com.ourgram.kubex.neoforge;

import com.ourgram.kubex.KubeXDebugManager;
import com.ourgram.kubex.KubeXDoctorManager;
import com.ourgram.kubex.KubeXWorkspaceManager;
import com.ourgram.kubex.command.KubeXCommandService;
import com.ourgram.kubex.neoforge.cmd.KubeXCommands;
import com.ourgram.kubex.neoforge.cmd.KubeXReloadBridge;
import com.ourgram.kubex.neoforge.config.KubeXNeoForgeConfig;
import com.ourgram.kubex.workspace.KubeXDebugModeService;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(KubeXNeoForge.MOD_ID)
public final class KubeXNeoForge {
    public static final String MOD_ID = "kubex";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    public static ModContainer MOD_CONTAINER;

    private final KubeXWorkspaceManager workspaceManager;
    private final KubeXCommands commands;
    private final KubeXReloadBridge reloadBridge;

    public KubeXNeoForge(ModContainer modContainer) {
        MOD_CONTAINER = modContainer;
        modContainer.registerConfig(ModConfig.Type.COMMON, KubeXNeoForgeConfig.SPEC);
        KubeXDebugModeService debugModeService = new KubeXDebugModeService();
        this.workspaceManager = new KubeXWorkspaceManager(debugModeService);
        this.reloadBridge = new KubeXReloadBridge();
        this.commands = new KubeXCommands(new KubeXCommandService(
            workspaceManager,
            new KubeXDebugManager(debugModeService),
            new KubeXDoctorManager()
        ));
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        LOGGER.info("KubeX NeoForge mod initialized.");
    }

    private void registerCommands(RegisterCommandsEvent event) {
        commands.register(event.getDispatcher());
    }

    private void registerClientCommands(RegisterClientCommandsEvent event) {
        commands.registerClient(event.getDispatcher());
    }

    private void onServerStarted(ServerStartedEvent event) {
        if(!KubeXNeoForgeConfig.rebuildOnGameStart()) return;

        try {
            var result = workspaceManager.buildAndSync(
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
