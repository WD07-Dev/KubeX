package com.ourgram.kubex.neoforge.export.template;

import java.nio.file.Files;
import java.nio.file.Path;
import dev.latvian.mods.kubejs.plugin.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.ScriptManager;
import net.neoforged.fml.ModList;

public final class ScriptPlugin implements KubeJSPlugin {
    private static final String MOD_ID = "__KUBEX_MOD_ID__";

    @Override
    public void beforeScriptsLoaded(ScriptManager manager) {
        var modFile = ModList.get().getModFileById(MOD_ID);
        if(modFile == null) return;

        Path scripts = modFile.getFile().findResource("kubex", "scripts", manager.scriptType.name, MOD_ID);
        if(Files.isDirectory(scripts)) {
            manager.loadPackFromDirectory(scripts, MOD_ID, false);
        }
    }
}