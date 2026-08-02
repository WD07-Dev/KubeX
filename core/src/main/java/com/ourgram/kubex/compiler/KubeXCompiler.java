package com.ourgram.kubex.compiler;

import com.ourgram.kubex.compiler.helper.CompilerHelper;
import com.ourgram.kubex.compiler.helper.LoopRewriter;
import com.ourgram.kubex.sourcemap.KubeXSourceMapService;
import dev.latvian.mods.rhino.CompilerEnvirons;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.RhinoException;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.Block;
import dev.latvian.mods.rhino.ast.FunctionCall;
import dev.latvian.mods.rhino.ast.FunctionNode;
import dev.latvian.mods.rhino.ast.Name;
import dev.latvian.mods.rhino.ast.PropertyGet;
import dev.latvian.mods.rhino.ast.TryStatement;
import dev.latvian.mods.rhino.ast.VariableDeclaration;
import dev.latvian.mods.rhino.ast.VariableInitializer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class KubeXCompiler {
    private record PackageAlias(String alias, String packageName) {}
    private record Edit(int start, int end, String replacement) {}
    private record LineColumn(int line, int column) {}
    private record SourceLocation(String file, int line, int column) {}

    private static final List<CompilerHelper> HELPERS = List.of(
        new LoopRewriter()
    );
    private static final String BANNER = loadResource("kubex/compiler/banner.js");
    private static final String DEBUG_HELPER = loadResource("kubex/compiler/debug-helper.js");
    private static final String JAVA_LOAD_CLASS = loadResource("kubex/compiler/java-load-class.js");
    private static final String DEBUG_TRY_PREFIX = "\n  try {";
    private static final String DEBUG_CATCH_SUFFIX =
        "\n  } catch (e) {\n"
            + "    __kubexReport(e, { scriptGroup: \"${scriptGroup}\", file: \"${file}\", line: ${line}, column: ${column} });\n"
            + "    throw e;\n"
            + "  }\n";
    private static final String SOURCE_NAME = "kubex-sync.js";
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
    private static final ContextFactory CONTEXT_FACTORY = new ContextFactory();

    public CompileResult compile(String fileName, String source) {
        return compile(fileName, source, CompileOptions.DEFAULT);
    }

    public CompileResult compile(String fileName, String source, CompileOptions options) {
        AstRoot firstPassRoot = parseScript(source);
        Map<String, String> packageAliases = collectPackageAliases(firstPassRoot);
        List<Edit> firstPassEdits = new ArrayList<>();
        firstPassEdits.addAll(collectPackageAliasRemovalEdits(firstPassRoot));
        if(!containsNonPackageRequireCall(firstPassRoot)) {
            firstPassEdits.addAll(collectRequireHelperRemovalEdits(firstPassRoot));
        }

        String firstPass = applyEdits(source, firstPassEdits);
        firstPass = applyHelpers(firstPass);
        AstRoot secondPassRoot = parseScript(firstPass);
        String secondPass = applyEdits(firstPass, collectPackageMemberAccessEdits(secondPassRoot, packageAliases));
        secondPass = rewriteRemainingPackageMemberAccesses(secondPass, packageAliases);
        String transformed = secondPass;

        if(options.debugEnabled()) {
            AstRoot thirdPassRoot = parseScript(secondPass);
            List<Edit> debugEdits = collectDebugWrapEdits(thirdPassRoot, secondPass, fileName, options.sourceMapContent());
            if(!debugEdits.isEmpty()) {
                transformed = prependDebugHelper(applyEdits(secondPass, debugEdits));
            }
        }

        transformed = prependBanner(transformed);
        validateRhino(transformed);
        return new CompileResult(fileName, source, transformed);
    }

    private String applyHelpers(String source) {
        String current = source;
        for(CompilerHelper helper : HELPERS) {
            current = helper.rewrite(parseScript(current), current);
        }
        return current;
    }

    private AstRoot parseScript(String source) {
        Context context = CONTEXT_FACTORY.enter();
        try {
            CompilerEnvirons environs = new CompilerEnvirons();
            environs.initFromContext(context);
            return new Parser(context, environs).parse(source, SOURCE_NAME, 1);
        } catch (RuntimeException exception) {
            throw new IllegalStateException(failureMessage(exception), exception);
        }
    }

    private Map<String, String> collectPackageAliases(AstRoot root) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for(Node container : collectStatementContainers(root)) {
            List<AstNode> statements = childStatements(container);
            for(AstNode statement : statements) {
                PackageAlias alias = extractPackageAlias(statement);
                if(alias == null) continue;
                aliases.put(alias.alias(), alias.packageName());
            }
        }
        return aliases;
    }

    private List<Edit> collectPackageAliasRemovalEdits(AstRoot root) {
        List<Edit> edits = new ArrayList<>();
        for(Node container : collectStatementContainers(root)) {
            for(AstNode statement : childStatements(container)) {
                if(extractPackageAlias(statement) != null) {
                    edits.add(removeNode(statement));
                }
            }
        }
        return edits;
    }

    private List<Edit> collectRequireHelperRemovalEdits(AstRoot root) {
        List<Edit> edits = new ArrayList<>();
        for(Node container : collectStatementContainers(root)) {
            for(AstNode statement : childStatements(container)) {
                if(isRequireHelperDeclaration(statement)) {
                    edits.add(removeNode(statement));
                }
            }
        }
        return edits;
    }

    private List<Node> collectStatementContainers(AstRoot root) {
        List<Node> containers = new ArrayList<>();
        Set<Node> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        visitAst(root, node -> {
            if((node instanceof AstRoot || node instanceof Block) && seen.add(node)) {
                containers.add(node);
            }

            if(node instanceof FunctionNode functionNode && functionNode.getBody() instanceof Node bodyNode && seen.add(bodyNode)) {
                containers.add(bodyNode);
            }
        });
        return containers;
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

    private PackageAlias extractPackageAlias(AstNode statement) {
        if(!(statement instanceof VariableDeclaration declaration) || declaration.getVariables().size() != 1) {
            return null;
        }

        VariableInitializer initializer = declaration.getVariables().get(0);
        if(!(initializer.getTarget() instanceof Name aliasName)) {
            return null;
        }

        if(!(initializer.getInitializer() instanceof FunctionCall call)) {
            return null;
        }

        if(!(call.getTarget() instanceof Name target) || !"__require".equals(target.getIdentifier()) || call.getArguments().size() != 1) {
            return null;
        }

        AstNode argument = call.getArguments().get(0);
        String packageId = extractStringValue(argument);
        if(packageId == null || !packageId.startsWith("@package/")) {
            return null;
        }

        return new PackageAlias(
            aliasName.getIdentifier(),
            packageId.substring("@package/".length()).replace('/', '.')
        );
    }

    private boolean containsNonPackageRequireCall(AstNode node) {
        boolean[] found = { false };
        visitAst(node, current -> {
            if(found[0]) return;

            if(current instanceof FunctionCall call && call.getTarget() instanceof Name target && "__require".equals(target.getIdentifier())) {
                if(call.getArguments().size() != 1) {
                    found[0] = true;
                    return;
                }

                String argument = extractStringValue(call.getArguments().get(0));
                found[0] = argument == null || !argument.startsWith("@package/");
            }
        });
        return found[0];
    }

    private boolean isRequireHelperDeclaration(AstNode statement) {
        if(!(statement instanceof VariableDeclaration declaration) || declaration.getVariables().size() != 1) {
            return false;
        }

        VariableInitializer initializer = declaration.getVariables().get(0);
        return initializer.getTarget() instanceof Name name
        && "__require".equals(name.getIdentifier())
        && initializer.getInitializer() instanceof FunctionNode;
    }

    private List<Edit> collectPackageMemberAccessEdits(AstRoot root, Map<String, String> packageAliases) {
        List<Edit> edits = new ArrayList<>();
        visitAst(root, node -> {
            if(!(node instanceof PropertyGet propertyGet) || !(propertyGet.getTarget() instanceof Name targetName)) {
                return;
            }

            String packageName = packageAliases.get(targetName.getIdentifier());
            if(packageName == null) {
                return;
            }

            String className = propertyGet.getProperty().getIdentifier();
            if(!className.startsWith("$")) {
                return;
            }

            edits.add(replaceNode(
                propertyGet,
                renderTemplate(JAVA_LOAD_CLASS, Map.of(
                    "className", packageName + "." + stripPackageMarker(className)
                ))
            ));
        });
        return edits;
    }

    private List<Edit> collectDebugWrapEdits(AstRoot root, String source, String fileName, String sourceMapContent) {
        List<Edit> edits = new ArrayList<>();
        visitAst(root, node -> {
            if(!(node instanceof FunctionNode functionNode) || !isCallbackFunction(functionNode) || hasTopLevelTryCatch(functionNode)) {
                return;
            }

            AstNode body = functionNode.getBody();
            if(body == null || body.getLength() < 2 || !isBlockBody(source, body)) {
                return;
            }

            SourceLocation location = resolveSourceLocation(fileName, sourceMapContent, source, functionNode);
            edits.add(insertAt(body.getAbsolutePosition() + 1, DEBUG_TRY_PREFIX));
            edits.add(insertAt(
                body.getAbsolutePosition() + body.getLength() - 1,
                renderTemplate(DEBUG_CATCH_SUFFIX, Map.of(
                    "scriptGroup", escapeJs(scriptGroupFor(fileName)),
                    "file", escapeJs(location.file()),
                    "line", Integer.toString(location.line()),
                    "column", Integer.toString(location.column())
                ))
            ));
        });
        return edits;
    }

    private boolean isCallbackFunction(FunctionNode functionNode) {
        AstNode current = functionNode;
        AstNode parent = functionNode.getParent();

        while(parent != null && "ParenthesizedExpression".equals(parent.getClass().getSimpleName())) {
            current = parent;
            parent = parent.getParent();
        }

        if(!(parent instanceof FunctionCall call)) {
            return false;
        }

        for(AstNode argument : call.getArguments()) {
            if(argument == current) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTopLevelTryCatch(FunctionNode functionNode) {
        AstNode body = functionNode.getBody();
        if(!(body instanceof Node bodyNode)) {
            return false;
        }

        for(AstNode statement : childStatements(bodyNode)) {
            if(statement instanceof TryStatement) {
                return true;
            }
        }
        return false;
    }

    private SourceLocation resolveSourceLocation(String fileName, String sourceMapContent, String source, FunctionNode functionNode) {
        LineColumn generated = lineColumnAt(source, firstMeaningfulPosition(source, functionNode.getBody()));
        if(sourceMapContent != null && !sourceMapContent.isBlank()) {
            try {
                KubeXSourceMapService.ResolvedSourcePosition resolved = KubeXSourceMapService.lookupSource(
                    sourceMapContent,
                    generated.line(),
                    generated.column()
                );
                if(resolved != null) {
                    return new SourceLocation(normalizeSourcePath(resolved.sourcePath()), resolved.sourceLine(), resolved.sourceColumn());
                }
            } catch (Exception ignored) {
            }
        }
        return new SourceLocation(fileName, generated.line(), generated.column());
    }

    private int firstMeaningfulPosition(String source, AstNode body) {
        int start = body.getAbsolutePosition();
        int end = start + body.getLength();
        int current = Math.min(end, start + 1);

        while(current < end) {
            char character = source.charAt(current);
            if(!Character.isWhitespace(character) && character != '{') {
                return current;
            }
            current++;
        }
        return start;
    }

    private LineColumn lineColumnAt(String source, int absolutePosition) {
        int line = 1;
        int column = 1;
        int limit = Math.max(0, Math.min(source.length(), absolutePosition));

        for(int index = 0; index < limit; index++) {
            if(source.charAt(index) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new LineColumn(line, column);
    }

    private String normalizeSourcePath(String sourcePath) {
        String normalized = sourcePath.replace('\\', '/');
        if(normalized.startsWith("../src/")) {
            return normalized.substring("../src/".length());
        }
        if(normalized.startsWith("src/")) {
            return normalized.substring("src/".length());
        }
        return normalized;
    }

    private String scriptGroupFor(String fileName) {
        if(fileName.endsWith(".js")) {
            return fileName.substring(0, fileName.length() - 3);
        }
        return fileName;
    }

    private boolean isBlockBody(String source, AstNode body) {
        String bodySource = slice(source, body).trim();
        return bodySource.startsWith("{") && bodySource.endsWith("}");
    }

    private String extractStringValue(AstNode node) {
        try {
            java.lang.reflect.Method method = node.getClass().getMethod("getValue");
            Object value = method.invoke(node);
            return value instanceof String string ? string : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private String stripPackageMarker(String className) {
        int index = 0;
        while(index < className.length() && className.charAt(index) == '$') {
            index++;
        }
        return index == 0 ? className : className.substring(index);
    }

    private String rewriteRemainingPackageMemberAccesses(String source, Map<String, String> packageAliases) {
        String current = source;
        for(Map.Entry<String, String> alias : packageAliases.entrySet()) {
            current = rewriteRemainingPackageMemberAccess(current, alias.getKey(), alias.getValue());
        }
        return current;
    }

    private String rewriteRemainingPackageMemberAccess(String source, String alias, String packageName) {
        String needle = alias + ".$";
        StringBuilder builder = new StringBuilder();
        int cursor = 0;
        int state = 0;

        while(cursor < source.length()) {
            char current = source.charAt(cursor);

            if(state == 1) {
                builder.append(current);
                if(current == '\\' && cursor + 1 < source.length()) {
                    builder.append(source.charAt(cursor + 1));
                    cursor += 2;
                    continue;
                }
                if(current == '\'') {
                    state = 0;
                }
                cursor++;
                continue;
            }

            if(state == 2) {
                builder.append(current);
                if(current == '\\' && cursor + 1 < source.length()) {
                    builder.append(source.charAt(cursor + 1));
                    cursor += 2;
                    continue;
                }
                if(current == '"') {
                    state = 0;
                }
                cursor++;
                continue;
            }

            if(state == 3) {
                builder.append(current);
                if(current == '\n') {
                    state = 0;
                }
                cursor++;
                continue;
            }

            if(state == 4) {
                builder.append(current);
                if(current == '*' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '/') {
                    builder.append('/');
                    cursor += 2;
                    state = 0;
                    continue;
                }
                cursor++;
                continue;
            }

            if(source.startsWith(needle, cursor) && (cursor == 0 || !isIdentifierPart(source.charAt(cursor - 1)))) {
                int classStart = cursor + alias.length() + 1;
                int nameStart = classStart + 1;
                int nameEnd = nameStart;
                while(nameEnd < source.length() && isIdentifierPart(source.charAt(nameEnd))) {
                    nameEnd++;
                }

                if(nameEnd > nameStart) {
                    String className = source.substring(classStart, nameEnd);
                    builder.append(renderTemplate(JAVA_LOAD_CLASS, Map.of(
                        "className", packageName + "." + stripPackageMarker(className)
                    )));
                    cursor = nameEnd;
                    continue;
                }
            }

            if(current == '\'') {
                builder.append(current);
                cursor++;
                state = 1;
                continue;
            }

            if(current == '"') {
                builder.append(current);
                cursor++;
                state = 2;
                continue;
            }

            if(current == '/' && cursor + 1 < source.length()) {
                char next = source.charAt(cursor + 1);
                if(next == '/') {
                    builder.append("//");
                    cursor += 2;
                    state = 3;
                    continue;
                }
                if(next == '*') {
                    builder.append("/*");
                    cursor += 2;
                    state = 4;
                    continue;
                }
            }
            builder.append(current);
            cursor++;
        }

        return builder.toString();
    }

    private boolean isIdentifierPart(char character) {
        return character == '_' || character == '$' || Character.isLetterOrDigit(character);
    }

    private String slice(String source, AstNode node) {
        int start = node.getAbsolutePosition();
        int end = start + node.getLength();
        return source.substring(start, end);
    }

    private Edit removeNode(AstNode node) {
        return new Edit(node.getAbsolutePosition(), node.getAbsolutePosition() + node.getLength(), "");
    }

    private Edit replaceNode(AstNode node, String replacement) {
        return new Edit(node.getAbsolutePosition(), node.getAbsolutePosition() + node.getLength(), replacement);
    }

    private Edit insertAt(int position, String inserted) {
        return new Edit(position, position, inserted);
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
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    private String prependBanner(String source) {
        if(source.startsWith(BANNER)) return source;
        return BANNER + System.lineSeparator() + source;
    }

    private String prependDebugHelper(String source) {
        if(source.contains("var __kubexReport = typeof __kubexReport === \"function\"")) {
            return source;
        }
        return DEBUG_HELPER + System.lineSeparator() + source;
    }

    private static String loadResource(String path) {
        try (InputStream inputStream = KubeXCompiler.class.getClassLoader().getResourceAsStream(path)) {
            if(inputStream == null) {
                throw new IllegalStateException("Missing compiler resource: " + path);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load compiler resource: " + path, exception);
        }
    }

    private String renderTemplate(String template, Map<String, String> replacements) {
        String current = template;
        for(Map.Entry<String, String> entry : replacements.entrySet()) {
            current = current.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return current;
    }

    private String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void validateRhino(String source) {
        parseScript(source);
    }

    private String failureMessage(RuntimeException exception) {
        if(exception instanceof RhinoException rhinoException) {
            StringBuilder message = new StringBuilder("Failed to lower Rhino syntax for ")
            .append(SOURCE_NAME).append(": ")
            .append(rhinoException.details());
            
            if(rhinoException.lineNumber() > 0) {
                message.append(" at ")
                .append(rhinoException.lineNumber()).append(':')
                .append(Math.max(1, rhinoException.columnNumber()));
            }

            if(rhinoException.lineSource() != null && !rhinoException.lineSource().isBlank()) {
                message.append(" | ").append(rhinoException.lineSource().trim());
            }
            return message.toString();
        }

        String message = exception.getMessage();
        if(message != null && !message.isBlank()) {
            return "Failed to lower Rhino syntax for " + SOURCE_NAME + ": " + message;
        }

        return "Failed to lower Rhino syntax for " + SOURCE_NAME;
    }
}
