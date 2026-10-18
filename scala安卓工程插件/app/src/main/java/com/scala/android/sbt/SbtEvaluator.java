package com.scala.android.sbt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sbt 语法树的求值器：把 {@link SbtParser} 产出的 AST 变成 {@link SbtBuild} 模型。
 *
 * <h3>求值规则（sbt 语义的可用子集）</h3>
 * <ul>
 *   <li>{@code val/lazy val} → 存进环境，后续语句按名字引用；</li>
 *   <li>{@code 键路径 := 值} → 写 setting；{@code += / ++= / -=} 在已有值上做追加/删除；</li>
 *   <li>{@code lazy val root = (project in file(".")).enablePlugins(...).settings(...)}
 *       → 新建一个子工程，并把 {@code settings(...)} 里的 setting 挂到它身上；</li>
 *   <li>{@code "org" % "name" % "ver"} / {@code %%} / {@code % Test} → 依赖坐标；</li>
 *   <li>{@code "名字" at "地址"}、{@code Resolver.mavenCentral} → 仓库；</li>
 *   <li>{@code baseDirectory.value / "x"}、{@code file("x")} → 路径（相对工程目录解析）。</li>
 * </ul>
 *
 * <p>不认识的写法不会让解析失败：记一条 {@link SbtIssue} 警告后按 {@link SbtValue#unknown} 处理。
 */
public final class SbtEvaluator {

    /** 当前求值作用域。 */
    private static final class Scope {
        final Map<String, SbtValue> settings;
        final File baseDir;

        Scope(Map<String, SbtValue> settings, File baseDir) {
            this.settings = settings;
            this.baseDir = baseDir;
        }
    }

    /** 解析出来的一次工程定义。 */
    private static final class ProjectDef {
        boolean isProject;
        SbtNode baseDirNode;
        final List<SbtNode> settingsNodes = new ArrayList<>();
        final List<String> plugins = new ArrayList<>();
        final List<String> dependsOn = new ArrayList<>();
        final List<String> aggregate = new ArrayList<>();
    }

    private final SbtBuild build;
    private final Map<String, SbtValue> env = new LinkedHashMap<>();
    private Scope scope;
    private String fileName = "<sbt>";

    public SbtEvaluator(SbtBuild build) {
        this.build = build;
        this.scope = new Scope(build.settings, build.buildRoot);
    }

    /** 求值一个脚本文件的所有顶层语句。 */
    public void evaluate(String fileName, List<SbtNode> statements) {
        this.fileName = fileName == null ? "<sbt>" : fileName;
        for (SbtNode statement : statements) {
            try {
                evaluateStatement(statement);
            } catch (Throwable error) {
                build.issues.add(SbtIssue.error(this.fileName,
                        statement == null ? 0 : statement.line,
                        statement == null ? 0 : statement.column,
                        "求值失败（已跳过该语句）：" + error));
            }
        }
    }

    // ------------------------------------------------------------ 语句

    private void evaluateStatement(SbtNode node) {
        if (node == null) {
            return;
        }
        switch (node.kind) {
            case DEFN: {
                ProjectDef def = new ProjectDef();
                collectProjectDef(node.right, def);
                if (def.isProject) {
                    buildProject(node, def);
                } else {
                    SbtValue value = eval(node.right, scope);
                    env.put(node.name, value);
                }
                break;
            }
            case ASSIGN:
                applyAssignment(node, scope);
                break;
            case CALL:
                if ("addSbtPlugin".equals(node.name)) {
                    recordPlugin(node);
                    break;
                }
                eval(node, scope);
                break;
            default:
                eval(node, scope);
                break;
        }
    }

    /** 收集 {@code (project in file(".")).enablePlugins(X).settings(...)} 的结构。 */
    private void collectProjectDef(SbtNode node, ProjectDef def) {
        if (node == null) {
            return;
        }
        if (node.kind == SbtNode.Kind.CALL) {
            String name = node.name == null ? "" : node.name;
            switch (name) {
                case "settings":
                    for (SbtNode arg : node.args()) {
                        flattenSettings(arg, def.settingsNodes);
                    }
                    collectProjectDef(node.target, def);
                    return;
                case "enablePlugins":
                    for (SbtNode arg : node.args()) {
                        collectNames(arg, def.plugins);
                    }
                    collectProjectDef(node.target, def);
                    return;
                case "disablePlugins":
                    collectProjectDef(node.target, def);
                    return;
                case "dependsOn":
                    for (SbtNode arg : node.args()) {
                        collectNames(arg, def.dependsOn);
                    }
                    collectProjectDef(node.target, def);
                    return;
                case "aggregate":
                    for (SbtNode arg : node.args()) {
                        collectNames(arg, def.aggregate);
                    }
                    collectProjectDef(node.target, def);
                    return;
                case "configs":
                case "withSources":
                case "withJavadoc":
                    collectProjectDef(node.target, def);
                    return;
                case "in":
                    if (isProjectIdent(node.target)) {
                        def.isProject = true;
                        if (!node.args().isEmpty()) {
                            def.baseDirNode = node.args().get(0);
                        }
                    }
                    collectProjectDef(node.target, def);
                    return;
                case "Project":
                    def.isProject = true;
                    for (SbtNode arg : node.args()) {
                        if (looksLikeFileCall(arg)) {
                            def.baseDirNode = arg;
                        }
                    }
                    return;
                default:
                    collectProjectDef(node.target, def);
                    return;
            }
        }
        if (node.kind == SbtNode.Kind.INFIX && "in".equals(node.name)) {
            if (isProjectIdent(node.target)) {
                def.isProject = true;
                def.baseDirNode = node.right;
            }
            collectProjectDef(node.target, def);
            return;
        }
        if (isProjectIdent(node)) {
            def.isProject = true;
        }
    }

    private void flattenSettings(SbtNode node, List<SbtNode> out) {
        if (node == null) {
            return;
        }
        if (node.kind == SbtNode.Kind.TUPLE) {
            for (SbtNode element : node.args()) {
                out.add(element);
            }
            return;
        }
        if (node.kind == SbtNode.Kind.BLOCK) {
            for (SbtNode element : node.args()) {
                out.add(element);
            }
            return;
        }
        out.add(node);
    }

    private void collectNames(SbtNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.kind == SbtNode.Kind.TUPLE) {
            for (SbtNode element : node.args()) {
                collectNames(element, out);
            }
            return;
        }
        if (node.kind == SbtNode.Kind.IDENT && node.name != null) {
            out.add(node.name);
        } else if (node.kind == SbtNode.Kind.SELECT && node.name != null) {
            out.add(node.name);
        } else if (node.kind == SbtNode.Kind.STRING && node.text != null) {
            out.add(node.text);
        }
    }

    private boolean isProjectIdent(SbtNode node) {
        return node != null && node.kind == SbtNode.Kind.IDENT
                && ("project".equals(node.name) || "Project".equals(node.name));
    }

    private boolean looksLikeFileCall(SbtNode node) {
        return node != null && node.kind == SbtNode.Kind.CALL && "file".equals(node.name);
    }

    /** 建一个子工程，并在它的作用域里求值 {@code .settings(...)}。 */
    private void buildProject(SbtNode definition, ProjectDef def) {
        SbtProjectModel project = new SbtProjectModel();
        project.id = definition.name;
        project.line = definition.line;
        File base = resolveBaseDir(def.baseDirNode);
        project.baseDir = base;
        project.enabledPlugins.addAll(def.plugins);
        project.dependsOn.addAll(def.dependsOn);
        project.aggregate.addAll(def.aggregate);

        Scope previous = scope;
        scope = new Scope(project.settings, base);
        try {
            for (SbtNode setting : def.settingsNodes) {
                if (setting.kind == SbtNode.Kind.ASSIGN) {
                    applyAssignment(setting, scope);
                } else {
                    eval(setting, scope);
                }
            }
        } finally {
            scope = previous;
        }
        build.projects.add(project);
        env.put(project.id, SbtValue.unknown(project.id));
    }

    private File resolveBaseDir(SbtNode node) {
        if (node == null) {
            return build.buildRoot;
        }
        SbtValue value = eval(node, scope);
        File file = value.asFile();
        if (file == null) {
            build.issues.add(SbtIssue.warning(fileName, node.line, node.column,
                    "无法解析工程目录表达式：" + node.describe() + "（回退到构建根目录）"));
            return build.buildRoot;
        }
        if (!file.isAbsolute()) {
            file = new File(build.buildRoot, file.getPath());
        }
        return file;
    }

    // ------------------------------------------------------------ setting

    /** 键路径：{@code ThisBuild / organization} → {@code ThisBuild/organization}。 */
    private String keyPath(SbtNode node) {
        if (node == null) {
            return null;
        }
        switch (node.kind) {
            case IDENT:
            case STRING:
                return node.text;
            case SELECT:
                return node.name;
            case INFIX: {
                if ("/".equals(node.name)) {
                    String left = keyPath(node.target);
                    String right = keyPath(node.right);
                    if (left == null || right == null) {
                        return null;
                    }
                    return left + "/" + right;
                }
                return null;
            }
            default:
                return null;
        }
    }

    private void applyAssignment(SbtNode node, Scope target) {
        String key = keyPath(node.target);
        if (key == null || key.isEmpty()) {
            build.issues.add(SbtIssue.warning(fileName, node.line, node.column,
                    "无法识别的 setting 左值：" + node.target.describe() + "（已跳过）"));
            return;
        }
        SbtValue value = eval(node.right, target);
        mergeInto(target.settings, key, node.name, value);
    }

    private void mergeInto(Map<String, SbtValue> settings, String key, String operator, SbtValue rhs) {
        SbtValue existing = settings.get(key);
        switch (operator) {
            case ":=":
                settings.put(key, rhs);
                return;
            case "+=": {
                if (existing != null && existing.kind == SbtValue.Kind.LIST
                        && rhs.kind != SbtValue.Kind.LIST) {
                    List<SbtValue> values = new ArrayList<>(existing.asList());
                    values.add(rhs);
                    settings.put(key, SbtValue.list(values));
                } else if (existing != null && existing.kind == SbtValue.Kind.LIST) {
                    List<SbtValue> values = new ArrayList<>(existing.asList());
                    values.addAll(rhs.asList());
                    settings.put(key, SbtValue.list(values));
                } else if (existing == null) {
                    settings.put(key, SbtValue.list(rhs.asList()));
                } else {
                    List<SbtValue> values = new ArrayList<>();
                    values.add(existing);
                    values.add(rhs);
                    settings.put(key, SbtValue.list(values));
                }
                return;
            }
            case "++=": {
                List<SbtValue> values = new ArrayList<>();
                if (existing != null) {
                    values.addAll(existing.asList());
                }
                values.addAll(rhs.asList());
                settings.put(key, SbtValue.list(values));
                return;
            }
            case "-=": {
                List<SbtValue> values = new ArrayList<>();
                if (existing != null) {
                    for (SbtValue value : existing.asList()) {
                        if (!sameValue(value, rhs)) {
                            values.add(value);
                        }
                    }
                }
                settings.put(key, SbtValue.list(values));
                return;
            }
            default:
                settings.put(key, rhs);
        }
    }

    private boolean sameValue(SbtValue left, SbtValue right) {
        if (left == null || right == null) {
            return false;
        }
        if (left.kind == SbtValue.Kind.MODULE && right.kind == SbtValue.Kind.MODULE) {
            return left.module != null && left.module.toString().equals(String.valueOf(right.module));
        }
        return String.valueOf(left.asString()).equals(String.valueOf(right.asString()));
    }

    private void recordPlugin(SbtNode node) {
        List<SbtNode> args = node.args();
        if (args.isEmpty()) {
            return;
        }
        List<SbtNode> operands = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        flattenPercent(args.get(0), operands, ops);
        SbtModuleId module = buildModule(operands, ops, node);
        if (module != null) {
            build.plugins.add(new SbtPlugin(module, fileName, node.line));
        }
    }

    // ------------------------------------------------------------ 表达式求值

    private SbtValue eval(SbtNode node, Scope scope) {
        if (node == null) {
            return SbtValue.nullValue();
        }
        switch (node.kind) {
            case STRING:
                return SbtValue.of(node.text);
            case NUMBER:
                return parseNumber(node.text);
            case BOOL:
                return SbtValue.of(Boolean.parseBoolean(node.text));
            case IDENT:
                return evalIdent(node, scope);
            case SELECT:
                return evalSelect(node, scope);
            case CALL:
                return evalCall(node, scope);
            case INFIX:
                return evalInfix(node, scope);
            case TUPLE: {
                List<SbtValue> values = new ArrayList<>();
                for (SbtNode element : node.args()) {
                    values.add(eval(element, scope));
                }
                return SbtValue.list(values);
            }
            case BLOCK: {
                SbtValue last = SbtValue.nullValue();
                for (SbtNode statement : node.args()) {
                    if (statement.kind == SbtNode.Kind.ASSIGN) {
                        applyAssignment(statement, scope);
                    } else {
                        last = eval(statement, scope);
                    }
                }
                return last;
            }
            case ASSIGN:
                applyAssignment(node, scope);
                return SbtValue.nullValue();
            case DEFN: {
                SbtValue value = eval(node.right, scope);
                env.put(node.name, value);
                return value;
            }
            case SKIPPED:
            default:
                return SbtValue.unknown(node.text);
        }
    }

    private SbtValue parseNumber(String text) {
        if (text == null) {
            return SbtValue.of(0L);
        }
        try {
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                SbtValue value = SbtValue.of((long) Double.parseDouble(text));
                value.decimalValue = true;
                value.decimal = Double.parseDouble(text);
                return value;
            }
            return SbtValue.of(Long.parseLong(text.replace("_", "")));
        } catch (NumberFormatException error) {
            return SbtValue.unknown(text);
        }
    }

    private SbtValue evalIdent(SbtNode node, Scope scope) {
        String name = node.name;
        SbtValue defined = env.get(name);
        if (defined != null) {
            return defined;
        }
        if ("Nil".equals(name)) {
            return SbtValue.list(new ArrayList<>());
        }
        if ("None".equals(name)) {
            return SbtValue.nullValue();
        }
        // 配置名（Compile / Test / Provided …）：作为字符串参与 % 运算。
        if (isConfigurationName(name)) {
            return SbtValue.of(name);
        }
        return SbtValue.key(name);
    }

    private SbtValue evalSelect(SbtNode node, Scope scope) {
        SbtNode target = node.target;
        String member = node.name;

        // Resolver.mavenCentral / Resolver.jcenter ...
        if (target != null && (target.isIdent("Resolver") || target.isIdent("Resolvers"))) {
            SbtResolver resolver = SbtResolver.byId(member);
            if (resolver != null) {
                return SbtValue.of(resolver);
            }
            return SbtValue.unknown("Resolver." + member);
        }
        // Seq.empty / List.empty / Seq() 之类
        if (target != null && target.kind == SbtNode.Kind.IDENT
                && isCollectionName(target.name)) {
            if ("empty".equals(member)) {
                return SbtValue.list(new ArrayList<>());
            }
        }
        SbtValue base = eval(target, scope);
        if (base.kind == SbtValue.Kind.KEY) {
            if ("value".equals(member)) {
                File path = resolveKeyToPath(base.text, scope);
                if (path != null) {
                    return SbtValue.of(path);
                }
                return SbtValue.unknown(base.text + ".value");
            }
            return SbtValue.key(base.text + "." + member);
        }
        if (base.kind == SbtValue.Kind.MODULE && "toString".equals(member)) {
            return SbtValue.of(String.valueOf(base));
        }
        return SbtValue.unknown(node.describe());
    }

    private SbtValue evalCall(SbtNode node, Scope scope) {
        String name = node.name == null ? "" : node.name;
        SbtValue target = node.target == null ? null : eval(node.target, scope);

        switch (name) {
            case "Seq":
            case "List":
            case "Vector":
            case "Array":
            case "Set":
            case "Iterable":
            case "collection":
                return evalCollection(node, scope);
            case "Map": {
                Map<String, SbtValue> map = new LinkedHashMap<>();
                for (SbtNode arg : node.args()) {
                    if (arg.kind == SbtNode.Kind.INFIX && "->".equals(arg.name)) {
                        String key = eval(arg.target, scope).asString();
                        SbtValue value = eval(arg.right, scope);
                        if (key != null) {
                            map.put(key, value);
                        }
                    }
                }
                return SbtValue.map(map);
            }
            case "Some": {
                List<SbtNode> args = node.args();
                return args.isEmpty() ? SbtValue.nullValue() : eval(args.get(0), scope);
            }
            case "None":
                return SbtValue.nullValue();
            case "file": {
                List<SbtNode> args = node.args();
                String path = args.isEmpty() ? null : eval(args.get(0), scope).asString();
                if (path == null) {
                    return SbtValue.unknown("file()");
                }
                File file = new File(path);
                if (!file.isAbsolute()) {
                    file = new File(scope.baseDir, path);
                }
                return SbtValue.of(file);
            }
            case "exclude": {
                if (target != null && target.kind == SbtValue.Kind.MODULE) {
                    List<SbtNode> args = node.args();
                    if (args.size() >= 2) {
                        target.module.excludes.add(
                                eval(args.get(0), scope).asString() + ":" + eval(args.get(1), scope).asString());
                    } else {
                        String raw = args.isEmpty() ? null : eval(args.get(0), scope).asString();
                        if (raw != null) {
                            target.module.excludes.add(raw);
                        }
                    }
                    return target;
                }
                return SbtValue.unknown("exclude(...)");
            }
            case "classifier": {
                if (target != null && target.kind == SbtValue.Kind.MODULE && !node.args().isEmpty()) {
                    target.module.classifier = eval(node.args().get(0), scope).asString();
                    return target;
                }
                return target == null ? SbtValue.unknown("classifier") : target;
            }
            case "withSources": {
                if (target != null && target.kind == SbtValue.Kind.MODULE) {
                    target.module.classifier = "sources";
                    return target;
                }
                return target == null ? SbtValue.unknown("withSources") : target;
            }
            case "withJavadoc": {
                if (target != null && target.kind == SbtValue.Kind.MODULE) {
                    target.module.classifier = "javadoc";
                    return target;
                }
                return target == null ? SbtValue.unknown("withJavadoc") : target;
            }
            case "intransitive": {
                return target == null ? SbtValue.unknown("intransitive") : target;
            }
            case "mavenCentral":
                return SbtValue.of(SbtResolver.mavenCentral());
            case "jcenter":
                return SbtValue.of(SbtResolver.byId("jcenter"));
            case "sonatypeRepo":
                if (!node.args().isEmpty()) {
                    String repo = eval(node.args().get(0), scope).asString();
                    return SbtValue.of(new SbtResolver("Sonatype " + repo,
                            "https://oss.sonatype.org/content/repositories/" + repo + "/"));
                }
                return SbtValue.of(SbtResolver.byId("sonatypeRepo"));
            case "in":
                // Project("name", file("dir")) 之类
                return target == null ? SbtValue.unknown("in") : target;
            default:
                break;
        }
        if ("file".equals(name)) {
            return SbtValue.unknown("file(...)");
        }
        if (target != null && target.kind != SbtValue.Kind.NULL) {
            // 未知的链式调用（.dependsOn/.aggregate 等）：保留接收者，避免丢失信息。
            return target;
        }
        return SbtValue.unknown(node.describe());
    }

    private SbtValue evalCollection(SbtNode node, Scope scope) {
        List<SbtValue> values = new ArrayList<>();
        for (SbtNode arg : node.args()) {
            SbtValue value = eval(arg, scope);
            if (value.kind == SbtValue.Kind.LIST) {
                values.addAll(value.asList());
            } else {
                values.add(value);
            }
        }
        return SbtValue.list(values);
    }

    private SbtValue evalInfix(SbtNode node, Scope scope) {
        String op = node.name == null ? "" : node.name;
        switch (op) {
            case "%":
            case "%%": {
                List<SbtNode> operands = new ArrayList<>();
                List<String> ops = new ArrayList<>();
                flattenPercent(node, operands, ops);
                SbtModuleId module = buildModule(operands, ops, node);
                return module == null ? SbtValue.unknown(node.describe()) : SbtValue.of(module);
            }
            case "at": {
                String name = eval(node.target, scope).asString();
                String url = node.right == null ? null : eval(node.right, scope).asString();
                if (url == null) {
                    return SbtValue.unknown(node.describe());
                }
                return SbtValue.of(new SbtResolver(name, url));
            }
            case "/": {
                // 键路径（ThisBuild / name 之类）在赋值里已被单独处理，
                // 这里只剩"路径拼接"。
                SbtValue left = eval(node.target, scope);
                SbtValue right = eval(node.right, scope);
                File base = left.asFile();
                String child = right.asString();
                if (base != null && child != null) {
                    File result = new File(base, child);
                    return SbtValue.of(result);
                }
                if (left.kind == SbtValue.Kind.KEY) {
                    return SbtValue.key(left.text + "/" + right.asString());
                }
                return SbtValue.unknown(node.describe());
            }
            case "++": {
                List<SbtValue> values = new ArrayList<>();
                values.addAll(eval(node.target, scope).asList());
                values.addAll(eval(node.right, scope).asList());
                return SbtValue.list(values);
            }
            case "::": {
                List<SbtValue> values = new ArrayList<>();
                values.add(eval(node.target, scope));
                values.addAll(eval(node.right, scope).asList());
                return SbtValue.list(values);
            }
            case "+": {
                SbtValue left = eval(node.target, scope);
                SbtValue right = eval(node.right, scope);
                if (left.kind == SbtValue.Kind.NUMBER && right.kind == SbtValue.Kind.NUMBER) {
                    return SbtValue.of(left.number + right.number);
                }
                return SbtValue.of(String.valueOf(left.asString()) + right.asString());
            }
            case "->": {
                List<SbtValue> values = new ArrayList<>();
                values.add(eval(node.target, scope));
                values.add(eval(node.right, scope));
                return SbtValue.list(values);
            }
            case "exclude":
            case "classifier": {
                return eval(node.target, scope);
            }
            default:
                break;
        }
        if (op.startsWith("unary")) {
            SbtValue value = eval(node.right, scope);
            if ("unary-".equals(op) && value.kind == SbtValue.Kind.NUMBER) {
                return SbtValue.of(-value.number);
            }
            return value;
        }
        return SbtValue.unknown(node.describe());
    }

    /** 把 {@code a % b %%% c} 的链摊平成 operands + ops（左结合）。 */
    private void flattenPercent(SbtNode node, List<SbtNode> operands, List<String> ops) {
        if (node != null && node.kind == SbtNode.Kind.INFIX
                && ("%".equals(node.name) || "%%".equals(node.name))) {
            flattenPercent(node.target, operands, ops);
            ops.add(node.name);
            operands.add(node.right);
            return;
        }
        operands.add(node);
    }

    /** 由摊平后的 % 链构造依赖坐标；不合法时返回 null。 */
    private SbtModuleId buildModule(List<SbtNode> operands, List<String> ops, SbtNode origin) {
        if (operands.size() < 3) {
            return null;
        }
        String organization = literal(eval(operands.get(0), scope));
        String name = literal(eval(operands.get(1), scope));
        String revision = literal(eval(operands.get(2), scope));
        if (organization == null || name == null || revision == null) {
            return null;
        }
        SbtModuleId module = new SbtModuleId(organization, name, revision);
        module.crossVersion = !ops.isEmpty() && "%%".equals(ops.get(0));
        if (operands.size() >= 4) {
            String configuration = literal(eval(operands.get(3), scope));
            if (configuration != null && !isConfigurationName(configuration)) {
                // 第四段也可能写成 "test->compile" 这种，取目标配置
                int arrow = configuration.indexOf("->");
                if (arrow >= 0) {
                    configuration = configuration.substring(arrow + 2);
                }
            }
            applyConfiguration(module, configuration);
        }
        module.raw = origin.describe();
        return module;
    }

    private void applyConfiguration(SbtModuleId module, String configuration) {
        if (configuration == null) {
            return;
        }
        module.configuration = configuration.toLowerCase(java.util.Locale.ROOT);
        if ("test".equalsIgnoreCase(configuration)) {
            module.test = true;
        }
        if ("provided".equalsIgnoreCase(configuration)) {
            module.provided = true;
        }
    }

    /** 只取字面量：模块坐标必须是字符串，其它情况视为解析失败。 */
    private String literal(SbtValue value) {
        if (value == null) {
            return null;
        }
        if (value.kind == SbtValue.Kind.STRING) {
            return value.text;
        }
        return value.asString();
    }

    private boolean isConfigurationName(String name) {
        if (name == null) {
            return false;
        }
        switch (name) {
            case "Compile":
            case "Test":
            case "Runtime":
            case "Provided":
            case "Optional":
            case "IntegrationTest":
            case "compile":
            case "test":
            case "runtime":
            case "provided":
                return true;
            default:
                return false;
        }
    }

    private boolean isCollectionName(String name) {
        return "Seq".equals(name) || "List".equals(name) || "Vector".equals(name)
                || "Set".equals(name) || "Array".equals(name) || "Iterable".equals(name);
    }

    /** {@code baseDirectory.value} / {@code target.value} 之类的键 → 实际路径。 */
    private File resolveKeyToPath(String key, Scope scope) {
        if (key == null) {
            return null;
        }
        switch (key) {
            case "baseDirectory":
            case "baseDirectory.value":
                return scope.baseDir;
            case "target":
                return new File(scope.baseDir, "target");
            case "sourceDirectory":
                return new File(scope.baseDir, "src");
            case "sourceManaged":
                return new File(scope.baseDir, "target/src_managed");
            case "resourceManaged":
                return new File(scope.baseDir, "target/resource_managed");
            case "crossTarget":
                return new File(scope.baseDir, "target");
            default:
                return null;
        }
    }
}
