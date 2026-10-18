package com.tiecode.ktcplugin.lang.kt;

import com.rapid.api.ext.framework.BaseObject;
import com.rapid.api.project.Project;
import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.PSFile;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.api.project.structure.extension.PSObjectCreationOption;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;
import com.rapid.util.file.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;

/**
 * 「新建Kotlin类」：在选中的目录下生成一个 {@code .kt} 文件骨架。
 *
 * <p>注册方式：{@code program.json} 的 {@code project.file.creationOption} 扩展点。
 * 不带 scope 时宿主会把它挂到<b>全局</b> {@code ProjectFileExtender}
 * （见 {@code PSObjectCreateOptionActivator}），于是任意工程的文件树长按菜单里
 * 都能看到这一项——与 {@code .kt} 的打开方式（{@code project.file.openWithEditor}）
 * 是同一套全局注册思路。
 *
 * <p>包名策略：优先用工程自身的包名（结绳工程 {@code project.json} 里的 pkgName，
 * 通过反射调 {@code getNamespace()} 拿，避免为一个小功能引入对宿主内部类库的
 * 编译期依赖），拿不到再按目录相对源码根推导。
 *
 * <p>本类只负责「宿主侧」的部分（表单、定位目录、写文件、通知文件树），
 * 文件名/包名/模板这些纯逻辑在 {@link KotlinFileTemplate} 里，可单独测试。
 */
public class KotlinClassCreationOption extends BaseObject implements PSObjectCreationOption {

    @Override
    public String getName() {
        return "新建Kotlin类";
    }

    @Override
    public Form getForm() {
        Form form = new Form();
        FormInput filename = PSObjectCreationForm.createFileNameInput();
        filename.setValidator(PSObjectCreationForm.getIdentifierValidator());
        form.addInput(filename);
        return form;
    }

    @Override
    public void create(PSCheckedNewChildObject parent, Form form, OnCreatePSObjectListener listener) {
        if (!(parent instanceof PSFile)) {
            listener.onFail("请在目录上执行「新建」");
            return;
        }
        File dir = ((PSFile) parent).getFile();
        if (dir == null || !dir.isDirectory()) {
            listener.onFail("无法定位目标目录");
            return;
        }
        String className = KotlinFileTemplate.className(PSObjectCreationForm.getFileName(form));
        if (className == null) {
            listener.onFail("类名不能为空");
            return;
        }
        File file = new File(dir, className + KotlinFileTemplate.KOTLIN_SUFFIX);
        if (file.exists()) {
            listener.onFail("文件已存在: " + file.getName());
            return;
        }
        try {
            String packageName = KotlinFileTemplate.packageName(
                    namespaceOfProject(), projectDir(), dir);
            FileUtils.writeFile(
                    KotlinFileTemplate.classTemplate(packageName, className), file);
            listener.onSuccess(new PSFile(file));
        } catch (IOException error) {
            listener.onFail("创建文件失败: " + error.getMessage());
        }
    }

    /** 当前工程目录；拿不到返回 {@code null}。 */
    private File projectDir() {
        try {
            Project project = getProject();
            return project == null ? null : project.getProjectDir();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射读取结绳工程的包名。
     *
     * <p>结绳工程的包名来自 {@code project.json} 的 pkgName，宿主通过
     * {@code TiecodeProject.getNamespace()} 暴露。这里不直接引用那个类，
     * 是为了不为「新建时填个包名头」这么小的功能引入对宿主内部类库的编译期依赖；
     * 非结绳工程没有这个方法，反射失败返回 {@code null}，由目录推导兜底。
     */
    private String namespaceOfProject() {
        try {
            Project project = getProject();
            if (project == null) {
                return null;
            }
            Method method = project.getClass().getMethod("getNamespace");
            Object value = method.invoke(project);
            if (value instanceof String) {
                String namespace = ((String) value).trim();
                return namespace.isEmpty() ? null : namespace;
            }
        } catch (Throwable ignored) {
            // 非结绳工程 / 方法不存在：交给目录推导。
        }
        return null;
    }
}
