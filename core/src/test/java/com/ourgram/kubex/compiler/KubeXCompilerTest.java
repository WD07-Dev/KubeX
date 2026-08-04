package com.ourgram.kubex.compiler;

import dev.latvian.mods.rhino.CompilerEnvirons;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.FunctionNode;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubeXCompilerTest {
    private static final ContextFactory CONTEXT_FACTORY = new ContextFactory();
    private static final List<String> AST_CHILD_GETTERS = List.of(
        "getExpression",
        "getTarget",
        "getInitializer",
        "getCondition",
        "getIncrement",
        "getBody",
        "getLeft",
        "getRight",
        "getArguments",
        "getElements",
        "getVariables",
        "getFunctionName",
        "getParams",
        "getProperty"
    );

    private final KubeXCompiler compiler = new KubeXCompiler();

    @Test
    void rewritesBundledPackageRequires() {
        String source = """
            (function () {
              var __require = function (x) {
                if (typeof require !== "undefined") return require.apply(this, arguments);
                throw Error('Dynamic require of "' + x + '" is not supported');
              };
              var import_item = __require("@package/net/minecraft/world/item");
              var import_util = __require("@package/com/example/util");
              import_item.$Items.APPLE.getDefaultInstance();
              import_util.$PlayerExtensionsKt.party(player);
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertTrue(output.contains("Java.loadClass(\"net.minecraft.world.item.Items\").APPLE.getDefaultInstance();"));
        assertTrue(output.contains("Java.loadClass(\"com.example.util.PlayerExtensionsKt\").party(player);"));
        assertFalse(output.contains("__require"));
        assertFalse(output.contains("typeof require"));
    }

    @Test
    void rewritesPackageRequiresInsideArrayLiterals() {
        String source = """
            (function () {
              var import_stats = __require("@package/com/cobblemon/mod/common/api/pokemon/stats");
              var STATS = [import_stats.$Stats.HP, import_stats.$Stats.ATTACK];
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertTrue(output.contains("Java.loadClass(\"com.cobblemon.mod.common.api.pokemon.stats.Stats\").HP"));
        assertTrue(output.contains("Java.loadClass(\"com.cobblemon.mod.common.api.pokemon.stats.Stats\").ATTACK"));
        assertFalse(output.contains("import_stats.$Stats"));
    }

    @Test
    void rewritesPackageRequiresInsideNestedMemberChains() {
        String source = """
            (function () {
              var import_util = __require("@package/com/cobblemon/mod/common/util");
              var party = import_util.$PlayerExtensionsKt.party(player);
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertTrue(output.contains("Java.loadClass(\"com.cobblemon.mod.common.util.PlayerExtensionsKt\").party(player);"));
        assertFalse(output.contains("import_util.$PlayerExtensionsKt"));
    }

    @Test
    void doesNotRewritePackageMarkersInsideStrings() {
        String source = """
            (function () {
              var import_util = __require("@package/com/cobblemon/mod/common/util");
              var message = "import_util.$PlayerExtensionsKt should stay text";
              return message;
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertTrue(output.contains("\"import_util.$PlayerExtensionsKt should stay text\""));
        assertFalse(output.contains("Java.loadClass(\"com.cobblemon.mod.common.util.PlayerExtensionsKt\").should"));
    }

    @Test
    void leavesNonPackageRequiresUntouched() {
        String source = """
            (function () {
              var __require = function (x) {
                if (typeof require !== "undefined") return require.apply(this, arguments);
                throw Error('Dynamic require of "' + x + '" is not supported');
              };
              var fs = __require("node:fs");
              return fs.readFileSync(path);
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertTrue(output.contains("var fs = __require(\"node:fs\");"));
    }

    @Test
    void failsLoudlyWhenRhinoSyntaxCannotBeParsed() {
        String source = """
            (function () {
              var broken = ;
            })();
        """;

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> compiler.compile("server_scripts.js", source)
        );

        assertTrue(exception.getMessage().contains("Failed to lower Rhino syntax"));
        assertTrue(exception.getMessage().contains("at "));
    }

    @Test
    void rewritesBabelLoopHelpersIntoPerIterationIifes() {
        String source = """
            (function () {
              var _loop = function _loop() {
                var pokemon = partyPokemon[slot];
                gui.button(slot + 1, 1, pokemon, function (click) {
                  return pokemon.getDisplayName(true);
                });
              };
              for (var slot = 0; slot < partyPokemon.length; slot++) {
                _loop();
              }
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();

        assertFalse(output.contains("var _loop = function _loop()"));
        assertTrue(output.contains("for (var slot = 0; slot < partyPokemon.length; slot++) {"));
        assertTrue(output.contains("(function(slot) {"));
        assertTrue(output.contains("var pokemon = partyPokemon[slot];"));
        assertTrue(output.contains("})(slot);"));
    }

    @Test
    void rewritesNestedBabelLoopHelpersInsideCreateClassCallbacks() {
        String source = """
            (function () {
              return _createClass(ItemRelicGacha, null, [{
                key: "openShinySelector",
                value: function openShinySelector(player, item) {
                  var partyPokemon = this.getPartyPokemon(player);
                  if (!partyPokemon) return;
                  player.openChestGUI("test", 3, function (gui) {
                    gui.playerSlots = false;
                    var _loop = function _loop() {
                      var pokemon = partyPokemon[slot];
                      gui.button(slot + 1, 1, pokemon, function (click) {
                        return pokemon.getDisplayName(true);
                      });
                    };
                    for (var slot = 0; slot < partyPokemon.length; slot++) {
                      _loop();
                    }
                  });
                }
              }]);
            })();
        """;
        String output = compiler.compile("server_scripts.js", source).outputSource();
        String diagnostics = diagnostics(source, output);

        assertFalse(output.contains("var _loop = function _loop()"), diagnostics);
        assertFalse(output.contains("_loop();"), diagnostics);
        assertTrue(output.contains("for (var slot = 0; slot < partyPokemon.length; slot++) {"), diagnostics);
        assertTrue(output.contains("(function(slot) {"), diagnostics);
        assertTrue(output.contains("var pokemon = partyPokemon[slot];"), diagnostics);
        assertTrue(output.contains("})(slot);"), diagnostics);
    }

    @Test
    void wrapsCallbackFunctionsInDebugMode() {
        String source = """
            (function () {
              gui.button(1, 1, item, function (click) {
                player.tell("ok");
              });
            })();
        """;
        String output = compiler.compile("server_scripts.js", source, new CompileOptions(true, null)).outputSource();

        assertTrue(output.contains("var __kubexReport"));
        assertTrue(output.contains("try {"));
        assertTrue(output.contains("__kubexReport(e, { scriptGroup: \"server_scripts\", file: \"server_scripts.js\""));
        assertTrue(output.contains("throw e;"));
    }

    @Test
    void skipsWrappingCallbacksThatAlreadyHaveTryCatch() {
        String source = """
            (function () {
              gui.button(1, 1, item, function (click) {
                try {
                  player.tell("ok");
                } catch (e) {
                  player.tell(String(e));
                }
              });
            })();
        """;
        String output = compiler.compile("server_scripts.js", source, new CompileOptions(true, null)).outputSource();

        assertFalse(output.contains("var __kubexReport"));
        assertFalse(output.contains("throw e;"));
    }

    @Test
    void tracksGeneratedLinesBackToOriginalOutputLines() {
        String source = """
            (function () {
              gui.button(1, 1, item, function (click) {
                player.tell("ok");
              });
            })();
        """;
        CompileResult result = compiler.compile("server_scripts.js", source, new CompileOptions(true, null));
        List<String> originalLines = Arrays.asList(source.replace("\r\n", "\n").split("\n", -1));
        List<String> generatedLines = Arrays.asList(result.outputSource().replace("\r\n", "\n").split("\n", -1));
        int generatedLine = findLineContaining(generatedLines, "player.tell(\"ok\");");
        int originalLine = result.generatedToOriginalLineMap()[generatedLine - 1];

        assertTrue(generatedLine > 0);
        assertTrue(originalLine > 0);
        assertTrue(originalLine <= originalLines.size());
        assertTrue(originalLines.get(originalLine - 1).contains("player.tell(\"ok\");"));
    }

    private int findLineContaining(List<String> lines, String needle) {
        for(int index = 0; index < lines.size(); index++) {
            if(lines.get(index).contains(needle)) {
                return index + 1;
            }
        }
        return -1;
    }

    private String diagnostics(String source, String output) {
        return output + "\n\n=== AST ===\n" + astSummary(source);
    }

    private String astSummary(String source) {
        LAST_SOURCE.set(source);
        try {
            AstRoot root = parse(source);
            StringBuilder builder = new StringBuilder();
            visit(root, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), builder, 0);
            return builder.toString();
        } finally {
            LAST_SOURCE.remove();
        }
    }

    private AstRoot parse(String source) {
        Context context = CONTEXT_FACTORY.enter();
        CompilerEnvirons environs = new CompilerEnvirons();
        environs.initFromContext(context);
        return new Parser(context, environs).parse(source, "test.js", 1);
    }

    private void visit(AstNode node, Set<AstNode> visited, StringBuilder builder, int depth) {
        if(node == null || !visited.add(node)) return;

        indent(builder, depth)
            .append(node.getClass().getSimpleName())
            .append(" @")
            .append(node.getAbsolutePosition())
            .append(':')
            .append(node.getLength());

        if(node instanceof FunctionNode functionNode) {
            builder.append(" fn=").append(functionNode.getName());
        }

        String snippet = snippet(node);
        if(!snippet.isBlank()) {
            builder.append(" :: ").append(snippet);
        }
        builder.append('\n');

        for(AstNode child : reflectiveChildren(node)) {
            visit(child, visited, builder, depth + 1);
        }
    }

    private List<AstNode> reflectiveChildren(AstNode node) {
        List<AstNode> children = new ArrayList<>();
        for(String name : AST_CHILD_GETTERS) {
            try {
                Method method = node.getClass().getMethod(name);
                if(method.getParameterCount() != 0) continue;

                Object value = method.invoke(node);
                if(value instanceof AstNode astNode) {
                    children.add(astNode);
                } else if(value instanceof List<?> list) {
                    for(Object entry : list) {
                        if(entry instanceof AstNode astNode) {
                            children.add(astNode);
                        }
                    }
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return children;
    }

    private StringBuilder indent(StringBuilder builder, int depth) {
        for(int i = 0; i < depth; i++) {
            builder.append("  ");
        }
        return builder;
    }

    private String snippet(AstNode node) {
        String source = currentSource(node);
        if(source == null) return "";

        int start = Math.max(0, node.getAbsolutePosition());
        int end = Math.min(source.length(), start + node.getLength());
        return start >= end ? "" : source.substring(start, end).replace('\n', ' ').trim();
    }

    private String currentSource(AstNode node) {
        return LAST_SOURCE.get();
    }

    private static final ThreadLocal<String> LAST_SOURCE = new ThreadLocal<>();
}
