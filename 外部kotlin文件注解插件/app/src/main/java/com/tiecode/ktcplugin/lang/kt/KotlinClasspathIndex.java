package com.tiecode.ktcplugin.lang.kt;

import android.content.Context;
import android.util.Log;

import com.rapid.api.Platform;
import com.rapid.api.ext.plugin.AndroidPlugins;
import com.rapid.api.framework.project.ProjectServiceKeys;
import com.rapid.api.framework.project.toolchain.ToolchainService;
import com.rapid.api.project.Project;
import com.rapid.api.project.toolchain.Tool;
import com.rapid.api.project.toolchain.android.sdk.AndroidSdk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 「简单类名 → 全限定名」索引，用于补全时<b>自动导包</b>。
 *
 * <p>补全出 {@code Activity} 这类类名还不够：Kotlin 里写 {@code Activity} 必须先
 * {@code import android.app.Activity}，否则用户选中候选后代码根本编译不过。
 * 本类负责建立这个映射，{@link KotlinCompletionProvider} 再用
 * {@code CompletionItem.additionalTextEdits} 把 import 插进文件顶部
 * ——与 scala 控制台插件的做法一致。
 *
 * <h3>数据来源</h3>
 * <ul>
 *   <li><b>android.jar</b>（编译目标平台，含 android.* 与 java.*）：这是
 *       {@code Activity} / {@code System} 这类最常要导包的来源；</li>
 *   <li>结绳工具链目录下的其它 jar（依赖库、运行时）；</li>
 * </ul>
 *
 * <h3>为什么放在后台线程</h3>
 * 索引一个 20+MB 的 android.jar 是百毫秒级，但如果碰上大依赖库可能到秒级。
 * 补全线程<b>从不等待</b>索引：先用当前（可能为空的）快照，同时触发一次后台构建，
 * 建好后下一次输入就能看到。这与 scala 插件的 classpath 索引策略相同。
 *
 * <h3>歧义处理</h3>
 * 同一个简单名对应多个包（例如 {@code Handler}）时<b>整个丢弃</b>：
 * 猜错包会插进一个编译不过的 import，比不提示更糟。
 */
public final class KotlinClasspathIndex {

    private static final String TAG = "KotlinLang";

    /** 一次索引扫描的 jar 数量上限，防止挂在超大的依赖目录上。 */
    private static final int MAX_ARCHIVES = 24;

    /** 单个归档超过该大小就跳过（android.jar 20~30MB 属于正常范围）。 */
    private static final long MAX_ARCHIVE_BYTES = 96L * 1024L * 1024L;

    /**
     * 兜底用：当连 android.jar 都拿不到时，至少让最常见的几个类型能补全并导包。
     *
     * <p>值来自公开文档，与 android.jar 索引冲突时以索引为准。
     */
    private static final String[][] FALLBACK_TYPES = {
            {"Activity", "android.app.Activity"},
            {"AppCompatActivity", "androidx.appcompat.app.AppCompatActivity"},
            {"Application", "android.app.Application"},
            {"Fragment", "androidx.fragment.app.Fragment"},
            {"AlertDialog", "android.app.AlertDialog"},
            {"Notification", "android.app.Notification"},
            {"Service", "android.app.Service"},
            {"BroadcastReceiver", "android.content.BroadcastReceiver"},
            {"ContentResolver", "android.content.ContentResolver"},
            {"SharedPreferences", "android.content.SharedPreferences"},
            {"Dialog", "android.app.Dialog"},
            {"Intent", "android.content.Intent"},
            {"Context", "android.content.Context"},
            {"Bundle", "android.os.Bundle"},
            {"Handler", "android.os.Handler"},
            {"Looper", "android.os.Looper"},
            {"Message", "android.os.Message"},
            {"Build", "android.os.Build"},
            {"View", "android.view.View"},
            {"ViewGroup", "android.view.ViewGroup"},
            {"LayoutInflater", "android.view.LayoutInflater"},
            {"Gravity", "android.view.Gravity"},
            {"Button", "android.widget.Button"},
            {"TextView", "android.widget.TextView"},
            {"ImageView", "android.widget.ImageView"},
            {"EditText", "android.widget.EditText"},
            {"LinearLayout", "android.widget.LinearLayout"},
            {"FrameLayout", "android.widget.FrameLayout"},
            {"RecyclerView", "androidx.recyclerview.widget.RecyclerView"},
            {"Toast", "android.widget.Toast"},
            {"Log", "android.util.Log"},
            {"System", "java.lang.System"},
            {"Collections", "java.util.Collections"},
            {"Arrays", "java.util.Arrays"},
            {"ArrayList", "java.util.ArrayList"},
            {"HashMap", "java.util.HashMap"},
            {"List", "java.util.List"},
            {"Map", "java.util.Map"},
            {"Set", "java.util.Set"},
            {"Date", "java.util.Date"},
            {"Calendar", "java.util.Calendar"},
            {"Random", "java.util.Random"},
            {"Scanner", "java.util.Scanner"},
            {"File", "java.io.File"},
            {"InputStream", "java.io.InputStream"},
            {"OutputStream", "java.io.OutputStream"},
            {"BufferedReader", "java.io.BufferedReader"},
            {"IOException", "java.io.IOException"},
            {"URL", "java.net.URL"},
            {"UUID", "java.util.UUID"},
            {"Pattern", "java.util.regex.Pattern"},
            {"BigDecimal", "java.math.BigDecimal"},
            {"Objects", "java.util.Objects"},
            {"Optional", "java.util.Optional"},
    };

