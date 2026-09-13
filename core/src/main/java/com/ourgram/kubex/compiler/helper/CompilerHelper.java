package com.ourgram.kubex.compiler.helper;

import dev.latvian.mods.rhino.ast.AstRoot;
import java.util.function.IntFunction;

public interface CompilerHelper {
    enum Phase {
        BEFORE_PACKAGE_REMOVAL,
        AFTER_PACKAGE_REMOVAL
    }

    default Phase phase() {
        return Phase.AFTER_PACKAGE_REMOVAL;
    }

    String rewrite(AstRoot root, String source);

    default String rewrite(AstRoot root, String source, IntFunction<String> location) {
        return rewrite(root, source);
    }
}