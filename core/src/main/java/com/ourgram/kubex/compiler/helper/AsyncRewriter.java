package com.ourgram.kubex.compiler.helper;

import dev.latvian.mods.rhino.CompilerEnvirons;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.ContextFactory;
import dev.latvian.mods.rhino.Node;
import dev.latvian.mods.rhino.Parser;
import dev.latvian.mods.rhino.ast.AstNode;
import dev.latvian.mods.rhino.ast.AstRoot;
import dev.latvian.mods.rhino.ast.Block;
import dev.latvian.mods.rhino.ast.ExpressionStatement;
import dev.latvian.mods.rhino.ast.FunctionCall;
import dev.latvian.mods.rhino.ast.FunctionNode;
import dev.latvian.mods.rhino.ast.Name;
import dev.latvian.mods.rhino.ast.NewExpression;
import dev.latvian.mods.rhino.ast.PropertyGet;
import dev.latvian.mods.rhino.ast.ReturnStatement;
import dev.latvian.mods.rhino.ast.VariableDeclaration;
import dev.latvian.mods.rhino.ast.VariableInitializer;
import dev.latvian.mods.rhino.ast.Yield;
import dev.latvian.mods.rhino.ast.TryStatement;
import dev.latvian.mods.rhino.ast.IfStatement;
import dev.latvian.mods.rhino.ast.ThrowStatement;
import dev.latvian.mods.rhino.ast.ForLoop;
import dev.latvian.mods.rhino.ast.WhileLoop;
import dev.latvian.mods.rhino.ast.BreakStatement;
import dev.latvian.mods.rhino.ast.ContinueStatement;
import dev.latvian.mods.rhino.ast.*;
import dev.latvian.mods.rhino.Token;
import java.util.function.Function;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.lang.reflect.Method;

