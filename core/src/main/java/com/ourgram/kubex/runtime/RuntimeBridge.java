package com.ourgram.kubex.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

public final class RuntimeBridge {
    private static final RuntimeReporter REPORT_SERVICE = new RuntimeReporter();
    private static final UnhandledRejections CLIENT_REJECTIONS = new UnhandledRejections();
    private static final UnhandledRejections SERVER_REJECTIONS = new UnhandledRejections();
    private static volatile Path gameRoot;

    public static void initialize(Path root) {
        gameRoot = Objects.requireNonNull(root).toAbsolutePath().normalize();
    }

    public static UnhandledRejections.Pending trackUnhandled(String detail, String scriptGroup, int line, int column) {
        String message = report(detail, scriptGroup, line, column, scriptGroup + "/main.js", line, column);
        return ("client_scripts".equals(scriptGroup) ? CLIENT_REJECTIONS : SERVER_REJECTIONS)
            .track("[KubeX] Unhandled Promise rejection\n" + message);
    }

    public static void tickClient(Consumer<String> reporter) {
        CLIENT_REJECTIONS.tick(reporter);
    }

    public static void tickServer(Consumer<String> reporter) {
        SERVER_REJECTIONS.tick(reporter);
    }

    public static String report(Object error, String scriptGroup, int generatedLine, int generatedColumn, String fallbackSourceFile, int fallbackSourceLine, int fallbackSourceColumn) {
        Path root = gameRoot;
        if(root == null) throw new IllegalStateException("RuntimeBridge has not been initialized");
        return REPORT_SERVICE.report(
            root,
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