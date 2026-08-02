# KubeX

![KubeX Logo](image/kubex.logo.png)

> A dedicated workspace mod for KubeJS development

KubeX is a KubeJS development helper mod that lets you work in a separate `kubex/` workspace instead of writing everything directly inside `kubejs/`.

The idea is simple:

- write source code in `kubex/src`
- build into `kubex/output`
- let KubeX post-process the output for Rhino / KubeJS
- publish the final result into `kubejs/`
- trace errors back to the original source

KubeX is not meant to replace ProbeJS. It is designed to work with ProbeJS-generated development assets such as `.probe`, `.vscode`, and `jsconfig.json`, then build a better workflow on top of them.

---

## Why KubeX?

Traditional KubeJS development usually has a few recurring problems:

- scripts are managed directly inside `kubejs/`
- large projects become hard to organize
- bundled JavaScript is difficult to debug
- Rhino does not handle modern JavaScript syntax well
- callback errors can be hard to find
- generated line numbers do not match the original source

KubeX addresses these problems with a dedicated workspace, build pipeline, sync flow, source maps, and runtime debugging support.

---

## Current Direction

KubeX currently targets NeoForge first.

The flow looks like this:

```text
ProbeJS
  ↓
development assets (.probe, .vscode, jsconfig.json)
  ↓
KubeX
  ↓
develop inside kubex/src
  ↓
build with esbuild
  ↓
KubeX post-processing
  - Rhino compatibility lowering
  - debug wrapping
  - source map preservation
  ↓
sync into kubejs
  ↓
Minecraft / KubeJS runtime
```

---

## Features

- dedicated `kubex/` workspace
- `js` or `ts` project initialization
- automatic `esbuild.config.mjs` and `package.json` generation
- sync `output/*.js` and `*.js.map` into `kubejs/`
- Rhino-friendly post-processing
- debug mode for callback-heavy code
- source map based source lookup
- `/kubex doctor` for mapping generated lines back to original source
- `/kubex build` to run `npm install` and `npm run build` automatically

---

## Workspace Layout

Running `/kubex init js` or `/kubex init ts` creates this structure:

```text
kubex/
├── .probe/
├── .vscode/
├── esbuild.config.mjs
├── output/
├── package.json
└── src/
    ├── assets/
    ├── client_scripts/
    │   ├── jsconfig.json
    │   └── main.js or main.ts
    ├── config/
    ├── data/
    ├── server_scripts/
    │   ├── jsconfig.json
    │   └── main.js or main.ts
    └── startup_scripts/
        ├── jsconfig.json
        └── main.js or main.ts
```

If ProbeJS has already generated `.probe`, `.vscode`, or `jsconfig.json`, KubeX can mirror those into the workspace so the development setup stays compatible.

---

## Installation

Recommended combination at the moment:

- Minecraft `1.21.1`
- NeoForge `21.1.x`
- KubeJS `2101.7.2-build.368`
- Rhino `2101.2.8-build.91`
- KubeX `0.0.3`

KubeX is primarily a development mod for KubeJS. To use `/kubex` commands and workspace sync features, KubeX needs to be present in the environment where you are developing.

---

## Getting Started

### 1. Initialize a workspace

For JavaScript:

```mcfunction
/kubex init js
```

For TypeScript:

```mcfunction
/kubex init ts
```

### 2. Open the workspace

It is recommended to open the `kubex/` folder in your editor instead of the game root.

### 3. Write code

For example:

```text
kubex/src/server_scripts/main.ts
```

or

```text
kubex/src/server_scripts/main.js
```

### 4. Build

In-game:

```mcfunction
/kubex build
```

KubeX will automatically:

- run `npm install` if `node_modules` is missing
- run `npm run build`
- inspect the generated files in `kubex/output`
- sync them into `kubejs/`
- run `/reload` when possible

If Node.js or npm is not available, KubeX will print a message telling you to install it.

---

## Manual Build

You can also build from the workspace directly:

```bash
cd kubex
npm install
npm run build
```

This usually produces:

```text
kubex/output/client_scripts.js
kubex/output/server_scripts.js
kubex/output/startup_scripts.js
```

Then run:

```mcfunction
/kubex sync
```

---

## How Sync Works

`/kubex sync` reads files from `kubex/output` and publishes them into `kubejs/`.

The flow is roughly:

```text
kubex/output/*.js
  ↓
KubeX compiler/post-process
  ↓
kubejs/client_scripts/main.js
kubejs/server_scripts/main.js
kubejs/startup_scripts/main.js
```

During this step, KubeX:

- lowers Rhino-problematic patterns
- injects debug wrapping when needed
- copies `.js.map` files
- preserves source map lookup support

---

## Debug Mode

Debug mode is meant to make callback-related failures easier to track down.

```mcfunction
/kubex debug
```

or

```mcfunction
/kubex debug on
/kubex debug off
```

When enabled, KubeX attempts to wrap selected execution points so exceptions produce more useful source information.

This is especially useful for:

- GUI button callbacks
- event callbacks
- functions that become hard to trace after bundling

---

## Doctor

If a bundled error looks like this:

```text
server_scripts/main.js:412
```

KubeX can trace it back to the original source:

```mcfunction
/kubex doctor server_scripts 412 1
```

or

```mcfunction
/kubex doctor "main.js:412:1"
```

When available, the result looks like:

```text
server_scripts/main.ts:37:19
const item = array.at(-1)
                  ^
```

This lets you debug the code you actually wrote instead of the bundled output.

---

## Relationship with ProbeJS

KubeX is not a ProbeJS competitor.

ProbeJS is responsible for things like:

- typing generation
- development metadata
- `.probe`
- `.vscode`
- `jsconfig.json`

KubeX is responsible for things like:

- workspace management
- build result sync
- Rhino compatibility post-processing
- debug mode
- source map tracing
- development commands

In short:

```text
ProbeJS = development assets
KubeX   = development workflow
```

---

## Commands

Initialize:

```mcfunction
/kubex init js
/kubex init ts
```

Build:

```mcfunction
/kubex build
```

Sync:

```mcfunction
/kubex sync
```

Debug:

```mcfunction
/kubex debug
/kubex debug on
/kubex debug off
```

Source lookup:

```mcfunction
/kubex doctor <group> <line> [column]
/kubex doctor "main.js:1450:1"
```

Current supported groups:

- `client_scripts`
- `server_scripts`
- `startup_scripts`

---

## Current Limitations

KubeX is still under active development, and a few limitations are worth noting:

- the current build toolchain is Node.js-based
- type generation itself is still handled by ProbeJS
- not every modern JavaScript feature is guaranteed yet
- Rhino compatibility lowering is still being expanded
- NeoForge is the primary target right now

---

## Building the Mod

To build the mod itself from the project root:

```bash
./gradlew :neoforge:build
```

Or for a quick compile check:

```bash
./gradlew :core:compileJava :neoforge:compileJava
```

---

## Vision

The long-term goal is simple:

> Make KubeX the first tool KubeJS developers install.

The intended workflow is:

```text
ProbeJS
  ↓
KubeX Workspace
  ↓
Build
  ↓
Rhino-safe Output
  ↓
Reload
  ↓
Debug
```

KubeX aims to become a single, practical workflow layer for KubeJS development.
