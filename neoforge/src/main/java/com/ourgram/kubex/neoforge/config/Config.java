package com.ourgram.kubex.neoforge.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public static ModConfigSpec SPEC;
    public static ModConfigValue VALUES;
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        VALUES = new ModConfigValue(builder);
        SPEC = builder.build();
    }
    private Config() {}

    public static final boolean DEFAULT_REBUILD_ON_GAME_START = false;
    public static final class ModConfigValue {
        public ModConfigSpec.BooleanValue rebuildOnGameStart;
        
        private ModConfigValue(ModConfigSpec.Builder builder) {
            builder.push("general"); // general

            rebuildOnGameStart = builder
            .comment("Rebuild, sync, and reload the KubeX workspace when the game server starts.")
            .define("rebuildOnGameStart", DEFAULT_REBUILD_ON_GAME_START);
            
            builder.pop(); // close general
        }
    }

    public static boolean rebuildOnGameStart() {
        try {
            return VALUES.rebuildOnGameStart.get();
        } catch (IllegalStateException ignored) {
            return DEFAULT_REBUILD_ON_GAME_START;
        }
    }
}