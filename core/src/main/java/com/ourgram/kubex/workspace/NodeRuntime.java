package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.regex.Pattern;

public final class NodeRuntime {
    public record Command(String executable, List<Path> binDirectories, boolean flatpakHost) {
        public Command(String executable, List<Path> binDirectories) {
            this(executable, binDirectories, false);
        }

        public void configure(ProcessBuilder processBuilder) throws IOException {
            if(flatpakHost) {
                Path directory = processBuilder.directory() == null ? null : processBuilder.directory().toPath();
                verifyHost(executable, directory);
                processBuilder.command(hostCommand(processBuilder.command(), directory));
                return;
            }
            Map<String, String> environment = processBuilder.environment();
            String pathKey = pathKey(environment);
            String previousPath = environment.getOrDefault(pathKey, "");
            String directories = binDirectories.stream()
            .map(Path::toString).reduce((left, right) -> left + File.pathSeparator + right).orElse("");

            if(!directories.isBlank()) {
                environment.put(pathKey, previousPath.isBlank() ? directories : directories + File.pathSeparator + previousPath);
            }
        }

        private static String pathKey(Map<String, String> environment) {
            for(String key : environment.keySet()) {
                if("PATH".equalsIgnoreCase(key)) return key;
            }
            return "PATH";
        }
    }

    private NodeRuntime() {}

    public static Command findNpm() {
        return find(isWindows() ? "npm.cmd" : "npm");
    }

    public static Command findNode() {
        return find(isWindows() ? "node.exe" : "node");
    }

    private static Command find(String executable) {
        boolean flatpak = isLinux() && (Files.exists(Path.of("/.flatpak-info")) || System.getenv("FLATPAK_ID") != null);
        Set<Path> directories = new LinkedHashSet<>();
        addEnvironmentDirectory(directories, "NVM_BIN");
        addEnvironmentDirectory(directories, "FNM_MULTISHELL_PATH");
        addEnvironmentDirectory(directories, "NPM_BIN");
        addEnvironmentDirectory(directories, "VOLTA_HOME", "bin");
        addPathDirectories(directories, System.getenv("PATH"));
        addCommonDirectories(directories);
        addVersionManagerDirectories(directories, homeDirectory().resolve(".nvm").resolve("versions").resolve("node"), "bin");
        addFnmDirectories(directories);

        for(Path directory : directories) {
            Path command = directory.resolve(executable);
            if(Files.isRegularFile(command) && Files.isExecutable(command)) {
                Command candidate = new Command(command.toString(), List.copyOf(directories));
                if(!flatpak || canRun(candidate)) return candidate;
            }
        }

        Path shellCommand = findWithShell(executable);
        if(shellCommand != null) {
            directories.add(shellCommand.getParent());
            Command candidate = new Command(shellCommand.toString(), List.copyOf(directories));
            if(!flatpak || canRun(candidate)) return candidate;
        }

        return new Command(executable, List.copyOf(directories), flatpak);
    }

    static List<String> hostCommand(List<String> arguments, Path directory) {
        List<String> command = new ArrayList<>(List.of("flatpak-spawn", "--host", "--watch-bus"));
        if(directory != null) command.add("--directory=" + directory.toAbsolutePath().normalize());
        command.addAll(arguments);
        return command;
    }

