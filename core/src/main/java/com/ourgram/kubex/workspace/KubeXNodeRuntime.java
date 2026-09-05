package com.ourgram.kubex.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class KubeXNodeRuntime {
    public record NpmCommand(String executable, List<Path> binDirectories) {
        public void configure(ProcessBuilder processBuilder) {
            Map<String, String> environment = processBuilder.environment();
            String pathKey = pathKey(environment);
            String previousPath = environment.getOrDefault(pathKey, "");
            String directories = binDirectories.stream()
            .map(Path::toString)
            .reduce((left, right) -> left + java.io.File.pathSeparator + right)
            .orElse("");

            if(!directories.isBlank()) {
                environment.put(pathKey, previousPath.isBlank() ? directories : directories + java.io.File.pathSeparator + previousPath);
            }
        }

        private static String pathKey(Map<String, String> environment) {
            for(String key : environment.keySet()) {
                if("PATH".equalsIgnoreCase(key)) return key;
            }
            return "PATH";
        }
    }

    private KubeXNodeRuntime() {}

    public static NpmCommand findNpm() {
        String npmName = isWindows() ? "npm.cmd" : "npm";
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
            Path npm = directory.resolve(npmName);
            if(Files.isRegularFile(npm) && Files.isExecutable(npm)) {
                return new NpmCommand(npm.toString(), List.copyOf(directories));
            }
        }

        return new NpmCommand(npmName, List.copyOf(directories));
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

        for(String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
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