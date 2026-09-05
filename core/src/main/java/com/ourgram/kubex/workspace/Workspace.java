package com.ourgram.kubex.workspace;

import java.nio.file.Path;
import java.util.function.Consumer;
import com.ourgram.kubex.compiler.ScriptCompiler;

public final class Workspace {
    private final Initializer initializer = new Initializer();
    private final Builder builder = new Builder();
    private final Synchronizer synchronizer;

    public Workspace(DebugMode debugModeService) {
        synchronizer = new Synchronizer(new ScriptCompiler(), debugModeService);
    }

    public InitResult initialize(Path gameRoot, InitMode mode) {
        return initializer.initialize(gameRoot, mode);
    }

    public SyncResult sync(Path gameRoot) {
        return synchronizer.sync(gameRoot);
    }

    public BuildAndSyncResult buildAndSync(Path gameRoot, Consumer<String> progressListener, ReloadService reloadService) {
        BuildResult buildResult = builder.build(gameRoot, progressListener);
        if(!buildResult.success()) {
            return new BuildAndSyncResult(false, buildResult.workspaceRoot(), buildResult, null, false, false, buildResult.message());
        }

        SyncResult syncResult = synchronizer.sync(gameRoot);
        if(!syncResult.success()) {
            return new BuildAndSyncResult(false, syncResult.workspaceRoot(), buildResult, syncResult, false, false, syncResult.message());
        }

        if(reloadService != null && !syncResult.publishedFiles().isEmpty() && !reloadService.reload()) {
            return new BuildAndSyncResult(false, syncResult.workspaceRoot(), buildResult, syncResult, true, false, "Sync completed but reload did not run");
        }

        boolean reloaded = reloadService != null && !syncResult.publishedFiles().isEmpty();
        String message = syncResult.publishedFiles().isEmpty()
        ? buildResult.message() + " | " + syncResult.message()
        : buildResult.message() + " | " + syncResult.message() + (reloaded ? " | Reload completed" : "");
        return new BuildAndSyncResult(true, syncResult.workspaceRoot(), buildResult, syncResult, reloaded, reloaded, message);
    }
}