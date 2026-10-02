package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopScopeTest {
    @Test
    void leavesLexicalLoopBindingsUnchanged() {
        String output = new ScriptCompiler().compile("server_scripts.js", "for(let i = 0; i < 1; i++) {};").outputSource();
        assertTrue(output.contains("for(let i = 0; i < 1; i++)"));
        assertFalse(output.contains("var i;"));
    }

    @Test
    void keepsBabelArrayCopyResultOutsideTheLoop() {
        assertEquals("a,b|0", evaluate("""
        function _arrayLikeToArray(r, a) {
            (null == a || a > r.length) && (a = r.length);
            for (var e = 0, n = Array(a); e < a; e++) n[e] = r[e];
            return n;
        }
        var copied = _arrayLikeToArray(['a', 'b']);
        copied[0] + ',' + copied[1] + '|' + _arrayLikeToArray([]).length;
        """));
    }

    @Test
    void preservesLabelsAndInitializerSideEffects() {
        assertEquals("3:1", evaluate("""
        function run() {
            var calls = 0;
            outer: for(var i = (calls++, 0), n = []; i < 3; i++) {
                if(i < 2) continue outer;
                n.push(i);
            }
            return i + ':' + calls;
        }
        run();
        """));
    }

    @Test
    void keepsNestedFunctionBindingsSeparateAndPreservesDirectives() {
        String source = """
        function outer() {
            'use strict';
            for(var n = 2; false;) {}
            function inner() { for(var n = 5; false;) {} return n; }
            return n + ':' + inner();
        }
        outer();
        """;
        assertEquals("2:5", evaluate(source));
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        assertTrue(output.indexOf("'use strict'") < output.indexOf("var n;"));
    }

    private String evaluate(String source) {
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        var context = new ContextFactory().enter();
        return context.toString(context.evaluateString(context.initStandardObjects(), output, "test.js", 1, null));
    }
}