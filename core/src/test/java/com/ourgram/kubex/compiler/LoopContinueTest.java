package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoopContinueTest {
    @Test
    void preservesContinueAndDeferredPlayersInsideClassCallback() {
        String source = """
        var callbacks = [], result = [], visited = [];
        function createClass(type, methods) { type.prototype.reload = methods[0].value; return type; }
        var Skin = (function() { return createClass(function() {}, [{key: 'reload', value: function() {
            var server = {execute: function(action) { action(); }};
            var players = ['first', 'skip', 'third'];
            server.execute(function() {
                var _loop = function _loop() {
                    var player = players[i];
                    visited.push(i);
                    if(player === 'skip') return 1;
                    callbacks.push(function() { result.push(player + ':' + i); });
                };
                for(var i = 0; i < players.length; i++) {
                    if(_loop()) continue;
                }
            });
        }}]); })();
        new Skin().reload();
        callbacks.forEach(function(callback) { callback(); });
        result.join(',') + '|' + visited.join(',');
        """;
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        assertFalse(output.contains("var _loop ="));
        assertTrue(output.contains("if((function(i)"));
        var context = new ContextFactory().enter();
        var scope = context.initStandardObjects();
        assertEquals("first:0,third:2|0,1,2", context.toString(context.evaluateString(scope, output, "test.js", 1, null)));
    }

    @Test
    void leavesUnsupportedConditionalBodiesUntouched() {
        String source = """
            var _loop = function _loop() { return 1; };
            for(var i = 0; i < 3; i++) { if(_loop()) break; }
            """;
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        assertTrue(output.contains("var _loop ="));
        assertTrue(output.contains("if(_loop()) break;"));
    }
}