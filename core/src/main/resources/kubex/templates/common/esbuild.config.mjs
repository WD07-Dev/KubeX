import { build } from "esbuild";
import { transformAsync } from "@babel/core";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
var groups = ["client_scripts", "server_scripts", "startup_scripts"];
var externalPackages = [
    "@package",
    "@package/*",
    "@side-only",
    "@side-only/*",
    "@special",
    "@special/*"
];

for (var group of groups) {
    var entry;

    if (existsSync(`src/${group}/main.ts`)) {
        entry = `src/${group}/main.ts`;
    }else if(existsSync(`src/${group}/main.js`)) {
        entry = `src/${group}/main.js`;
    }else {
        console.warn(`No entry file found for ${group}`);
        continue;
    }

    var outfile = `output/${group}.js`;
    var sourceMapFile = `${outfile}.map`;

    await build({
        entryPoints: [entry],
        outfile,
        bundle: true,
        sourcemap: "external",
        format: "iife",
        platform: "neutral",
        target: "es2020",
        external: externalPackages,
        logLevel: "info"
    });

    var builtCode = readFileSync(outfile, "utf8");
    var builtMap = existsSync(sourceMapFile) ? JSON.parse(readFileSync(sourceMapFile, "utf8")) : undefined;
    var transformed = await transformAsync(builtCode, {
        filename: outfile,
        inputSourceMap: builtMap,
        presets: [
            [
                "@babel/preset-env",
                {
                    targets: {
                        ie: "11"
                    },
                    modules: false
                }
            ]
        ],
        babelrc: false,
        configFile: false,
        sourceMaps: true
    });

    if(transformed?.code) {
        writeFileSync(outfile, transformed.code, "utf8");
    }

    if(transformed?.map) {
        writeFileSync(sourceMapFile, JSON.stringify(transformed.map), "utf8");
    }
}