    private static final KotlinClasspathIndex INSTANCE = new KotlinClasspathIndex();

    public static KotlinClasspathIndex get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    private final AtomicBoolean building = new AtomicBoolean(false);

    /** 已建好的索引（构建完成后整体替换，读侧无锁）。 */
    private volatile Map<String, String> types = Collections.emptyMap();

    /** 当前索引用的是哪些归档（换了工程/工具链就重建）。 */
    private volatile String indexKey;

    private KotlinClasspathIndex() {
    }

    /** 当前快照；可能还在后台构建而返回空表。 */
    public Map<String, String> snapshot() {
        return types;
    }

    /**
     * 直接塞入一张已建好的索引，跳过工具链探测。
     *
     * <p>给桌面单测用：真实路径要读 {@code Platform.getService(TOOLCHAIN)} 与
     * 插件 Context，桌面 JVM 上这些宿主类是抛异常的桩。生产代码不会调用它。
     */
    void injectForTest(Map<String, String> built) {
        synchronized (lock) {
            types = built == null ? Collections.<String, String>emptyMap() : built;
            indexKey = "injected";
        }
    }

    public boolean isReady() {
        return !types.isEmpty();
    }

    public int size() {
        return types.size();
    }

    /**
     * 确保索引已针对当前工具链建立（不阻塞调用方）。
     *
     * <p>补全线程调这个：命中缓存就直接返回，没命中就丢给后台线程去建。
     */
    public void ensureBuilt() {
        if (building.get()) {
            return;
        }
        final List<File> archives = classpathArchives();
        if (archives.isEmpty()) {
            // 连归档都没有：至少把兜底表用起来，不能一个候选都不给。
            if (types.isEmpty()) {
                types = fallbackTypes();
            }
            return;
        }
        String key = keyOf(archives);
        if (key.equals(indexKey) && !types.isEmpty()) {
            return;
        }
        if (!building.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> built = build(archives);
                    if (!built.isEmpty()) {
                        types = built;
                        indexKey = key;
                        Log.i(TAG, "Kotlin 补全索引就绪: " + built.size() + " 个类型，"
                                + archives.size() + " 个归档");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Kotlin 补全索引构建失败（自动导包将不可用）", t);
                } finally {
                    building.set(false);
                }
            }
        }, "kotlin-classpath-index");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------
    // 索引构建
    // ------------------------------------------------------------------

    /**
     * 扫描归档，得到「唯一简单名 → 全限定名」。歧义名字整体丢弃。
     *
     * <p>公开是为了能在桌面单测里直接喂一个造好的 jar 验证索引规则
     * （真实入口 {@link #ensureBuilt()} 依赖宿主工具链，桌面跑不了）。
     */
    public static Map<String, String> build(List<File> archives) {
        Map<String, String> unique = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (File archive : archives) {
            if (archive == null || !archive.isFile() || archive.length() > MAX_ARCHIVE_BYTES) {
                continue;
            }
            JarFile jar = null;
            try {
                jar = new JarFile(archive);
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    addEntry(entries.nextElement().getName(), unique, ambiguous);
                }
            } catch (Throwable ignored) {
                // 单个归档坏掉不能连累整个索引。
            } finally {
                if (jar != null) {
                    try {
                        jar.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return unique;
    }

    private static void addEntry(String name, Map<String, String> unique, Set<String> ambiguous) {
        if (!isCandidate(name)) {
            return;
        }
        String qualified = name.substring(0, name.length() - 6).replace('/', '.');
        int separator = qualified.lastIndexOf('.');
        if (separator <= 0) {
            return;
        }
        String simple = qualified.substring(separator + 1);
        if (ambiguous.contains(simple)) {
            return;
        }
        String previous = unique.get(simple);
        if (previous == null) {
            unique.put(simple, qualified);
        } else if (!previous.equals(qualified)) {
            unique.remove(simple);
            ambiguous.add(simple);
        }
    }

    /** 只收顶层公开类型的 .class，排除内部类与元数据。 */
    private static boolean isCandidate(String name) {
        if (!name.endsWith(".class") || name.indexOf('$') >= 0) {
            return false;
        }
        if (name.startsWith("META-INF/versions/")) {
            return false;
        }
        return !name.endsWith("module-info.class") && !name.endsWith("package-info.class");
    }

    private static String keyOf(List<File> archives) {
        StringBuilder builder = new StringBuilder();
        for (File archive : archives) {
            builder.append(archive.getAbsolutePath())
                    .append(':').append(archive.length()).append('\n');
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 归档来源
    // ------------------------------------------------------------------

    /** 收集用于索引的归档：android.jar 优先，其次工具链目录下的其他 jar。 */
    static List<File> classpathArchives() {
        List<File> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (File candidate : archiveCandidates()) {
            addArchive(result, seen, candidate);
            if (result.size() >= MAX_ARCHIVES) {
                break;
            }
        }
        return result;
    }

    private static List<File> archiveCandidates() {
        List<File> candidates = new ArrayList<>();
        // 1) 编译目标平台 jar：android.* 与 java.* 的主要来源
        String androidJar = androidJarPath();
        if (androidJar != null) {
            candidates.add(new File(androidJar));
        }
        // 2) 工具链目录下的其它 jar（依赖库、运行时）
        for (File dir : toolchainDirs()) {
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (isArchive(child)) {
                    candidates.add(child);
                }
            }
        }
        // 3) 工程依赖库目录
        for (File dir : projectLibDirs()) {
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (isArchive(child)) {
                    candidates.add(child);
                }
            }
        }
        return candidates;
    }

    /** 取编译目标平台 jar 路径；拿不到返回 {@code null}。 */
    private static String androidJarPath() {
        try {
            ToolchainService service = Platform.getService(ProjectServiceKeys.TOOLCHAIN);
            if (service == null) {
                return null;
            }
            AndroidSdk sdk = service.getTool(Tool.ANDROID_SDK);
            if (sdk == null) {
                return null;
            }
            String path = sdk.getAndroidJarPath();
            if (path != null && new File(path).isFile()) {
                return path;
            }
            // 有些版本只给 stub jar，退而求其次也比没有强。
            String stub = sdk.getAndroidStubJarPath();
            return stub != null && new File(stub).isFile() ? stub : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 结绳工具链可能存放 jar 的目录。 */
    private static List<File> toolchainDirs() {
        List<File> dirs = new ArrayList<>();
        try {
            Context context = AndroidPlugins.getContext(KotlinClasspathIndex.class);
            if (context != null) {
                File files = context.getFilesDir();
                if (files != null) {
                    // 结绳把 android.jar 放在私有目录的 sdk 缓存里，往上找两层常见布局。
                    dirs.add(new File(files, "toolchain"));
                    dirs.add(new File(files, "sdk"));
                    dirs.add(new File(files, "classpath"));
                }
            }
        } catch (Throwable ignored) {
        }
        return dirs;
    }

    /** 工程自带的依赖库目录。 */
    private static List<File> projectLibDirs() {
        List<File> dirs = new ArrayList<>();
        try {
            Project project = Platform.getProject();
            File projectDir = project == null ? null : project.getProjectDir();
            if (projectDir != null) {
                dirs.add(new File(projectDir, "libs"));
                dirs.add(new File(projectDir, "lib"));
            }
        } catch (Throwable ignored) {
        }
        return dirs;
    }

    private static boolean isArchive(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static void addArchive(List<File> result, Set<String> seen, File candidate) {
        if (candidate == null || !candidate.isFile()) {
            return;
        }
        String key;
        try {
            key = candidate.getCanonicalPath();
        } catch (Throwable t) {
            key = candidate.getAbsolutePath();
        }
        if (seen.add(key)) {
            result.add(candidate);
        }
    }

    private static Map<String, String> fallbackTypes() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String[] row : FALLBACK_TYPES) {
            map.put(row[0], row[1]);
        }
        return map;
    }
}