public final class AsyncRewriter implements CompilerHelper {
    private record Edit(int start, int end, String replacement) {}

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
        "getValue",
        "getReturnValue",
        "getTryBlock",
        "getCatchClauses",
        "getFinallyBlock",
        "getThenPart",
        "getElsePart",
        "getOperand",
        "getElement",
        "getTestExpression",
        "getTrueExpression",
        "getFalseExpression",
        "getCases",
        "getStatements",
        "getStatement",
        "getIterator",
        "getIteratedObject",
        "getArguments",
        "getElements",
        "getVariables",
        "getFunctionName",
        "getParams",
        "getProperty"
    );

    @Override
    public String rewrite(AstRoot root, String source) {
        String rewritten = source;
        while(true) {
            List<Edit> edits = innermost(generatorEdits(parse(rewritten), rewritten));
            if(edits.isEmpty()) break;
            rewritten = applyEdits(rewritten, edits);
        }
        while(true) {
            List<Edit> edits = innermost(promiseEdits(parse(rewritten), rewritten));
            if(edits.isEmpty()) return rewritten;
            rewritten = applyEdits(rewritten, edits);
        }
    }

    private List<Edit> generatorEdits(AstRoot root, String source) {
        List<Edit> edits = new ArrayList<>();
        visit(root, node -> {
            if(node instanceof FunctionNode function && function.isGenerator()) {
                boolean[] nested = {false};
                visit(function.getBody(), child -> {
                    if(child instanceof FunctionNode inner && inner.isGenerator()) nested[0] = true;
                });
                if(!nested[0]) edits.add(new Edit(
                    functionStart(function, source),
                    function.getAbsolutePosition() + function.getLength(), rewriteGenerator(function, source)
                ));
            }
        });
        return edits;
    }

    private String rewriteGenerator(FunctionNode function, String source) {
        if(!(function.getBody() instanceof Block body)) {
            throw unsupported(function, "expression-bodied async functions");
        }

        String name = function.getName();
        String parameters = function.getParams().stream().map(parameter -> slice(source, parameter))
        .reduce((left, right) -> left + ", " + right).orElse("");
        ChainBuilder builder = new ChainBuilder(source);
        String chain = builder.block(body);
        return "function " + name + "(" + parameters + ") {\n"
        + (builder.variables.isEmpty() ? "" : "var " + String.join(", ", builder.variables) + ";\n")
        + "var " + builder.originalArguments + " = arguments;\n"
        + String.join("\n", builder.declarations)
        + "return " + chain + ".then(function(completion) { return completion ? completion.value : undefined; });\n}";
    }

    private final class ChainBuilder {
        private final String source;
        private final Set<String> variables = new java.util.LinkedHashSet<>();
        private final List<String> declarations = new ArrayList<>();
        private final String assigned;
        private int temporary;
        private final String originalArguments;

        private ChainBuilder(String source) {
            this.source = source;
            String name = "__kxAssigned";
            while(source.contains(name)) name += "_";
            assigned = name;
            originalArguments = fresh();
        }

        private String block(AstNode node) {
            String chain = "KubeXPromise.resolve()";
            if(node instanceof Block || node.getClass() == dev.latvian.mods.rhino.ast.Scope.class) {
                for(Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    if(child instanceof AstNode statement) chain = append(chain, statement);
                }
                return chain;
            }
            return append(chain, node);
        }

        private String append(String chain, AstNode statement) {
            return chain + ".then(function(completion) { if(completion) return completion;\n"
            + statement(statement) + "\n}.bind(this))";
        }

        private String expression(AstNode expression) {
            if(expression == null) return "KubeXPromise.resolve()";
            return "KubeXPromise.resolve().then(function() { "
            + lower(expression, value -> "return " + value + ";") + " }.bind(this))";
        }

        private String fresh() { return assigned + "_" + temporary++; }

        private boolean suspends(AstNode node) {
            boolean[] found = {false};
            visit(node, child -> { if(child instanceof Yield) found[0] = true; });
            return found[0];
        }

        private String capture(AstNode node, Function<String, String> next) {
            return lower(node, value -> {
                String saved = fresh();
                return "var " + saved + " = " + value + ";\n" + next.apply(saved);
            });
        }

        private String lower(AstNode node, Function<String, String> next) {
            if(!suspends(node)) return next.apply(original(node));
            if(node instanceof ParenthesizedExpression grouped) return lower(grouped.getExpression(), next);
            if(node instanceof Yield yielded) {
                String value = fresh();
                return "return " + expression(yielded.getValue()) + ".then(function(" + value + ") { "
                + next.apply(value) + " }.bind(this));";
            }
            if(node instanceof ConditionalExpression choice) return capture(choice.getTestExpression(), test ->
                "if(" + test + ") { " + lower(choice.getTrueExpression(), next) + " } else { "
                    + lower(choice.getFalseExpression(), next) + " }");
            if(node instanceof PropertyGet || node instanceof ElementGet) return reference(node, next);
            if(node instanceof Assignment assignment) return reference(assignment.getLeft(), target -> {
                String operator = operator(assignment.getOperator());
                if(operator.equals("=")) return capture(assignment.getRight(), value -> next.apply("(" + target + " = " + value + ")"));
                String old = fresh();
                return "var " + old + " = " + target + ";" + capture(
                    assignment.getRight(),
                    value -> next.apply("(" + target + " = " + old + " " + operator.substring(0, operator.length() - 1) + " " + value + ")")
                );
            });
            if(node instanceof InfixExpression infix) return capture(infix.getLeft(), left -> {
                String operator = operator(infix.getOperator());
                if(infix.getOperator() == Token.AND || infix.getOperator() == Token.OR) {
                    String test = infix.getOperator() == Token.AND ? left : "!(" + left + ")";
                    return "if(" + test + ") { " + lower(infix.getRight(), next) + " } else { " + next.apply(left) + " }";
                }
                return capture(infix.getRight(), right -> next.apply("(" + left + " " + operator + " " + right + ")"));
            });
            if(node instanceof FunctionCall call) {
                if(call instanceof NewExpression) return capture(call.getTarget(), constructor ->
                    arguments(call.getArguments(), 0, new ArrayList<>(), args -> next.apply("new " + constructor + "(" + String.join(",", args) + ")")));
                AstNode target = call.getTarget();
                if(target instanceof PropertyGet property) return capture(
                    property.getTarget(),
                    receiver -> invoke(receiver, receiver + "." + property.getProperty().getIdentifier(), call, next)
                );
                if(target instanceof ElementGet element) return capture(
                    element.getTarget(),
                    receiver -> capture(element.getElement(), key -> invoke(receiver, receiver + "[" + key + "]", call, next))
                );
                return capture(target, function -> arguments(
                    call.getArguments(),
                    0,
                    new ArrayList<>(),
                    args -> next.apply(function + "(" + String.join(",", args) + ")")
                ));
            }
            if(node instanceof ArrayLiteral array) return arguments(
                array.getElements(),
                0,
                new ArrayList<>(),
                values -> next.apply("[" + String.join(",", values) + "]")
            );
            if(node instanceof ObjectLiteral object) return properties(object.getElements(), 0, new ArrayList<>(), next);
            if(node instanceof UnaryExpression unary) {
                if(unary.getOperator() == Token.DELPROP) return reference(unary.getOperand(), value -> next.apply("delete " + value));
                if(unary.getOperator() == Token.INC || unary.getOperator() == Token.DEC) {
                    String op = unary.getOperator() == Token.INC ? "++" : "--";
                    return reference(unary.getOperand(), value -> next.apply(unary.isPostfix() ? value + op : op + value));
                }
                return capture(unary.getOperand(), value -> next.apply("(" + operator(unary.getOperator()) + " " + value + ")"));
            }
            throw unsupported(node, "await in " + node.getClass().getSimpleName());
        }

        private String reference(AstNode node, Function<String, String> next) {
            if(node instanceof ParenthesizedExpression grouped) return reference(grouped.getExpression(), next);

            if(node instanceof PropertyGet property) return capture(
                property.getTarget(), 
                receiver -> next.apply(receiver + "." + property.getProperty().getIdentifier())
            );
            if(node instanceof ElementGet element) return capture(
                element.getTarget(),
                receiver -> capture(element.getElement(), key -> next.apply(receiver + "[" + key + "]"))
            );
            if(node instanceof Name) return next.apply(slice(source, node));
            throw unsupported(node, "assignment target " + node.getClass().getSimpleName());
        }

        private String original(AstNode node) {
            List<Edit> edits = new ArrayList<>();
            visit(node, child -> {
                if(!(child instanceof Name name) || !name.getIdentifier().equals("arguments")) return;
                if(child.getParent() instanceof PropertyGet property && property.getProperty() == child) return;
                for(AstNode parent = child; parent != null && parent != node.getParent(); parent = parent.getParent()) {
                    if(parent instanceof FunctionNode) return;
                }
                edits.add(
                    new Edit(child.getAbsolutePosition() - node.getAbsolutePosition(),
                    child.getAbsolutePosition() - node.getAbsolutePosition() + child.getLength(), originalArguments)
                );
            });
            return applyEdits(slice(source, node), edits);
        }

        private String operator(int token) {
            return switch(token) {
                case Token.ADD, Token.POS -> "+"; case Token.SUB, Token.NEG -> "-";
                case Token.MUL -> "*"; case Token.DIV -> "/"; case Token.MOD -> "%";
                case Token.EQ -> "=="; case Token.NE -> "!="; case Token.SHEQ -> "==="; case Token.SHNE -> "!==";
                case Token.LT -> "<"; case Token.LE -> "<="; case Token.GT -> ">"; case Token.GE -> ">=";
                case Token.AND -> "&&"; case Token.OR -> "||"; case Token.COMMA -> ",";
                case Token.BITAND -> "&"; case Token.BITOR -> "|"; case Token.BITXOR -> "^";
                case Token.LSH -> "<<"; case Token.RSH -> ">>"; case Token.URSH -> ">>>";
                case Token.IN -> "in"; case Token.INSTANCEOF -> "instanceof";
                case Token.NOT -> "!"; case Token.BITNOT -> "~"; case Token.TYPEOF -> "typeof"; case Token.VOID -> "void";
                case Token.ASSIGN -> "="; case Token.ASSIGN_ADD -> "+="; case Token.ASSIGN_SUB -> "-=";
                case Token.ASSIGN_MUL -> "*="; case Token.ASSIGN_DIV -> "/="; case Token.ASSIGN_MOD -> "%=";
                case Token.ASSIGN_BITAND -> "&="; case Token.ASSIGN_BITOR -> "|="; case Token.ASSIGN_BITXOR -> "^=";
                case Token.ASSIGN_LSH -> "<<="; case Token.ASSIGN_RSH -> ">>="; case Token.ASSIGN_URSH -> ">>>=";
                default -> throw new IllegalStateException("Unsupported async operator: " + token);
            };
        }

        private String invoke(String receiver, String target, FunctionCall call, Function<String, String> next) {
            String function = fresh();
            return "var " + function + " = " + target + ";" + arguments(call.getArguments(), 0, new ArrayList<>(), args ->
            next.apply(function + ".call(" + receiver + (args.isEmpty() ? "" : "," + String.join(",", args)) + ")"));
        }

        private String arguments(List<AstNode> nodes, int index, List<String> values, Function<List<String>, String> next) {
            if(index == nodes.size()) return next.apply(values);
            if(nodes.get(index) instanceof EmptyExpression) {
                var copy = new ArrayList<>(values); copy.add("");
                return arguments(nodes, index + 1, copy, next);
            }
            return capture(nodes.get(index), value -> {
                var copy = new ArrayList<>(values); copy.add(value);
                return arguments(nodes, index + 1, copy, next);
            });
        }

        private String properties(List<ObjectProperty> properties, int index, List<String> values, Function<String, String> next) {
            if(index == properties.size()) return next.apply("({" + String.join(",", values) + "})");
            ObjectProperty property = properties.get(index);
            if(property.isMethod()) throw unsupported(property, "unlowered object methods");
            return capture(property.getRight(), value -> {
                var copy = new ArrayList<>(values); copy.add(slice(source, property.getLeft()) + ":" + value);
                return properties(properties, index + 1, copy, next);
            });
        }

        private String statement(AstNode node) {
            if(node instanceof Block || node.getClass() == dev.latvian.mods.rhino.ast.Scope.class) return "return " + block(node) + ";";
            if(node instanceof ReturnStatement returned) {
                return returned.getReturnValue() == null ? "return {kind: 'return'};"
                : lower(returned.getReturnValue(), value -> "return {kind: 'return', value: " + value + "};");
            }
            if(node instanceof BreakStatement broken) return "return {kind: 'break', label: "
                + (broken.getBreakLabel() == null ? "null" : "'" + broken.getBreakLabel().getIdentifier() + "'") + "};";
            if(node instanceof ContinueStatement continued) return "return {kind: 'continue', label: "
                + (continued.getLabel() == null ? "null" : "'" + continued.getLabel().getIdentifier() + "'") + "};";
            if(node instanceof LabeledStatement labeled) {
                String labelTest = labeled.getLabels().stream().map(label -> "completion.label === '" + label.getName() + "'").reduce((left, right) -> left + " || " + right).orElse("false");
                return "return " + block(labeled.getStatement()) + ".then(function(completion) { if(completion && completion.kind === 'break' && ("
                + labelTest + ")) return; return completion; });";
            }
            if(node instanceof ForLoop loop) {
                String init = loop.getInitializer().getLength() == 0 ? "" : loop.getInitializer() instanceof VariableDeclaration
                ? statement(loop.getInitializer()) : "return " + expression(loop.getInitializer()) + ";";
                return "return KubeXPromise.resolve().then(function() { " + init + " }.bind(this)).then(function() { "
                + loop(loop.getCondition(), loop.getIncrement(), loop.getBody()) + " }.bind(this));";
            }
            if(node instanceof WhileLoop loop) return loop(loop.getCondition(), null, loop.getBody());
            if(node instanceof ForInLoop loop && !loop.isForOf() && !loop.isForEach()) {
                AstNode target = loop.getIterator();
                if(target instanceof VariableDeclaration declaration) {
                    target = declaration.getVariables().get(0).getTarget();
                    if(target instanceof Name name) variables.add(name.getIdentifier());
                }
                AstNode iterator = target;
                String object = fresh(), keys = fresh(), key = fresh(), index = fresh(), next = fresh();
                return capture(loop.getIteratedObject(), value -> "var " + object + " = " + value + ", " + keys + " = [], "
                + index + " = 0; for(var " + key + " in " + object + ") " + keys + ".push(" + key + ");"
                + "var receiver = this; function " + next + "() { if(" + index + " >= " + keys + ".length) return; var "
                + key + " = " + keys + "[" + index + "++]; if(!(" + key + " in " + object + ")) return " + next + ".call(receiver);"
                + reference(iterator, name -> name + " = " + key + ";return " + block(loop.getBody())
                    + ".then(function(completion) { if(completion && (completion.kind === 'return' || completion.label)) return completion;"
                    + "if(completion && completion.kind === 'break') return; return " + next + ".call(receiver); }.bind(receiver));")
                + "} return " + next + ".call(receiver);");
            }
            if(node instanceof DoLoop loop) {
                String first = fresh();
                return "var " + first + " = true;" + loop(loop.getCondition(), null, loop.getBody(), first);
            }
            if(node instanceof SwitchStatement switched) {
                String discriminant = fresh();
                String selected = fresh();
                String chain = "KubeXPromise.resolve()";
                for(int index = 0; index < switched.getCases().size(); index++) {
                    String bodyChain = "KubeXPromise.resolve()";
                    var statements = switched.getCases().get(index).getStatements();
                    if(statements != null) for(AstNode child : statements) bodyChain = append(bodyChain, child);
                    chain += ".then(function(completion) { if(completion) return completion; if(" + selected
                    + " <= " + index + ") return " + bodyChain + "; }.bind(this))";
                }
                chain += ".then(function(completion) { return completion && completion.kind === 'break' && !completion.label ? undefined : completion; })";
                return "return " + expression(switched.getExpression()) + ".then(function(" + discriminant + ") { return "
                + selectCase(switched.getCases(), 0, discriminant) + ".then(function(" + selected + ") { return "
                + chain + "; }.bind(this)); }.bind(this));";
            }
            if(node instanceof FunctionNode function) {
                declarations.add(source.substring(functionStart(function, source), function.getAbsolutePosition() + function.getLength()));
                return "";
            }
            if(node instanceof VariableDeclaration declaration) {
                String chain = "KubeXPromise.resolve()";
                for(VariableInitializer variable : declaration.getVariables()) {
                    if(!(variable.getTarget() instanceof Name name)) throw unsupported(node, "unlowered destructuring");
                    variables.add(name.getIdentifier());
                    if(variable.getInitializer() != null) chain += ".then(function() { "
                    + lower(variable.getInitializer(), value -> name.getIdentifier() + " = " + value + ";") + " }.bind(this))";
                }
                return "return " + chain + ";";
            }
            if(node instanceof ExpressionStatement expression) {
                return lower(expression.getExpression(), value -> value + ";");
            }
            if(node instanceof IfStatement branch) {
                return lower(branch.getCondition(), condition -> "return (" + condition + ") ? "
                + block(branch.getThenPart()) + " : " + (branch.getElsePart() == null ? "undefined" : block(branch.getElsePart())) + ";");
            }
            if(node instanceof TryStatement tried) {
                String chain = block(tried.getTryBlock());
                if(!tried.getCatchClauses().isEmpty()) {
                    var caught = tried.getCatchClauses().get(0);
                    chain += ".catch(function(" + caught.getVarName().getIdentifier() + ") { return " + block(caught.getBody()) + "; }.bind(this))";
                }
                if(tried.getFinallyBlock() != null) {
                    String cleanup = block(tried.getFinallyBlock());
                    chain += ".then(function(completion) { return " + cleanup
                    + ".then(function(finalCompletion) { return finalCompletion || completion; }); }.bind(this), "
                    + "function(error) { return " + cleanup
                    + ".then(function(finalCompletion) { if(finalCompletion) return finalCompletion; throw error; }); }.bind(this))";
                }
                return "return " + chain + ";";
            }
            if(node instanceof ThrowStatement thrown) return lower(thrown.getExpression(), value -> "throw " + value + ";");
            if(node instanceof dev.latvian.mods.rhino.ast.EmptyExpression || node instanceof dev.latvian.mods.rhino.ast.EmptyStatement) return "";
            throw unsupported(node, node.getClass().getSimpleName());
        }

        private String loop(AstNode condition, AstNode increment, AstNode body) {
            return loop(condition, increment, body, null);
        }

        private String selectCase(List<SwitchCase> cases, int index, String discriminant) {
            if(index == cases.size()) {
                int fallback = cases.size();
                for(int i = 0; i < cases.size(); i++) if(cases.get(i).isDefault()) fallback = i;
                return "KubeXPromise.resolve(" + fallback + ")";
            }
            if(cases.get(index).isDefault()) return selectCase(cases, index + 1, discriminant);
            String candidate = fresh();
            return expression(cases.get(index).getExpression()) + ".then(function(" + candidate + ") { return "
            + discriminant + " === " + candidate + " ? " + index + " : "
            + selectCase(cases, index + 1, discriminant) + "; }.bind(this))";
        }

        private String loop(AstNode condition, AstNode increment, AstNode body, String first) {
            String check = condition == null || condition.getLength() == 0 ? "KubeXPromise.resolve(true)"
            : "KubeXPromise.resolve().then(function() { " + lower(condition, value -> "return !!(" + value + ");") + " }.bind(this))";
            if(first != null) check = "(" + first + " ? (" + first + " = false, KubeXPromise.resolve(true)) : " + check + ")";
            String advance = increment == null || increment.getLength() == 0 ? "KubeXPromise.resolve()"
            : "KubeXPromise.resolve().then(function() { " + lower(increment, value -> value + ";") + " }.bind(this))";
            String labels = "false";
            for(AstNode parent = body.getParent().getParent(); parent instanceof LabeledStatement labeled; parent = parent.getParent()) {
                for(Label label : labeled.getLabels()) labels += " || completion.label === '" + label.getName() + "'";
            }
            return "var receiver = this; function iterate() { return " + check
            + ".then(function(condition) { if(!condition) return; return " + block(body)
            + ".then(function(completion) { if(completion && completion.kind === 'return') return completion; "
            + "if(completion && completion.label && !(" + labels + ")) return completion; "
            + "if(completion && completion.kind === 'break') return; return " + advance
            + ".then(function() { return iterate.call(receiver); }); }.bind(receiver)); }.bind(receiver)); } return iterate.call(receiver);";
        }
    }

    private List<Edit> promiseEdits(AstRoot root, String source) {
        List<Edit> edits = new ArrayList<>();
        visit(root, node -> {
            if(node instanceof NewExpression expression && expression.getTarget() instanceof Name name && "Promise".equals(name.getIdentifier())) {
                edits.add(new Edit(
                    expression.getAbsolutePosition(),
                    expression.getAbsolutePosition() + expression.getLength(),
                    "new KubeXPromise(" + arguments(source, expression) + ")"
                ));
                return;
            }

            if(!(node instanceof FunctionCall call) || !(call.getTarget() instanceof PropertyGet property)) return;
            if(!(property.getTarget() instanceof Name target) || !"Promise".equals(target.getIdentifier())) return;

            String replacement = switch(property.getProperty().getIdentifier()) {
                case "resolve" -> "KubeXPromise.resolve(" + arguments(source, call) + ")";
                case "reject" -> "KubeXPromise.reject(" + arguments(source, call) + ")";
                case "all" -> "KubeXPromise.all(" + arguments(source, call) + ")";
                case "allSettled" -> "KubeXPromise.allSettled(" + arguments(source, call) + ")";
                case "race" -> "KubeXPromise.race(" + arguments(source, call) + ")";
                case "any" -> "KubeXPromise.any(" + arguments(source, call) + ")";
                default -> null;
            };
            if(replacement != null) {
                edits.add(new Edit(call.getAbsolutePosition(), call.getAbsolutePosition() + call.getLength(), replacement));
            }
        });
        return edits;
    }

    private AstRoot parse(String source) {
        Context context = CONTEXT_FACTORY.enter();
        CompilerEnvirons environs = new CompilerEnvirons();
        environs.initFromContext(context);
        AstRoot root = new Parser(context, environs).parse(source, "kubex-async.js", 1);
        visit(root, node -> {
            if(node instanceof LabeledStatement label && label.getStatement() instanceof Loop loop) {
                AstNode body = loop.getBody();
                body.setPosition(body.getPosition() + label.getAbsolutePosition());
            }
        });
        return root;
    }

    private String arguments(String source, FunctionCall call) {
        return call.getArguments().stream().map(argument -> slice(source, argument))
        .reduce((left, right) -> left + ", " + right).orElse("");
    }

    private String applyEdits(String source, List<Edit> edits) {
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder rewritten = new StringBuilder(source);
        int nextStart = source.length();
        for(Edit edit : edits) {
            if(edit.end() > nextStart) {
                throw new IllegalStateException("KubeX async compiler does not support nested Promise expressions");
            }
            rewritten.replace(edit.start(), edit.end(), edit.replacement());
            nextStart = edit.start();
        }
        return rewritten.toString();
    }

    private List<Edit> innermost(List<Edit> edits) {
        List<Edit> selected = new ArrayList<>();
        for(Edit candidate : edits) {
            boolean containsAnotherEdit = false;
            for(Edit other : edits) {
                if(candidate == other) continue;
                if(candidate.start() <= other.start() && other.end() <= candidate.end()) {
                    containsAnotherEdit = true;
                    break;
                }
            }
            if(!containsAnotherEdit) selected.add(candidate);
        }
        return selected;
    }

    private String slice(String source, AstNode node) {
        int start = node.getAbsolutePosition();
        return source.substring(start, start + node.getLength());
    }

    private int functionStart(FunctionNode function, String source) {
        int start = source.lastIndexOf("function", function.getAbsolutePosition());
        if(start < 0) {
            throw unsupported(function, "an unrecognizable async function");
        }
        return start;
    }


    private IllegalStateException unsupported(AstNode node, String feature) {
        return new IllegalStateException("KubeX async compiler does not support " + feature + " at line " + (node.getLineno() + 1));
    }

    private void visit(AstNode root, Consumer<AstNode> consumer) {
        visit(root, java.util.Collections.newSetFromMap(new IdentityHashMap<>()), consumer);
    }

    private void visit(AstNode node, Set<AstNode> visited, Consumer<AstNode> consumer) {
        if(node == null || !visited.add(node)) return;
        consumer.accept(node);
        if(node instanceof FunctionNode function && function.getBody() instanceof AstNode body) {
            visit(body, visited, consumer);
        }
        if(node instanceof FunctionCall call) {
            visit(call.getTarget(), visited, consumer);
            for(AstNode argument : call.getArguments()) {
                visit(argument, visited, consumer);
            }
        }
        for(Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if(child instanceof AstNode astChild) visit(astChild, visited, consumer);
        }
        for(String getterName : AST_CHILD_GETTERS) {
            try {
                Method getter = node.getClass().getMethod(getterName);
                if(getter.getParameterCount() != 0) continue;
                Object value = getter.invoke(node);
                if(value instanceof AstNode astChild) {
                    visit(astChild, visited, consumer);
                } else if(value instanceof List<?> list) {
                    for(Object entry : list) {
                        if(entry instanceof AstNode astChild) visit(astChild, visited, consumer);
                    }
                }
            } catch(ReflectiveOperationException ignored) {
            }
        }
    }
}