    private static boolean canRun(Command command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command.executable(), "--version");
            command.configure(builder);
            return probe(builder) == 0;
        } catch(IOException ignored) {
            return false;
        }
    }

    private static void verifyHost(String executable, Path directory) throws IOException {
        int status;
        try {
            status = probe(new ProcessBuilder(hostCommand(List.of(executable, "--version"), directory)));
        } catch(IOException exception) {
            throw new IOException("Flatpak host execution is unavailable. Check flatpak-spawn and the launcher runtime, or use a non-Flatpak launcher.", exception);
        }
        if(status == 127) throw new IOException("Node.js/npm was not found in the host PATH. Install Node.js and npm on the host.");
        if(status != 0) throw new IOException("Could not run Node.js/npm from the host PATH. Check installation and PATH, the launcher's org.freedesktop.Flatpak D-Bus permission, and host access to the workspace directory.");
    }

    private static int probe(ProcessBuilder builder) throws IOException {
        Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            return process.waitFor(3, TimeUnit.SECONDS) ? process.exitValue() : -1;
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Node.js detection interrupted", exception);
        } finally {
            if(process.isAlive()) process.destroyForcibly();
        }
    }

    private static Path findWithShell(String executable) {
        List<String> command = isWindows() ? List.of("where.exe", executable)
        : List.of("sh", "-lc", "NVM_DIR=\"${NVM_DIR:-$HOME/.nvm}\"; [ -s \"$NVM_DIR/nvm.sh\" ] && . \"$NVM_DIR/nvm.sh\" >/dev/null 2>&1; command -v " + executable);

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if(!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }

            for(String line : new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                Path candidate = parseExecutablePath(line);
                if(candidate != null) return candidate;
            }
        } catch(IOException exception) {
            return null;
        } catch(InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static Path parseExecutablePath(String value) {
        String path = value.trim();
        if(path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
        if(path.isBlank()) return null;

        try {
            Path candidate = Path.of(path);
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate) ? candidate : null;
        } catch(RuntimeException ignored) {
            return null;
        }
    }

    private static void addEnvironmentDirectory(Set<Path> directories, String variable, String... children) {
        String value = System.getenv(variable);
        if(value == null || value.isBlank()) return;

        try {
            Path directory = Path.of(value);
            for(String child : children) {
                directory = directory.resolve(child);
            }
            directories.add(directory);
        } catch(RuntimeException ignored) {
        }
    }

    private static void addPathDirectories(Set<Path> directories, String path) {
        if(path == null || path.isBlank()) return;

        for(String entry : path.split(Pattern.quote(File.pathSeparator))) {
            if(entry.isBlank()) continue;
            try {
                directories.add(Path.of(entry));
            } catch(RuntimeException ignored) {
            }
        }
    }

    private static void addCommonDirectories(Set<Path> directories) {
        Path home = homeDirectory();
        directories.add(home.resolve(".volta").resolve("bin"));
        directories.add(home.resolve(".asdf").resolve("shims"));
        directories.add(home.resolve(".mise").resolve("shims"));
        directories.add(home.resolve(".local").resolve("share").resolve("mise").resolve("shims"));

        if(isMac()) {
            directories.add(Path.of("/opt/homebrew/bin"));
            directories.add(Path.of("/usr/local/bin"));
            directories.add(Path.of("/opt/local/bin"));
        }

        if(isLinux()) {
            directories.add(home.resolve(".local").resolve("bin"));
            directories.add(Path.of("/usr/local/bin"));
            directories.add(Path.of("/usr/bin"));
            directories.add(Path.of("/snap/bin"));
            directories.add(Path.of("/home/linuxbrew/.linuxbrew/bin"));
        }
    }

    private static void addFnmDirectories(Set<Path> directories) {
        Path home = homeDirectory();
        addVersionManagerDirectories(
            directories,
            home.resolve(".local").resolve("share").resolve("fnm").resolve("node-versions"),
            "installation",
            "bin"
        );

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if(xdgDataHome == null || xdgDataHome.isBlank()) return;
        try {
            addVersionManagerDirectories(directories, Path.of(xdgDataHome).resolve("fnm").resolve("node-versions"), "installation", "bin");
        } catch(RuntimeException ignored) {
        }
    }

    private static void addVersionManagerDirectories(Set<Path> directories, Path versionsRoot, String... binPath) {
        if(!Files.isDirectory(versionsRoot)) return;

        try (Stream<Path> versions = Files.list(versionsRoot)) {
            versions.filter(Files::isDirectory)
            .sorted(Comparator.reverseOrder())
            .map(version -> resolve(version, binPath))
            .forEach(directories::add);
        } catch(IOException ignored) {
        }
    }

    private static Path resolve(Path root, String... children) {
        Path path = root;
        for(String child : children) {
            path = path.resolve(child);
        }
        return path;
    }

    private static Path homeDirectory() {
        String home = System.getProperty("user.home", "");
        return home.isBlank() ? Path.of(".").toAbsolutePath().normalize() : Path.of(home);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return name.contains("mac") || name.contains("darwin");
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}