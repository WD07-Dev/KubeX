package com.ourgram.kubex;

import java.nio.file.Path;
import com.ourgram.kubex.sourcemap.KubeXSourceMapLookupResult;
import com.ourgram.kubex.sourcemap.KubeXSourceMapService;

public final class KubeXDoctorManager {
    private final KubeXSourceMapService sourceMapService;

    public KubeXDoctorManager() {
        this.sourceMapService = new KubeXSourceMapService();
    }

    public KubeXSourceMapLookupResult lookup(Path gameRoot, String scriptGroup, int generatedLine, int generatedColumn) {
        return sourceMapService.lookup(gameRoot, scriptGroup, generatedLine, generatedColumn);
    }

    public KubeXSourceMapLookupResult lookupAny(Path gameRoot, String positionHint, int generatedLine, int generatedColumn) {
        return sourceMapService.lookupAny(gameRoot, positionHint, generatedLine, generatedColumn);
    }
}
