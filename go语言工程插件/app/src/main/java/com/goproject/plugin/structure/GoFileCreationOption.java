package com.goproject.plugin.structure;

import com.rapid.util.file.FileUtils;
import com.rapid.api.project.structure.PSFile;
import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.api.project.structure.extension.PSObjectCreationOption;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;

import java.io.File;
import java.io.IOException;

/**
 * 「新建go文件」操作：长按文件夹 → 新建文件 → 新建go文件，
 * 输入对话框里输入如 {@code main} 点确认就新建 {@code main.go}。
 *
 * <p>输入框默认填有 {@code main}；输入的名字不带 {@code .go} 后缀，
 * 创建时自动补上（带了也会先剥掉再加，避免出现 {@code main.go.go}）。
 * {@code main.go} 写入可运行的 hello world 起始代码，其余写 {@code package main}。
 */
public class GoFileCreationOption implements PSObjectCreationOption {

    /** main.go 的起始代码（可直接运行）。 */
    private static final String MAIN_TEMPLATE =
            "package main\n\nimport \"fmt\"\n\nfunc main() {\n\tfmt.Println(\"hello world\")\n}\n";

    /** 其他 go 文件的起始代码。 */
    private static final String PLAIN_TEMPLATE = "package main\n\n";

    @Override
    public String getName() {
        return "新建go文件";
    }

    @Override
    public Form getForm() {
        Form form = new Form();
        FormInput fileName = new FormInput(KEY_FILENAME, "文件名", "请输入文件名");
        // 输入框默认填 main，确认后即新建 main.go
        fileName.setValue("main");
        fileName.setValidator(PSObjectCreationForm.getIdentifierValidator());
        form.addInput(fileName);
        return form;
    }

    @Override
    public void create(PSCheckedNewChildObject parent, Form form, OnCreatePSObjectListener listener) {
        if (!(parent instanceof PSFile)) {
            listener.onFail("当前目录下不可新建文件");
            return;
        }
        File dir = ((PSFile) parent).getFile();
        if (dir.isFile()) {
            dir = dir.getParentFile();
        }
        String name = PSObjectCreationForm.getFileName(form);
        if (name == null || name.trim().isEmpty()) {
            listener.onFail("文件名不能为空");
            return;
        }
        name = name.trim();
        // 输入的名字不带 .go 后缀，创建时自动补上。
        if (name.endsWith(".go")) {
            name = name.substring(0, name.length() - 3);
        }
        File file = new File(dir, name + ".go");
        try {
            if (file.exists()) {
                listener.onFail("文件已存在");
                return;
            }
            if (!file.createNewFile()) {
                listener.onFail("创建失败");
                return;
            }
            FileUtils.writeFile("main".equals(name) ? MAIN_TEMPLATE : PLAIN_TEMPLATE, file);
            listener.onSuccess(new PSFile(file));
        } catch (IOException e) {
            listener.onFail(e.getMessage());
        }
    }
}
