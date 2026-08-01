package com.ourgram.kubex.neoforge;

import net.minecraft.commands.CommandSourceStack;
import java.lang.reflect.Method;

public final class KubeXReloadBridge {
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
            if(minecraft == null) {
                return false;
            }

            Method getConnection = minecraftClass.getMethod("getConnection");
            Object connection = getConnection.invoke(minecraft);
            if(connection == null) {
                return false;
            }

            Method sendCommand = connection.getClass().getMethod("sendCommand", String.class);
            sendCommand.invoke(connection, "reload");
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
