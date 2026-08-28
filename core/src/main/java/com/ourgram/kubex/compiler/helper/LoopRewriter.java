package com.ourgram.kubex.compiler.helper;

import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.ExpressionStatement;
import dev.latvian.mods.rhino.ast.ForLoop;
import dev.latvian.mods.rhino.ast.FunctionCall;
import dev.latvian.mods.rhino.ast.FunctionNode;
import dev.latvian.mods.rhino.ast.Name;
import dev.latvian.mods.rhino.ast.VariableDeclaration;
import dev.latvian.mods.rhino.ast.VariableInitializer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class LoopRewriter implements CompilerHelper {
    private record LoopHelper(String name, FunctionNode functionNode) {}
    private record Edit(int start, int end, String replacement) {}
    private record LoopHelperMatch(
        int start,
        int end,
        String initializer,
        String condition,
        String increment,
        String loopVariable,
        String helperBody
    ) {}

    private static final String LOOP_REPLACEMENT =
        "for (%s; %s; %s) {\n"
            + "  (function(%s) %s)(%s);\n"
            + "}";
    private static final Pattern LOOP_HELPER_NAME_PATTERN = Pattern.compile("_loop\\d*");
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

    @Override
    public String rewrite(AstRoot root, String source) {
        String rewritten = applyEdits(source, collectLoopHelperEdits(root, source));
        return rewriteLoopHelpersFallback(rewritten);
    }

    private List<Edit> collectLoopHelperEdits(AstRoot root, String source) {
        List<Edit> edits = new ArrayList<>();
        Set<Integer> seenHelpers = new HashSet<>();
        visitAst(root, node -> {
            LoopHelper helper = extractLoopHelper(node);
            if(helper == null || !seenHelpers.add(node.getAbsolutePosition())) {
                return;
            }

            AstNode loopStatement = nextAstSibling(node);
            if(!(loopStatement instanceof ForLoop forLoop) || !isLoopHelperCall(forLoop, helper.name())) {
                return;
            }

            edits.add(new Edit(
                node.getAbsolutePosition(),
                loopStatement.getAbsolutePosition() + loopStatement.getLength(),
                buildLoopReplacementSource(forLoop, helper, source)
            ));
        });
        return edits;
    }

    private LoopHelper extractLoopHelper(AstNode statement) {
        if(!(statement instanceof VariableDeclaration declaration) || declaration.getVariables().size() != 1) {
            return null;
        }

        VariableInitializer initializer = declaration.getVariables().get(0);
        if(!(initializer.getTarget() instanceof Name helperName)) {
            return null;
        }

        String identifier = helperName.getIdentifier();
        if(!LOOP_HELPER_NAME_PATTERN.matcher(identifier).matches()) {
            return null;
        }

        if(!(initializer.getInitializer() instanceof FunctionNode functionNode)) {
            return null;
        }

        Name functionName = functionNode.getFunctionName();
        if((functionName != null && !identifier.equals(functionName.getIdentifier())) || !functionNode.getParams().isEmpty()) {
            return null;
        }

        return new LoopHelper(identifier, functionNode);
    }

    private boolean isLoopHelperCall(ForLoop forLoop, String helperName) {
        if(!(forLoop.getInitializer() instanceof VariableDeclaration declaration) || declaration.getVariables().size() != 1) {
            return false;
        }

        VariableInitializer initializer = declaration.getVariables().get(0);
        if(!(initializer.getTarget() instanceof Name loopName)) {
            return false;
        }

        AstNode body = forLoop.getBody();
        if(!(body instanceof Node bodyNode)) return false;

        List<AstNode> statements = childStatements(bodyNode);
        if(statements.size() != 1 || !(statements.get(0) instanceof ExpressionStatement expressionStatement)) {
            return false;
        }

        if(!(expressionStatement.getExpression() instanceof FunctionCall call)) {
            return false;
        }

        return call.getTarget() instanceof Name target
            && helperName.equals(target.getIdentifier())
            && call.getArguments().isEmpty()
            && loopName.getIdentifier() != null;
    }

    private String buildLoopReplacementSource(ForLoop forLoop, LoopHelper helper, String source) {
        String loopVariable = ((Name) ((VariableDeclaration) forLoop.getInitializer()).getVariables().get(0).getTarget()).getIdentifier();
        return formatLoopReplacement(
            slice(source, forLoop.getInitializer()),
            slice(source, forLoop.getCondition()),
            slice(source, forLoop.getIncrement()),
            loopVariable,
            slice(source, helper.functionNode().getBody())
        );
    }

    private String rewriteLoopHelpersFallback(String source) {
        String current = source;
        int cursor = 0;

        while(cursor < current.length()) {
            int varIndex = current.indexOf("var _loop", cursor);
            if(varIndex < 0) {
                return current;
            }

            LoopHelperMatch match = matchLoopHelperPattern(current, varIndex);
            if(match == null) {
                cursor = varIndex + 1;
                continue;
            }

            String replacement = formatLoopReplacement(
                match.initializer(),
                match.condition(),
                match.increment(),
                match.loopVariable(),
                match.helperBody()
            );
            current = current.substring(0, match.start()) + replacement + current.substring(match.end());
            cursor = match.start() + replacement.length();
        }

        return current;
    }

    private LoopHelperMatch matchLoopHelperPattern(String source, int start) {
        int cursor = start + "var ".length();
        int helperNameEnd = readIdentifierEnd(source, cursor);
        if(helperNameEnd <= cursor) return null;

        String helperName = source.substring(cursor, helperNameEnd);
        if(!LOOP_HELPER_NAME_PATTERN.matcher(helperName).matches()) {
            return null;
        }

        cursor = skipWhitespace(source, helperNameEnd);
        if(cursor >= source.length() || source.charAt(cursor) != '=') {
            return null;
        }

        cursor = skipWhitespace(source, cursor + 1);
        if(!source.startsWith("function", cursor)) {
            return null;
        }

        cursor = skipWhitespace(source, cursor + "function".length());
        int namedEnd = readIdentifierEnd(source, cursor);
        if(namedEnd > cursor) {
            String functionName = source.substring(cursor, namedEnd);
            if(!functionName.equals(helperName)) {
                return null;
            }
            cursor = skipWhitespace(source, namedEnd);
        }

        if(cursor >= source.length() || source.charAt(cursor) != '(') {
            return null;
        }

        int paramsEnd = findMatching(source, cursor, '(', ')');
        if(paramsEnd < 0 || !source.substring(cursor + 1, paramsEnd).trim().isEmpty()) {
            return null;
        }

        cursor = skipWhitespace(source, paramsEnd + 1);
        if(cursor >= source.length() || source.charAt(cursor) != '{') {
            return null;
        }

        int helperBodyEnd = findMatching(source, cursor, '{', '}');
        if(helperBodyEnd < 0) {
            return null;
        }

        String helperBody = source.substring(cursor, helperBodyEnd + 1);
        cursor = skipWhitespace(source, helperBodyEnd + 1);
        if(cursor < source.length() && source.charAt(cursor) == ';') {
            cursor = skipWhitespace(source, cursor + 1);
        }

        if(!source.startsWith("for", cursor)) {
            return null;
        }

        cursor = skipWhitespace(source, cursor + 3);
        if(cursor >= source.length() || source.charAt(cursor) != '(') {
            return null;
        }

        int headerEnd = findMatching(source, cursor, '(', ')');
        if(headerEnd < 0) {
            return null;
        }

        String header = source.substring(cursor + 1, headerEnd);
        List<String> headerParts = splitTopLevel(header, ';');
        if(headerParts.size() != 3) {
            return null;
        }

        String initializer = headerParts.get(0).trim();
        String loopVariable = extractLoopVariable(initializer);
        if(loopVariable == null) {
            return null;
        }

        cursor = skipWhitespace(source, headerEnd + 1);
        if(cursor >= source.length() || source.charAt(cursor) != '{') {
            return null;
        }

        int loopBodyEnd = findMatching(source, cursor, '{', '}');
        if(loopBodyEnd < 0) {
            return null;
        }

        String loopBody = source.substring(cursor + 1, loopBodyEnd).trim();
        if(!loopBody.equals(helperName + "();")) {
            return null;
        }

        return new LoopHelperMatch(
            start,
            loopBodyEnd + 1,
            initializer,
            headerParts.get(1).trim(),
            headerParts.get(2).trim(),
            loopVariable,
            helperBody
        );
    }

    private int skipWhitespace(String source, int index) {
        int cursor = index;
        while(cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private int readIdentifierEnd(String source, int index) {
        if(index >= source.length() || !isIdentifierStart(source.charAt(index))) {
            return index;
        }

        int cursor = index + 1;
        while(cursor < source.length() && isIdentifierPart(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private String extractLoopVariable(String initializer) {
        String trimmed = initializer.trim();
        if(trimmed.startsWith("var ")) {
            trimmed = trimmed.substring(4).trim();
        }

        int equalsIndex = trimmed.indexOf('=');
        String candidate = equalsIndex >= 0 ? trimmed.substring(0, equalsIndex).trim() : trimmed;
        return candidate.isEmpty() ? null : candidate;
    }

    private List<String> splitTopLevel(String source, char delimiter) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        int braceDepth = 0;
        int state = 0;

        for(int cursor = 0; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);

            if(state == 1) {
                if(current == '\\') {
                    cursor++;
                    continue;
                }
                if(current == '\'') {
                    state = 0;
                }
                continue;
            }

            if(state == 2) {
                if(current == '\\') {
                    cursor++;
                    continue;
                }
                if(current == '"') {
                    state = 0;
                }
                continue;
            }

            if(state == 3) {
                if(current == '\n') {
                    state = 0;
                }
                continue;
            }

            if(state == 4) {
                if(current == '*' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '/') {
                    cursor++;
                    state = 0;
                }
                continue;
            }

            if(current == '\'') {
                state = 1;
                continue;
            }
            if(current == '"') {
                state = 2;
                continue;
            }
            if(current == '/' && cursor + 1 < source.length()) {
                char next = source.charAt(cursor + 1);
                if(next == '/') {
                    state = 3;
                    cursor++;
                    continue;
                }
                if(next == '*') {
                    state = 4;
                    cursor++;
                    continue;
                }
            }

            if(current == '(') parenDepth++;
            if(current == ')') parenDepth--;
            if(current == '[') bracketDepth++;
            if(current == ']') bracketDepth--;
            if(current == '{') braceDepth++;
            if(current == '}') braceDepth--;

            if(current == delimiter && parenDepth == 0 && bracketDepth == 0 && braceDepth == 0) {
                parts.add(source.substring(start, cursor));
                start = cursor + 1;
            }
        }

        parts.add(source.substring(start));
        return parts;
    }

    private int findMatching(String source, int start, char open, char close) {
        int depth = 0;
        int state = 0;

        for(int cursor = start; cursor < source.length(); cursor++) {
            char current = source.charAt(cursor);

            if(state == 1) {
                if(current == '\\') {
                    cursor++;
                    continue;
                }
                if(current == '\'') {
                    state = 0;
                }
                continue;
            }

            if(state == 2) {
                if(current == '\\') {
                    cursor++;
                    continue;
                }
                if(current == '"') {
                    state = 0;
                }
                continue;
            }

            if(state == 3) {
                if(current == '\n') {
                    state = 0;
                }
                continue;
            }

            if(state == 4) {
                if(current == '*' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '/') {
                    cursor++;
                    state = 0;
                }
                continue;
            }

            if(current == '\'') {
                state = 1;
                continue;
            }
            if(current == '"') {
                state = 2;
                continue;
            }
            if(current == '/' && cursor + 1 < source.length()) {
                char next = source.charAt(cursor + 1);
                if(next == '/') {
                    state = 3;
                    cursor++;
                    continue;
                }
                if(next == '*') {
                    state = 4;
                    cursor++;
                    continue;
                }
            }

            if(current == open) {
                depth++;
            } else if(current == close) {
                depth--;
                if(depth == 0) {
                    return cursor;
                }
            }
        }

        return -1;
    }

    private AstNode nextAstSibling(AstNode node) {
        AstNode parent = node.getParent();
        if(parent instanceof Node parentNode) {
            List<AstNode> statements = childStatements(parentNode);
            for(int index = 0; index < statements.size(); index++) {
                if(statements.get(index) != node) continue;
                return index + 1 < statements.size() ? statements.get(index + 1) : null;
            }
        }

        for(Node current = node.getNext(); current != null; current = current.getNext()) {
            if(current instanceof AstNode astNode) {
                return astNode;
            }
        }
        return null;
    }

    private List<AstNode> childStatements(Node container) {
        List<AstNode> statements = new ArrayList<>();
        for(Node child = container.getFirstChild(); child != null; child = child.getNext()) {
            if(child instanceof AstNode astChild) {
                statements.add(astChild);
            }
        }
        return statements;
    }

    private String slice(String source, AstNode node) {
        int start = node.getAbsolutePosition();
        int end = start + node.getLength();
        return source.substring(start, end);
    }

    private String applyEdits(String source, List<Edit> edits) {
        if(edits.isEmpty()) {
            return source;
        }

        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder builder = new StringBuilder(source);

        for(Edit edit : ordered) {
            builder.replace(edit.start(), edit.end(), edit.replacement());
        }
        return builder.toString();
    }

    private void visitAst(AstNode root, Consumer<AstNode> consumer) {
        visitAst(root, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), consumer);
    }

    private void visitAst(AstNode node, Set<AstNode> visited, Consumer<AstNode> consumer) {
        if(node == null || !visited.add(node)) return;
        consumer.accept(node);

        for(Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if(child instanceof AstNode astChild) {
                visitAst(astChild, visited, consumer);
            }
        }

        for(String name : AST_CHILD_GETTERS) {
            try {
                java.lang.reflect.Method method = node.getClass().getMethod(name);
                if(method.getParameterCount() != 0) continue;

                Object value = method.invoke(node);
                if(value instanceof AstNode astChild) {
                    visitAst(astChild, visited, consumer);
                } else if(value instanceof List<?> list) {
                    for(Object entry : list) {
                        if(entry instanceof AstNode astChild) {
                            visitAst(astChild, visited, consumer);
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
                continue;
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private boolean isIdentifierPart(char character) {
        return character == '_' || character == '$' || Character.isLetterOrDigit(character);
    }

    private boolean isIdentifierStart(char character) {
        return character == '_' || character == '$' || Character.isLetter(character);
    }

    private String formatLoopReplacement(String initializer, String condition, String increment, String loopVariable, String helperBody) {
        return LOOP_REPLACEMENT.formatted(initializer, condition, increment, loopVariable, helperBody, loopVariable);
    }
}