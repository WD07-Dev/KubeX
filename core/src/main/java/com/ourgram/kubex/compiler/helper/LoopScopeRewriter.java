package com.ourgram.kubex.compiler.helper;

import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.ExpressionStatement;
import dev.latvian.mods.rhino.ast.ForLoop;
import dev.latvian.mods.rhino.ast.Name;
import dev.latvian.mods.rhino.ast.StringLiteral;
import dev.latvian.mods.rhino.ast.VariableDeclaration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class LoopScopeRewriter implements CompilerHelper {
    private record Edit(int start, int end, String text) {}
    private static final List<String> CHILDREN = List.of(
        "getExpression", "getReturnValue", "getTarget", "getInitializer", "getCondition",
        "getIncrement", "getBody", "getLeft", "getRight", "getArguments", "getElements",
        "getVariables", "getThenPart", "getElsePart", "getTryBlock", "getCatchClauses",
        "getFinallyBlock", "getStatements", "getCases", "getStatement", "getTrueExpression",
        "getFalseExpression", "getTestExpression", "getOperand", "getIterator", "getIteratedObject"
    );

    @Override
    public String rewrite(AstRoot root, String source) {
        List<Edit> edits = new ArrayList<>();
        Map<AstNode, Set<String>> declarations = new IdentityHashMap<>();
        visit(root, Collections.newSetFromMap(new IdentityHashMap<>()), node -> {
            if(!(node instanceof ForLoop loop) || !(loop.getInitializer() instanceof VariableDeclaration declaration)) return;
            int start = declaration.getAbsolutePosition();
            if(!source.startsWith("var", start)) return;
            if(declaration.getVariables().stream().anyMatch(variable -> !(variable.getTarget() instanceof Name))) return;

            var function = loop.getEnclosingFunction();
            AstNode scope = function == null ? root : function.getBody();
            Set<String> names = declarations.computeIfAbsent(scope, ignored -> new LinkedHashSet<>());
            for(var variable : declaration.getVariables()) names.add(((Name) variable.getTarget()).getIdentifier());
            edits.add(new Edit(start, start + 3, "   "));
        });
        declarations.forEach((scope, names) -> {
            int offset = scope instanceof AstRoot ? 0 : scope.getAbsolutePosition() + 1;
            for(Node child = scope.getFirstChild(); child != null; child = child.getNext()) {
                if(!(child instanceof ExpressionStatement statement) || !(statement.getExpression() instanceof StringLiteral)) break;
                offset = statement.getAbsolutePosition() + statement.getLength();
            }
            edits.add(new Edit(offset, offset, ";var " + String.join(", ", names) + ";"));
        });
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder output = new StringBuilder(source);
        for(Edit edit : edits) output.replace(edit.start(), edit.end(), edit.text());
        return output.toString();
    }

    private void visit(AstNode node, Set<AstNode> seen, Consumer<AstNode> action) {
        if(node == null || !seen.add(node)) return;
        action.accept(node);
        for(Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if(child instanceof AstNode ast) visit(ast, seen, action);
        }
        for(String getter : CHILDREN) {
            try {
                Object value = node.getClass().getMethod(getter).invoke(node);
                if(value instanceof AstNode ast) visit(ast, seen, action);
                else if(value instanceof List<?> list) {
                    for(Object entry : list) if(entry instanceof AstNode ast) visit(ast, seen, action);
                }
            } catch(NoSuchMethodException ignored) {
            } catch(ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to inspect loop scope", exception);
            }
        }
    }
}