package com.scala.android.project.option;

import com.rapid.api.project.structure.PSCheckedNewChildObject;
import com.rapid.api.project.structure.extension.PSObjectCreationForm;
import com.rapid.framework.data.form.Form;
import com.rapid.framework.data.form.FormInput;

import java.io.File;

/**
 * 「新建单例类」：按输入的名字生成 {@code object Xxx} 骨架（带 package 头）。
 *
 * <p>Scala 的 {@code object} 就是单例；带 {@code main} 的 {@code object} 还能直接作为入口，
 * 所以这里也给一个 {@code main}，方便新建完就能调试运行。
 */
public class ScalaSingletonCreationOption extends ScalaCreationOption {

    @Override
    public String getName() {
        return "新建单例类";
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
        File dir = parentDir(parent);
        String simpleName = fileName(form, null);
        if (simpleName == null) {
            listener.onFail("名称不能为空");
            return;
        }
        StringBuilder builder = new StringBuilder();
        if (dir != null) {
            builder.append(packageHeader(dir));
        }
        builder.append("object ").append(simpleName).append(":\n")
                .append("  def main(args: Array[String]): Unit =\n")
                .append("    println(\"Hello from ").append(simpleName).append("\")\n");
        createTextFile(parent, form, ".scala", builder.toString(), listener);
    }
}
