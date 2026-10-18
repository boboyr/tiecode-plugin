package com.linux.plugin.lang.engine;

import com.rapid.api.Platform;
import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.lang.engine.baseModel.TextChange;
import com.rapid.api.project.Project;
import com.rapid.framework.vfs.VirtualFile;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import cn.tiecode.compiler.TCContext;
import cn.tiecode.compiler.TCIDEService;
import cn.tiecode.compiler.component.TCOptions;
import cn.tiecode.compiler.component.TCTargetPlatform;
import cn.tiecode.compiler.foundation.TCLogLevel;
import cn.tiecode.compiler.foundation.TCPosition;
import cn.tiecode.compiler.foundation.TCRange;
import cn.tiecode.compiler.foundation.TCSourceVersion;
import cn.tiecode.compiler.ide.TCTextChange;

/**
 * 结绳语言上下文:持有工程级别的 {@link TCIDEService},为补全、高亮、定义等
 * 语言服务提供共享的编译器实例,并负责把编辑器变更同步给编译器。
 */
public class TiecodeLanguageContext extends DefaultLanguageContext {

    private static final class Holder {
        static final TiecodeLanguageContext INSTANCE = new TiecodeLanguageContext();
    }

    public static TiecodeLanguageContext getInstance() {
        return Holder.INSTANCE;
    }

    private final Object lock = new Object();
    private volatile TCContext context;
    private volatile TCIDEService ideService;

    private TiecodeLanguageContext() {
    }

    @Override
    public void dipOpenProject(Project project) {
        synchronized (lock) {
            openForProject(project);
        }
    }

    /** 打开工程:释放旧服务并基于工程目录重建 IDE 服务(调用方需持有 {@link #lock})。 */
    private void openForProject(Project project) {
        releaseService();
        try {
            File projectDir = project == null ? null : project.getProjectDir();
            TCOptions options = new TCOptions();
            options.debug = true;
            options.ideMode = true;
            options.sourceVersion = TCSourceVersion.VERSION_4_7;
            options.platform = TCTargetPlatform.LINUX;
            options.logLevel = TCLogLevel.ERROR;
            options.packageName = readPackageName(project);
            if (projectDir != null) {
                File ideDir = new File(projectDir, "build/generated/tiec-ide");
                options.outputDir = ideDir.getAbsolutePath();
                options.lineMapPath = new File(ideDir, "mapping.bin").getAbsolutePath();
                List<String> prefixes = collectSearchPrefixes(projectDir);
                if (!prefixes.isEmpty()) {
                    Map<String, java.util.List<String>> search = new HashMap<>();
                    search.put(TCOptions.SCOPE_LIB, new ArrayList<>(prefixes));
                    search.put(TCOptions.SCOPE_SOURCE, new ArrayList<>(prefixes));
                    options.searchPrefixes = search;
                }
            }
            context = new TCContext(options);
            ideService = new TCIDEService(context);
            reload(projectDir);
        } catch (Throwable t) {
            log("初始化结绳 IDE 服务失败: " + t);
            releaseService();
        }
    }

    @Override
    public void didCloseProject(Project project) {
        synchronized (lock) {
            releaseService();
        }
    }

