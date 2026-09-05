package com.ourgram.kubex.neoforge.cmd;

import com.ourgram.kubex.runtime.RuntimeReporter;
import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;

public final class RuntimeBridge {
    private static final RuntimeReporter REPORT_SERVICE = new RuntimeReporter();

    public static String report(Object error, String scriptGroup, int generatedLine, int generatedColumn, String fallbackSourceFile, int fallbackSourceLine, int fallbackSourceColumn) {
        Path gameRoot = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        return REPORT_SERVICE.report(
            gameRoot,
            error,
            scriptGroup,
            generatedLine,
            generatedColumn,
            fallbackSourceFile,
            fallbackSourceLine,
            fallbackSourceColumn
        );
    }
}