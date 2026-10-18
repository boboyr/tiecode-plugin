package com.scala.android.project;

import android.content.Context;

import com.rapid.api.ext.plugin.AndroidPlugins;
import com.scala.android.compiler.ScalaCompilerApi;
import com.scala.android.tool.ScalaToolchain;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "类还有哪些抽象成员没实现" —— 给「实现所有抽象方法」快速修复提供待插入的骨架。
 *
 * <h3>为什么不能复用交互式补全</h3>
 * {@code class X needs to be abstract, since: it has N unimplemented members} 这条错是
 * 编译器<b>后置阶段</b>（refchecks）才报的，交互式驱动只跑到 typer，永远不会报。
 * 所以这里走完整编译：把当前文档写成一个临时 .scala 文件，交给编译器，直接从它给的
 * 错误消息里取现成的骨架 —— 那条消息本身就带着可直接使用的
 * {@code def perform(arg: ...): Unit = ???} 行（dotty 自己写着
 * "For convenience, these are usable as stub implementations"）。
 *
 * <p>代价是一次完整编译（几百毫秒到几秒），所以只在用户主动点「实现所有抽象方法」时才算，
 * 并按"文件内容 hash"缓存结果。
 */
public final class ScalaAbstractMembers {

    private static final Map<String, List<String>> CACHE = new ConcurrentHashMap<>();

    private ScalaAbstractMembers() {
    }

    /**
     * 取应当插入的骨架行（已经去掉缩进和注释行）。
     *
     * @param content   当前文档全文
     * @param classpath 编译 classpath（含工程依赖）
     * @return 骨架行；没有未实现成员或编译通过时返回空表
     */
    public static List<String> stubs(String content, List<File> classpath) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        String key = Integer.toHexString(content.hashCode()) + ":" + content.length();
        List<String> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        List<String> stubs = compileStubs(content, classpath);
        if (stubs != null) {
            if (CACHE.size() > 16) {
                CACHE.clear();
            }
            CACHE.put(key, stubs);
        }
        return stubs == null ? Collections.<String>emptyList() : stubs;
    }

    /** 一条"类还有抽象成员没实现"的结果（类名用于在编辑器里定位）。 */
    public static final class Missing {
        public final String className;
        public final List<String> stubs;

        Missing(String className, List<String> stubs) {
            this.className = className;
            this.stubs = stubs;
        }
    }

    /** 取"哪个类还缺哪些方法"；没有就返回 null。 */
    public static Missing missing(String content, List<File> classpath) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        String key = Integer.toHexString(content.hashCode()) + ":" + content.length();
        Missing cached = MISSING_CACHE.get(key);
        if (cached != null) {
            return cached.stubs.isEmpty() ? null : cached;
        }
        Missing missing = compileMissing(content, classpath);
        if (missing != null) {
            if (MISSING_CACHE.size() > 16) {
                MISSING_CACHE.clear();
            }
            MISSING_CACHE.put(key, missing);
        }
        return missing == null || missing.stubs.isEmpty() ? null : missing;
    }

    private static final Map<String, Missing> MISSING_CACHE = new ConcurrentHashMap<>();

    private static Missing compileMissing(String content, List<File> classpath) {
        File workDir = null;
        try {
            Context context = AndroidPlugins.getContext(ScalaAbstractMembers.class);
            File base = context == null ? null : context.getCacheDir();
            if (base == null) {
                return null;
            }
            workDir = new File(base, "scala-abstract-check");
            if (!workDir.isDirectory() && !workDir.mkdirs()) {
                return null;
            }
            File source = new File(workDir, "Current.scala");
            File out = new File(workDir, "out");
            if (!out.isDirectory() && !out.mkdirs()) {
                return null;
            }
            com.rapid.util.file.FileUtils.writeFile(content, source);
            ScalaCompilerApi.Result result = ScalaCompilerApi.compile(
                    Collections.singletonList(source), out, classpath);
            for (ScalaCompilerApi.DiagnosticInfo info : result.diagnostics) {
                if (info.message != null && info.message.contains("unimplemented members")) {
                    return new Missing(classNameOf(info.message), parseStubs(info.message));
                }
            }
            return new Missing("", Collections.<String>emptyList());
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /** 从 {@code class X needs to be abstract...} 里取类名。 */
    private static String classNameOf(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:class|trait|object)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> compileStubs(String content, List<File> classpath) {
        File workDir = null;
        try {
            Context context = AndroidPlugins.getContext(ScalaAbstractMembers.class);
            File base = context == null ? null : context.getCacheDir();
            if (base == null) {
                return null;
            }
            workDir = new File(base, "scala-abstract-check");
            if (!workDir.isDirectory() && !workDir.mkdirs()) {
                return null;
            }
            File source = new File(workDir, "Current.scala");
            File out = new File(workDir, "out");
            if (!out.isDirectory() && !out.mkdirs()) {
                return null;
            }
            com.rapid.util.file.FileUtils.writeFile(content, source);
            ScalaCompilerApi.Result result = ScalaCompilerApi.compile(
                    Collections.singletonList(source), out, classpath);
            for (ScalaCompilerApi.DiagnosticInfo info : result.diagnostics) {
                if (info.message != null && info.message.contains("unimplemented members")) {
                    return parseStubs(info.message);
                }
            }
            return Collections.emptyList();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (workDir != null) {
                deleteRecursively(workDir);
            }
        }
    }

    /**
     * 从编译器消息里挑出骨架行。
     *
     * <p>消息形如（这里用 &#47;* 代替注释开头，避免和 JavaDoc 自己的结束符冲突）：
     * <pre>
     * class Tesmenu needs to be abstract, since:
     * it has 4 unimplemented members.
     * &#47;* As seen from class Tesmenu, the missing signatures are as follows.
     *  *  For convenience, these are usable as stub implementations.
     *  *&#47;
     *   // Members declared in com.rapid.framework.action.Action1
     *   def perform(arg: com.rapid.framework.data.DataHolder): Unit = ???
     *   // Members declared in com.rapid.framework.action.menu.MenuAction
     *   def getIcon(): android.graphics.drawable.Drawable = ???
     * </pre>
     *
     * <p>所以只要挑出以 {@code def}/{@code val}/{@code var} 开头、且带 {@code = ???} 的行即可。
     */
    private static List<String> parseStubs(String message) {
        List<String> stubs = new ArrayList<>();
        for (String raw : message.split("\\R")) {
            String line = raw.trim();
            if (!(line.startsWith("def ") || line.startsWith("val ") || line.startsWith("var "))) {
                continue;
            }
            if (line.endsWith("= ???") || line.contains("= ???")) {
                stubs.add(line);
            }
        }
        return stubs;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    /** 编译/补全用的 classpath。 */
    public static List<File> classpath() {
        try {
            Context context = AndroidPlugins.getContext(ScalaAbstractMembers.class);
            if (context != null) {
                return ScalaToolchain.compileClasspath(context);
            }
        } catch (Throwable ignored) {
            // 拿不到就没有 classpath，交给编译器自行失败。
        }
        return Collections.emptyList();
    }

    /** 便于调试：直接用已经算好的结果。 */
    static List<String> cached(String content) {
        return CACHE.get(Integer.toHexString(content == null ? 0 : content.hashCode())
                + ":" + (content == null ? 0 : content.length()));
    }
}