    @Override
    public void didChangeDocumentContent(VirtualFile virtualFile, String newText) {
        super.didChangeDocumentContent(virtualFile, newText);
        if (!isTieSource(virtualFile) || newText == null) {
            return;
        }
        TCIDEService service = ideService;
        if (service == null) {
            return;
        }
        try {
            service.didChangeSource(toUri(virtualFile), newText);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void didChangeDocumentContent(VirtualFile virtualFile, TextChange textChange) {
        if (!isTieSource(virtualFile) || textChange == null) {
            return;
        }
        TCIDEService service = ideService;
        if (service == null) {
            return;
        }
        try {
            TCTextChange change = new TCTextChange();
            if (textChange.range != null) {
                TCRange range = new TCRange();
                range.start = new TCPosition(textChange.range.start.line, textChange.range.start.character);
                range.end = new TCPosition(textChange.range.end.line, textChange.range.end.character);
                change.range = range;
            }
            change.rangeLength = textChange.rangeLength == null ? 0 : textChange.rangeLength;
            change.newText = textChange.text;
            service.didChangeSourceIncremental(toUri(virtualFile), change);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void didCreateWorkspaceFile(VirtualFile virtualFile) {
        super.didCreateWorkspaceFile(virtualFile);
        if (!isTieSource(virtualFile)) {
            return;
        }
        TCIDEService service = ideService;
        if (service == null) {
            return;
        }
        try {
            String content = getDynamicContent(virtualFile);
            service.didCreateSource(toUri(virtualFile), content == null ? "" : content);
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void didDeleteWorkspaceFile(VirtualFile virtualFile) {
        boolean isTie = isTieSource(virtualFile);
        super.didDeleteWorkspaceFile(virtualFile);
        TCIDEService service = ideService;
        if (!isTie || service == null) {
            return;
        }
        try {
            service.didDeleteSource(toUri(virtualFile));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void didRenameWorkspaceFile(VirtualFile oldFile, VirtualFile newFile) {
        boolean oldIsTie = isTieSource(oldFile);
        boolean newIsTie = isTieSource(newFile);
        super.didRenameWorkspaceFile(oldFile, newFile);
        TCIDEService service = ideService;
        if (service == null) {
            return;
        }
        try {
            if (oldIsTie && newIsTie) {
                service.didRenameSource(toUri(oldFile), toUri(newFile));
            } else if (oldIsTie) {
                service.didDeleteSource(toUri(oldFile));
            } else if (newIsTie) {
                String content = getDynamicContent(newFile);
                service.didCreateSource(toUri(newFile), content == null ? "" : content);
            }
        } catch (Throwable ignore) {
        }
    }

    /** 重新扫描工程源码并整体预编译。 */
    public void reload(File projectDir) {
        TCIDEService service = ideService;
        if (service == null || projectDir == null) {
            return;
        }
        try {
            List<File> sources = scanCompileSources(projectDir);
            fileContentMap.clear();
            addFiles(sources);
            service.compileFiles(sources);
            log("结绳 IDE 服务已预编译源文件数: " + sources.size());
        } catch (Throwable t) {
            log("预编译工程源文件失败: " + t);
        }
    }

    /** 获取 IDE 服务;若尚未初始化,则在当前工程上惰性初始化一次。 */
    public TCIDEService getIDEService() {
        if (ideService != null) {
            return ideService;
        }
        synchronized (lock) {
            if (ideService != null) {
                return ideService;
            }
            Project project = null;
            try {
                project = Platform.getProject();
            } catch (Throwable ignore) {
            }
            if (project != null) {
                openForProject(project);
            }
            return ideService;
        }
    }

    /** 扫描工程源代码目录与各绳包源代码目录下的 .t 文件。 */
    public static List<File> scanCompileSources(File projectDir) {
        List<File> files = new ArrayList<>();
        if (projectDir == null) {
            return files;
        }
        collect(new File(projectDir, "源代码"), files, true);
        for (File libRoot : listLibRoots(projectDir)) {
            collect(new File(libRoot, "源代码"), files, true);
        }
        return files;
    }

    /** 收集附加搜寻路径(绳包根目录),供依赖/资源解析使用。 */
    public static List<String> collectSearchPrefixes(File projectDir) {
        List<String> prefixes = new ArrayList<>();
        for (File libRoot : listLibRoots(projectDir)) {
            prefixes.add(libRoot.getAbsolutePath());
        }
        return prefixes;
    }

    /**
     * 列出各绳包的"真实根目录"。
     *
     * <p>兼容 tlb 解压产生的同名多一层嵌套(如 {@code 绳包/SDL3基本库/SDL3基本库/源代码}),
     * 否则该绳包源码会被漏采,导致"找不到类"的编译错误。
     */
    public static List<File> listLibRoots(File projectDir) {
        List<File> roots = new ArrayList<>();
        if (projectDir == null) {
            return roots;
        }
        File[] children = new File(projectDir, "绳包").listFiles();
        if (children == null) {
            return roots;
        }
        for (File child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            File root = resolveLibRoot(child);
            if (root != null) {
                roots.add(root);
            }
        }
        return roots;
    }

    /** 解析绳包真实根目录:自身即合法则返回自身,否则向下最多两级查找同名嵌套目录。 */
    private static File resolveLibRoot(File dir) {
        if (isLibRoot(dir)) {
            return dir;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && isLibRoot(child)) {
                    return child;
                }
            }
        }
        // 仍找不到源码目录时保留原目录,交由编译阶段报出更明确的诊断。
        return dir;
    }

    /** 判定目录是否为绳包根目录:含"源代码"子目录,或根目录下直接放有 .t 文件。 */
    private static boolean isLibRoot(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        if (new File(dir, "源代码").isDirectory()) {
            return true;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".t")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void collect(File dir, List<File> out, boolean recursive) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (recursive) {
                    collect(file, out, true);
                }
            } else if (file.getName().endsWith(".t")) {
                out.add(file);
            }
        }
    }

    /** 结绳 Linux 工程源码默认包名(源文件未声明包名时使用)。 */
    private String readPackageName(Project project) {
        return "com.tie.compiler";
    }

    private URI toUri(VirtualFile virtualFile) {
        return virtualFile.asURI();
    }

    private boolean isTieSource(VirtualFile virtualFile) {
        if (virtualFile == null || !virtualFile.isFile()) {
            return false;
        }
        String extension = virtualFile.getExtension();
        return extension != null && "t".equals(extension.toLowerCase(Locale.ROOT));
    }

    private void releaseService() {
        try {
            if (ideService != null) {
                ideService.cancel();
            }
        } catch (Throwable ignore) {
        }
        ideService = null;
        context = null;
    }

    private void log(String message) {
        try {
            Platform.getLogger().info("[Tiecode] " + message);
        } catch (Throwable ignore) {
        }
    }
}
