package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.ContextFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsyncChainTest {
    @Test
    void supportsComputedPropertiesConstructorsAndForIn() {
        assertEquals("7:ab", evaluate("""
        var result;
        function Box(value) { this.value = value; }
        function* foo() {
            var object = new Box(1 + (yield 6)), keys = '';
            for(var key in {a: 1, b: 2}) { keys += yield key; }
            return object[yield 'value'] + ':' + keys;
        }
        foo().then(function(value) { result = value; });
        """));
    }
    @Test
    void doesNotAwaitPromisesWithoutAwaitAndRunsFinallyBeforeAdoption() {
        assertEquals("started,finally|started,finally,done:9", evaluate("""
        var result, finish, log = [];
        function* foo() {
            try {
                var pending = new Promise(function(resolve) { finish = resolve; });
                log.push('started');
                return pending;
            } finally { log.push('finally'); }
        }
        foo().then(function(value) { log.push('done:' + value); });
        var before = log.join(','); finish(9); result = before + '|' + log.join(',');
        """));
    }

    @Test
    void preservesArgumentsAndLabeledContinue() {
        assertEquals("9", evaluate("""
        var result;
        function* foo(value) {
            var sum = arguments[0];
            outer: for(var i = 0; i < 3; i++) {
                for(var j = 0; j < 3; j++) {
                    sum += yield 1;
                    if(j === 1) continue outer;
                }
            }
            return sum;
        }
        foo(3).then(function(value) { result = value; });
        """));
    }
    @Test
    void preservesOperandOrderAndMethodReceiver() {
        assertEquals("left,wait|left,wait,right,method:15", evaluate("""
        var result = '', trace = [], finish;
        var object = {base: 10, add: function(a, b) { trace.push('method'); return this.base + a + b; }};
        function left() { trace.push('left'); return 2; }
        function right() { trace.push('right'); return 3; }
        function wait() { trace.push('wait'); return new Promise(function(resolve) { finish = resolve; }); }
        function* foo() { return object.add(left() + (yield wait()), right()); }
        foo().then(function(value) { result = trace.join(',') + ':' + value; });
        var before = trace.join(','); finish(0); result = before + '|' + result;
        """));
    }

    @Test
    void shortCircuitsAndBuildsLiterals() {
        assertEquals("3:4:0", evaluate("""
        var result, calls = 0;
        function unexpected() { calls++; return 99; }
        function* foo() {
            var a = false && (yield unexpected());
            var b = true || (yield unexpected());
            var c = (yield true) ? (yield 3) : (yield unexpected());
            var value = {items: [c, 1 + (yield 3)]};
            return value.items[0] + ':' + value.items[1] + ':' + calls;
        }
        foo().then(function(value) { result = value; });
        """));
    }

    @Test
    void preservesAssignmentReferenceAndOldValue() {
        assertEquals("7:1", evaluate("""
        var result, finish, reads = 0, object = {value: 2};
        function target() { reads++; return object; }
        function* foo() { target().value += yield new Promise(function(resolve) { finish = resolve; }); return object.value; }
        foo().then(function(value) { result = value + ':' + reads; });
        object.value = 100; finish(5);
        """));
    }

    @Test
    void supportsSwitchFallthroughAndDoWhile() {
        assertEquals("ABBC", evaluate("""
        var result;
        function* foo() {
            var log = '', i = 0;
            do {
                switch(yield i) {
                    case 0: log += yield 'A';
                    case 1: log += yield 'B'; break;
                    default: log += yield 'C';
                }
                i++;
            } while((yield i) < 3);
            return log;
        }
        foo().then(function(value) { result = value; });
        """));
    }
    private String evaluate(String source) {
        String output = new ScriptCompiler().compile("server_scripts.js", source).outputSource();
        assertFalse(output.contains("function*"));
        assertFalse(output.contains("__kubexRunGenerator"));
        var context = new ContextFactory().enter();
        var scope = context.initStandardObjects();
        context.evaluateString(scope, output, "test.js", 1, null);
        return context.toString(context.evaluateString(scope, "result", "test.js", 1, null));
    }

    @Test
    void resumesOnlyAfterDeferredCompletion() {
        assertEquals("A|ABdone:123", evaluate("""
        var finish, result = '';
        function* foo() {
            try {
                result += 'A';
                yield new Promise(function(resolve) { finish = resolve; });
                result += 'B';
                return 123;
            } catch(e) { result += 'failed'; }
            finally { result += 'done'; }
        }
        foo().then(function(value) { result += ':' + value; });
        var before = result;
        finish();
        result = before + '|' + result;
        """));
    }

    @Test
    void catchesRejectionAndRunsFinally() {
        assertEquals("faileddone:7", evaluate("""
        var result = '';
        function* foo() {
            try { yield Promise.reject('failed'); result += 'wrong'; }
            catch(e) { result += e; return 7; }
            finally { result += 'done'; }
        }
        foo().then(function(value) { result += ':' + value; });
        """));
    }

    @Test
    void finallyReturnOverridesEarlierReturn() {
        assertEquals("9", evaluate("""
        var result;
        function* foo() { try { return 1; } finally { return yield Promise.resolve(9); } }
        foo().then(function(value) { result = value; });
        """));
    }

    @Test
    void preservesVariablesAcrossBranchesAndLoops() {
        assertEquals("5", evaluate("""
        var result;
        function* foo() {
            var sum = 0;
            for(var i = 0; i < 4; i++) {
                if(i === 1) continue;
                var number = yield Promise.resolve(i);
                sum += number;
                if(i === 3) break;
            }
            return sum;
        }
        foo().then(function(value) { result = value; });
        """));
    }

    @Test
    void transformsAsyncCallbackInsideEventRegistration() {
        assertEquals("3", evaluate("""
        var result;
        function register(callback) { callback().then(function(value) { result = value; }); }
        register(function* () { var value = yield 3; return value; });
        """));
    }
}