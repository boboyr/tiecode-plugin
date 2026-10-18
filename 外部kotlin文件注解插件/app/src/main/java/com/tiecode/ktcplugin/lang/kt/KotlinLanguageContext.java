package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.lang.engine.DefaultLanguageContext;
import com.rapid.api.project.Project;
import com.rapid.framework.vfs.VirtualFile;

/**
 * Kotlin 语言上下文。
 *
 * <p>Kotlin 的补全与高亮都是<b>本地实现</b>（见 {@link KotlinCompletionProvider}），
 * 不像 gopls / clangd 那样需要维护一个长连接的语言服务器，所以这里不持有额外状态，
 * 只继承 {@link DefaultLanguageContext}：
 * <ul>
 *   <li>拿到结绳维护的「未保存正文」缓存，补全时可用于就近提取标识符；</li>
 *   <li>文档变更 / 工程开关等回调可以让 {@link KotlinSourceIndex} 及时失效，
 *       避免打开新工程后还在提示上一个工程的符号。</li>
 * </ul>
 *
 * <p><b>必须主动清理基类的正文缓存</b>：{@link DefaultLanguageContext} 的
 * {@code fileContentMap} 会保存每一篇改过的正文，而它的 {@code didCloseDocument}
 * 是空实现。本类是<b>进程级单例</b>，不清理就等于每编辑一个 {@code .kt}
 * 就常驻一份全文，关闭标签页也不会释放。参照宿主自己的
 * {@code TiecodeLanguageContext}，在关工程/关文档时清掉。
 *
 * <p>单例：一次结绳会话里 Kotlin 只有一门语言，共享一个上下文即可。
 */
public class KotlinLanguageContext extends DefaultLanguageContext {

    private static final class Holder {
        static final KotlinLanguageContext INSTANCE = new KotlinLanguageContext();
    }

    public static KotlinLanguageContext getInstance() {
        return Holder.INSTANCE;
    }

    private KotlinLanguageContext() {
    }

    @Override
    public void dipOpenProject(Project project) {
        super.dipOpenProject(project);
        // 换了工程：旧符号缓存立刻失效，也不该继续留着上个工程的正文。
        fileContentMap.clear();
        KotlinSourceIndex.get().invalidate();
    }

    @Override
    public void didCloseProject(Project project) {
        super.didCloseProject(project);
        fileContentMap.clear();
        KotlinSourceIndex.get().invalidate();
    }

    /**
     * 关掉一篇文档：把它的正文缓存丢掉。
     *
     * <p>基类的这个方法不做事，而缓存是按文件累积的——不在这里释放，
     * 每个编辑过的 {@code .kt} 全文都会一直留在进程里。
     */
    @Override
    public void didCloseDocument(VirtualFile virtualFile) {
        super.didCloseDocument(virtualFile);
        if (virtualFile != null) {
            fileContentMap.remove(virtualFile);
        }
    }

    @Override
    public void didSaveDocument(VirtualFile virtualFile) {
        super.didSaveDocument(virtualFile);
        // 保存意味着磁盘上的声明可能变了，下一次补全重新扫一遍。
        KotlinSourceIndex.get().invalidate();
    }

    @Override
    public void didCreateWorkspaceFile(VirtualFile virtualFile) {
        super.didCreateWorkspaceFile(virtualFile);
        KotlinSourceIndex.get().invalidate();
    }

    @Override
    public void didDeleteWorkspaceFile(VirtualFile virtualFile) {
        super.didDeleteWorkspaceFile(virtualFile);
        KotlinSourceIndex.get().invalidate();
    }

    @Override
    public void didRenameWorkspaceFile(VirtualFile oldFile, VirtualFile newFile) {
        super.didRenameWorkspaceFile(oldFile, newFile);
        KotlinSourceIndex.get().invalidate();
    }
}
