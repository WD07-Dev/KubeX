package com.ourgram.kubex.neoforge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import com.ourgram.kubex.workspace.KubeXWorkspaceReloadService;
import net.minecraft.commands.CommandSourceStack;

public final class KubeXReloadBridge {
    public KubeXWorkspaceReloadService fromSource(CommandSourceStack source) {
        return () -> reload(source);
    }

    public boolean reload() {
        try {
            Object server = currentServer();
            if(server != null) {
                Method createCommandSourceStack = server.getClass().getMethod("createCommandSourceStack");
                Object source = createCommandSourceStack.invoke(server);
                if(source instanceof CommandSourceStack commandSourceStack) {
                    return reload(commandSourceStack);
                }
            }
        } catch (Exception ignored) {
        }

        return sendClientReloadCommand();
    }

    public boolean reload(CommandSourceStack source) {
        try {
            source.getServer().getCommands().performPrefixedCommand(source, "reload");
            return true;
        } catch (RuntimeException ignored) {
            return sendClientReloadCommand();
        }
    }

    private boolean sendClientReloadCommand() {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Method getInstance = minecraftClass.getMethod("getInstance");
            Object minecraft = getInstance.invoke(null);
            if(minecraft == null) return false;

            Method getConnection = minecraftClass.getMethod("getConnection");
            Object connection = getConnection.invoke(minecraft);
            if(connection == null) return false;

            Method sendCommand = connection.getClass().getMethod("sendCommand", String.class);
            sendCommand.invoke(connection, "reload");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object currentServer() {
        try {
            Class<?> hooksClass = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks");
            Method currentServerMethod = hooksClass.getMethod("getCurrentServer");
            return currentServerMethod.invoke(null);
        } catch (Exception ignored) {
        }

        try {
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            Field serverField = minecraftServerClass.getDeclaredField("SERVER");
            serverField.setAccessible(true);
            return serverField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
