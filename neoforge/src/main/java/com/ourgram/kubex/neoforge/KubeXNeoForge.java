package com.ourgram.kubex.neoforge;

import com.ourgram.kubex.app.KubeXDebugManager;
import com.ourgram.kubex.app.KubeXDoctorManager;
import com.ourgram.kubex.app.KubeXWorkspaceManager;
import com.ourgram.kubex.workspace.KubeXDebugModeService;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
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

    public KubeXNeoForge(ModContainer modContainer) {
        MOD_CONTAINER = modContainer;
        KubeXDebugModeService debugModeService = new KubeXDebugModeService();
        this.workspaceManager = new KubeXWorkspaceManager(debugModeService);
        this.commands = new KubeXCommands(
            workspaceManager,
            new KubeXDebugManager(debugModeService),
            new KubeXDoctorManager()
        );
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
        try {
            var result = workspaceManager.sync(event.getServer().getServerDirectory());
            if(result.success()) {
                LOGGER.info("Auto synced KubeX workspace: {}", result.message());
                for(var publishedFile : result.publishedFiles()) {
                    LOGGER.info("Published {}", publishedFile);
                }
                return;
            }

            LOGGER.warn("Auto sync failed: {}", result.message());
        } catch (Exception exception) {
            LOGGER.error("Auto sync failed", exception);
        }
    }
}
