package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PromiseRejectionTest {
    @Test
    void reportsOnlyTheUnhandledEndOfAChain() {
        assertEquals("1:Error: boom", evaluate("""
        Promise.reject(new Error('boom')).then(function(value) { return value; }).finally(function() {});
        """));
    }

    @Test
    void ignoresCaughtAndAggregatedRejections() {
        assertEquals("0:", evaluate("""
        Promise.reject('caught').catch(function() {});
        Promise.all([Promise.reject('all')]).catch(function() {});
        Promise.allSettled([Promise.reject('settled')]);
        Promise.resolve().then(function() { throw new Error('callback'); }).catch(function() {});
        """));
    }

    @Test
    void reportsAsyncFailuresAndPrimitiveReasons() {
        assertEquals("2:Error: async,primitive", evaluate("""
        function* fail() { yield 1; throw new Error('async'); }
        fail();
        Promise.reject('primitive');
        """));
    }

    private String evaluate(String source) {
        var context = new ContextFactory().enter();
        var scope = context.initStandardObjects();
        context.evaluateString(scope, """
        var reports = [], failures = [];
        var Java = {loadClass: function() { return {
            trackUnhandled: function(detail, group, line, column) {
                if(group !== 'server_scripts' || line <= 0) throw new Error('Missing rejection position');
                var ticket = {detail: detail, handled: false, cancel: function() { this.handled = true; }};
                reports.push(ticket);
                return ticket;
            }
        }; }};
        var console = {error: function(message) { failures.push(message); }};
        """, "setup.js", 1, null);
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        context.evaluateString(scope, output, "server_scripts/main.js", 1, null);
        assertEquals("", context.toString(context.evaluateString(scope, "failures.join(';')", "check.js", 1, null)));
        return context.toString(context.evaluateString(scope, """
        var unhandled = reports.filter(function(ticket) { return !ticket.handled; });
        unhandled.length + ':' + unhandled.map(function(ticket) { return ticket.detail; }).join(',');
        """, "check.js", 1, null));
    }
}