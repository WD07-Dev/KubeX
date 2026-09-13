package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncSourceLocationTest {
    @Test
    void sendsAstLocationToUnhandledReporter() {
        assertEquals("kubex/src/server_scripts/main.js:12:11", evaluate("""
        function* handler(event) {
            event.value = 'sans';
            if(event.value === 'sans') {
                yield _kbWait(0.5);
            }
        }
        handler({});
        """, """
        {"version":3,"sources":["../src/server_scripts/main.js"],"mappings":"AAAA;AASA;AACA;UACU"}
        """));
    }

    @Test
    void mapsMissingAwaitFunctionToItsOriginalToken() {
        String source = """
        function* handler(event) {
            event.value = 'sans';
            if(event.value === 'sans') {
                yield _kbWait(0.5);
            }
        }
        handler({}).catch(function(error) { result = error.__kubexLocation.join(':'); });
        """;
        // Bundle line 4, column 11 maps to source line 12, column 11.
        String map = """
        {"version":3,"sources":["../src/server_scripts/main.js"],"mappings":"AAAA;AASA;AACA;UACU"}
        """;
        assertEquals("kubex/src/server_scripts/main.js:12:11", evaluate(source, map));
    }

    @Test
    void preservesInnerLocationsAcrossNestedAsyncRewrites() {
        String source = """
        function* outer() {
            function* inner() {
                yield _kbWait(0.5);
            }
            yield inner();
        }
        outer().catch(function(error) { result = error.__kubexLocation.join(':'); });
        """;
        assertEquals("kubex/output/server_scripts.js:3:15", evaluate(source, null));
    }

    @Test
    void preservesLocationsAfterAnInnerFunctionChangesSourceOffsets() {
        String source = """
        function* outer() {
            function* inner() { yield 1; }
            yield inner();
            yield missing();
        }
        outer().catch(function(error) { result = error.__kubexLocation.join(':'); });
        """;
        assertEquals("kubex/output/server_scripts.js:4:11", evaluate(source, null));
    }

    private String evaluate(String source, String map) {
        String output = new ScriptCompiler().compile("server_scripts.js", source, new CompileOptions(false, map)).outputSource();
        var context = new ContextFactory().enter();
        var scope = context.initStandardObjects();
        context.evaluateString(scope, """
        var result;
        var Java = {loadClass: function() { return {
            trackUnhandledAt: function(detail, group, file, line, column) {
                result = file + ':' + line + ':' + column;
                return {cancel: function() {}};
            }
        }; }};
        """, "setup.js", 1, null);
        context.evaluateString(scope, output, "server_scripts/main.js", 1, null);
        return context.toString(context.evaluateString(scope, "result", "check.js", 1, null));
    }
}