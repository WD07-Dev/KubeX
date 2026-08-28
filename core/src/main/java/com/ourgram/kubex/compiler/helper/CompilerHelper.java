package com.ourgram.kubex.compiler.helper;

import dev.latvian.mods.rhino.ast.AstRoot;

public interface CompilerHelper {
    String rewrite(AstRoot root, String source);
}