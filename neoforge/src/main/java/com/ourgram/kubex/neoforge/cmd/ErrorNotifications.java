package com.ourgram.kubex.neoforge.cmd;

import com.ourgram.kubex.neoforge.Main;
import com.ourgram.kubex.runtime.ErrorNotification;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class ErrorNotifications {
    private static final ErrorNotification SERVER = new ErrorNotification();

    public static void server(MinecraftServer server, String report) {
        Main.LOGGER.error(report);
        var recipients = server.getPlayerList().getPlayers().stream()
        .filter(player -> server.isSingleplayerOwner(player.getGameProfile()) || player.createCommandSourceStack().hasPermission(2)).toList();
        if(recipients.isEmpty()) return;
        String message = SERVER.next(report, System.nanoTime());
        if(message == null) return;
        Component text = Component.literal(message).withStyle(ChatFormatting.RED);
        for(var player : recipients) player.sendSystemMessage(text);
    }

    public static final class Client {
        private static final ErrorNotification NOTIFICATIONS = new ErrorNotification();

        public static void report(String report) {
            Main.LOGGER.error(report);
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if(minecraft.player == null) return;
            String message = NOTIFICATIONS.next(report, System.nanoTime());
            if(message != null) minecraft.gui.getChat().addMessage(Component.literal(message).withStyle(ChatFormatting.RED));
        }
    }
}