package com.dsh.libgdxeditor;

import com.rapid.api.ext.framework.BaseObject;
import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.PSFile;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.api.project.structure.extension.PSObjectCreationOption;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;

import java.io.File;

/**
 * 工程文件树「新建文件」里的一项:<b>新建场景类</b>。
 *
 * <p>宿主 {@code CreateFileMenuAction} 会把所有注册的创建方式列出来让用户选
 * (「选择方式」列表),选中本项后弹出一个「输入类名」表单,
 * 确定即在当前目录生成 {@code <类名>.t}:一个空的 libgdx 场景类骨架
 * (舞台 / 创建场景 / 绘制场景 / 触摸),并同时写好 {@code <类名>.t.scene.json},
 * 这样用「游戏引擎编辑器」打开它就能直接可视化编辑。</p>
 *
 * <p>通过插件扩展点 {@code project.file.creationOption} 注册
 * (scope = 结绳安卓工程 {@code cn.tiecode.android})。</p>
 */
public class NewSceneClassOption extends BaseObject implements PSObjectCreationOption {

    @Override
    public String getName() {
        return "新建场景类";
    }

    @Override
    public Form getForm() {
        Form form = new Form();
        FormInput className = PSObjectCreationForm.createFileNameInput();
        className.setValidator(PSObjectCreationForm.getIdentifierValidator());
        form.addInput(className);
        return form;
    }

    @Override
    public void create(PSCheckedNewChildObject parent, Form form, OnCreatePSObjectListener listener) {
        if (!(parent instanceof PSFile)) {
            listener.onFail("当前目录下不可新建文件");
            return;
        }
        File dir = ((PSFile) parent).getFile();
        if (dir == null || !dir.isDirectory()) {
            listener.onFail("无法定位目标目录");
            return;
        }
        String raw = PSObjectCreationForm.getFileName(form);
        if (raw == null || raw.trim().isEmpty()) {
            listener.onFail("类名不能为空");
            return;
        }
        String name = raw.trim();
        if (name.toLowerCase().endsWith(".t")) {
            name = name.substring(0, name.length() - 2);
        }
        String className = CodeGen.sanitize(name);
        if (className.isEmpty() || "元素".equals(className)) {
            listener.onFail("类名不能为空");
            return;
        }
        File file = new File(dir, className + ".t");
        try {
            if (file.exists()) {
                listener.onFail("文件已存在:" + file.getName());
                return;
            }
            String path = file.getAbsolutePath();
            SceneModel model = new SceneModel();
            model.className = className;
            String code = CodeGen.generate(model, className, false);
            if (!SceneStore.writeText(path, code)) {
                listener.onFail("创建文件失败");
                return;
            }
            SceneStore.saveModel(path, model);
            PluginLog.write("新建场景类:" + path);
            listener.onSuccess(new PSFile(file));
        } catch (Throwable t) {
            PluginLog.write("新建场景类失败:" + t);
            listener.onFail("创建文件失败:" + t);
        }
    }
}
