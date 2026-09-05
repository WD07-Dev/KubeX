# KubeX

![KubeX Logo](image/kubex.logo.png)

KubeX is a development workspace for KubeJS. Instead of keeping source files, build output, editor files, and generated data all in `kubejs/`, it keeps the project in `kubex/` and publishes only the final result to KubeJS.

NeoForge is the only export target at the moment. KubeX uses the exporter included by the installed platform module, so Fabric and other loaders can be added without changing the workspace or the core compiler.

## What KubeX Does

- Creates a JavaScript or TypeScript workspace with `/kubex init`.
- Runs the project's esbuild configuration with `/kubex build`.
- Rewrites bundled output where Rhino needs older syntax.
- Copies scripts, assets, data, and config into `kubejs/` with `/kubex sync`.
- Keeps source maps usable after KubeX changes generated JavaScript.
- Provides `/kubex doctor` and debug mode for errors in bundled scripts.
- Exports a workspace as a separate KubeJS mod JAR.

KubeX does not replace ProbeJS. ProbeJS still provides typings and editor metadata. When they exist, KubeX brings `.probe`, `.vscode`, and `jsconfig.json` into the workspace.

## Requirements

- Minecraft 1.21.1 with NeoForge
- KubeJS and Rhino
- Node.js only when building a workspace

ProbeJS is optional, but recommended if you want typings and editor completion.

## Quick Start

Create a workspace from inside a KubeJS world or instance:

```mcfunction
/kubex init ts
```

Use `js` instead of `ts` if the initial entry files should be JavaScript. This choice only creates the first `main` files. A JavaScript project can still contain TypeScript files later.

Open `kubex/` in your editor, edit a script such as `kubex/src/server_scripts/main.ts`, then build it:

```mcfunction
/kubex build
```

KubeX installs npm dependencies when `node_modules` is missing, runs `npm run build`, processes the files in `kubex/output`, and synchronizes the result into `kubejs/`.

It looks for npm through normal `PATH` lookup and common Node.js installations on Windows, macOS, and Linux, including nvm, Homebrew, Volta, fnm, asdf, mise, MacPorts, Snap, and Linux package locations. If npm cannot be found, install Node.js normally and restart the game.

## Workspace

`/kubex init` creates the following layout:

```text
kubex/
  .probe/
  .vscode/
  esbuild.config.mjs
  export.properties
  package.json
  output/
  src/
    assets/
    client_scripts/
      jsconfig.json
      main.js or main.ts
    config/
    data/
    server_scripts/
      jsconfig.json
      main.js or main.ts
    startup_scripts/
      jsconfig.json
      main.js or main.ts
```

Running init again is safe. Existing `main.js` and `main.ts` files are left alone. `.probe` is synchronized from the game root, `.vscode` is refreshed from the game root, and existing KubeJS `jsconfig.json` files are copied into the matching script directories. KubeX also adds TypeScript files to their `include` list.

The common workspace files such as `package.json`, `esbuild.config.mjs`, and `export.properties` are created only when missing.

## Build and Sync

You can run the build from the game:

```mcfunction
/kubex build
```

Or run it yourself:

```bash
cd kubex
npm install
npm run build
```

Then publish the output:

```mcfunction
/kubex sync
```

The default esbuild configuration writes these files:

```text
kubex/output/client_scripts.js
kubex/output/server_scripts.js
kubex/output/startup_scripts.js
```

KubeX converts them to Rhino-friendly KubeJS scripts and writes them as `kubejs/<group>/main.js`. It also synchronizes these directories:

```text
kubex/src/assets  -> kubejs/assets
kubex/src/config  -> kubejs/config
kubex/src/data    -> kubejs/data
```

Synchronization removes files that no longer exist in the workspace, so `kubejs/` reflects the published workspace state.

When KubeX has access to the integrated or dedicated server, it reloads after a successful sync. A client-only installation connected to a remote server can update its local files, but cannot reload that remote server.

Build progress is saved to `kubex/.kubex-status.json`. It is intended for editor extensions or other tools that want to show build state.

## Source Maps and Debugging

Bundling changes line numbers, and KubeX may change them again while lowering syntax. KubeX stores the extra line mapping so `doctor` can point back to the source you edited.

For example, if KubeJS reports this:

```text
server_scripts/main.js:412
```

run:

```mcfunction
/kubex doctor server_scripts 412 1
```

or paste the position directly:

```mcfunction
/kubex doctor "main.js:412:1"
```

When the source map is available, the result includes the original file, line, column, and source line.

Debug mode adds exception reporting around supported callbacks. It is useful when a GUI callback or event fails without an obvious KubeJS error.

```mcfunction
/kubex debug on
/kubex debug off
```

KubeX does not add a second wrapper when a callback already has a top-level `try/catch`.

## Rebuild on Startup

Automatic rebuilding is disabled by default. To build, sync, and reload when a game server starts, set this in `config/kubex-common.toml`:

```toml
[general]
rebuildOnGameStart = true
```

This runs Node.js during startup, so it is best used in a development instance rather than a normal player pack.

## Exporting a Mod

`/kubex export` turns the compiled workspace into a separate mod. Build first, fill in `kubex/export.properties`, and export:

```mcfunction
/kubex build
/kubex export
```

The resulting JAR is written here:

```text
kubex/export/<mod.id>-<mod.version>.jar
```

The exported NeoForge mod requires KubeJS, but it does not require KubeX. It contains its own small KubeJS plugin, the processed startup/server/client scripts, and the files under `src/assets` and `src/data`.

`src/config` is not exported because it is per-instance configuration. It is only used by local workspace synchronization.

An `export.properties` file looks like this:

```properties
mod.package=example.kubejs.mod
mod.id=example_kubejs_mod
mod.name=Example KubeJS Mod
mod.version=1.0.0
mod.description=KubeJS scripts exported by KubeX.
mod.authors=YourName
kubejs.version=[2101.7.2,)

# Optional dependency
dependency.jei.version=[19.0,)
dependency.jei.mandatory=false
dependency.jei.ordering=AFTER
dependency.jei.side=CLIENT
```

`mod.package` is the Java package used by the generated KubeJS plugin class. The exported plugin is written as `<mod.package>.Plugin`.

Optional dependencies start with `dependency.<modid>.version`. `mandatory`, `ordering`, and `side` are optional; their defaults are `true`, `AFTER`, and `BOTH`.

Exporters are registered by each platform module in `META-INF/kubex/exporter.properties`. No loader option is needed in `export.properties` because only the exporter for the installed KubeX platform is available at runtime.

## Commands

```mcfunction
/kubex init js
/kubex init ts
/kubex build
/kubex sync
/kubex export
/kubex debug
/kubex debug on
/kubex debug off
/kubex doctor <client_scripts|server_scripts|startup_scripts> <line> [column]
/kubex doctor "main.js:1450:1"
```
