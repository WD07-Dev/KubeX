package com.ourgram.kubex.workspace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.function.Consumer;
import com.ourgram.kubex.KubeXCore;

public final class KubeXWorkspaceBuildService {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final int OUTPUT_LIMIT = 1200;

    public KubeXWorkspaceBuildResult build(Path gameRoot) {
        return build(gameRoot, ignored -> {});
    }

    public KubeXWorkspaceBuildResult build(Path gameRoot, Consumer<String> progressListener) {
        Path workspaceRoot = KubeXCore.paths(gameRoot).workspace();
        Path packageJson = workspaceRoot.resolve("package.json");
        Path nodeModules = workspaceRoot.resolve("node_modules");

        try {
            if(!Files.isDirectory(workspaceRoot)) {
                return new KubeXWorkspaceBuildResult(false, workspaceRoot, "KubeX workspace was not found");
            }

            if(!Files.exists(packageJson)) {
                return new KubeXWorkspaceBuildResult(false, workspaceRoot, "package.json was not found in kubex");
            }

            String npm = isWindows() ? "npm.cmd" : "npm";
            boolean installedDependencies = false;

            if(!Files.isDirectory(nodeModules)) {
                progressListener.accept("Installing dependencies with npm install...");
                run(workspaceRoot, progressListener, npm, "install");
                progressListener.accept("npm install completed");
                installedDependencies = true;
            }else {
                progressListener.accept("Skipping npm install (node_modules already exists)");
            }

            progressListener.accept("Running npm run build...");
            run(workspaceRoot, progressListener, npm, "run", "build");
            progressListener.accept("npm run build completed");

            return new KubeXWorkspaceBuildResult(
                true,
                workspaceRoot,
                installedDependencies ? "Installed dependencies and completed build" : "Build completed"
            );
        } catch (IOException exception) {
            if(isMissingCommand(exception)) {
                return new KubeXWorkspaceBuildResult(false, workspaceRoot, "Node.js를 설치해주세요 (npm/node was not found)");
            }

            return new KubeXWorkspaceBuildResult(false, workspaceRoot, failureMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new KubeXWorkspaceBuildResult(false, workspaceRoot, "Build interrupted");
        }
    }

    private void run(Path workspaceRoot, Consumer<String> progressListener, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
            .directory(workspaceRoot.toFile())
            .redirectErrorStream(true)
            .start();

        Deque<String> outputLines = new ArrayDeque<>();
        Thread readerThread = new Thread(() -> pumpOutput(process, progressListener, outputLines), "kubex-build-output");
        readerThread.setDaemon(true);
        readerThread.start();

        boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        readerThread.join(1000L);

        String output = String.join(System.lineSeparator(), outputLines).trim();
        if(!finished) {
            process.destroyForcibly();
            throw new IOException(command[0] + " timed out");
        }

        if(process.exitValue() != 0) {
            throw new IOException(composeCommandFailure(command, output));
        }
    }

    private void pumpOutput(Process process, Consumer<String> progressListener, Deque<String> outputLines) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if(trimmed.isBlank()) continue;

                synchronized (outputLines) {
                    outputLines.addLast(trimmed);
                    while(outputLines.size() > 40) {
                        outputLines.removeFirst();
                    }
                }

                if(shouldReport(trimmed)) {
                    progressListener.accept(trimOutput(trimmed));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private boolean shouldReport(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if(lower.startsWith("npm notice")) return false;
        if(lower.startsWith("npm fund")) return false;
        if(lower.startsWith("> kubex-workspace@")) return false;
        if(lower.equals("> node ./esbuild.config.mjs")) return false;
        if(lower.startsWith("up to date")) return true;
        if(lower.startsWith("added ")) return true;
        if(lower.startsWith("removed ")) return true;
        if(lower.startsWith("changed ")) return true;
        if(lower.startsWith("audited ")) return true;
        if(lower.contains("warning")) return true;
        if(lower.contains("error")) return true;
        if(lower.contains("building")) return true;
        if(lower.contains("finished")) return true;
        if(lower.contains("done")) return true;
        if(lower.endsWith(".js")) return true;
        if(line.startsWith("output/")) return true;
        return true;
    }

    private String composeCommandFailure(String[] command, String output) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.join(" ", command)).append(" failed");
        if(!output.isBlank()) {
            builder.append(": ").append(trimOutput(output));
        }
        return builder.toString();
    }

    private String trimOutput(String output) {
        String compact = output.replace("\r", " ").replace('\n', ' ').trim();
        if(compact.length() <= OUTPUT_LIMIT) return compact;
        return compact.substring(0, OUTPUT_LIMIT) + "...";
    }

    private boolean isMissingCommand(IOException exception) {
        String message = exception.getMessage();
        if(message == null) return false;

        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("cannot run program")
            || lower.contains("createprocess error=2")
            || lower.contains("error=2")
            || lower.contains("no such file or directory");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String failureMessage(Exception exception) {
        Throwable current = exception;
        while(current != null) {
            String message = current.getMessage();
            if(message != null && !message.isBlank()) return message;
            current = current.getCause();
        }

        return exception.getClass().getSimpleName();
    }
}
