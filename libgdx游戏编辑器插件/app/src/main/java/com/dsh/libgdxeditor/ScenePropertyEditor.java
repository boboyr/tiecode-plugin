package com.dsh.libgdxeditor;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import com.qiplat.open.ui.widget.dialog.QiBottomTextInputDialog;
import com.rapid.api.component.designer.element.Element;
import com.rapid.api.component.designer.property.Editor;
import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.designer.property.Value;

/**
 * 右栏点属性 → 结绳自带的底部输入弹窗(照抄本体
 * {@code cn.tiecode.android.layout.property.editor.DefaultPropertyEditor} 的写法:
 * {@code QiBottomTextInputDialog.make(context)} 自己就会弹出来,确定时按类型解析再回调)。
 *
 * <p>关键:那个弹窗的布局里有 MaterialButton({@code QiButton}),要求 Context 的主题是
 * {@code Theme.MaterialComponents} 的后代。插件页所在的 {@code SimplePluginActivity} 主题是
 * {@code Theme.AppCompat.Light},所以必须先过一层 {@link UiContext#material(Context)} 包上宿主应用主题,
 * 否则会在 inflate 阶段抛 {@code InflateException} 把宿主带崩。</p>
 */
public class ScenePropertyEditor implements Editor {

    private final Context context;

    public ScenePropertyEditor(Context context) {
        this.context = context;
    }

    @Override
    public void editProperty(final Element element, final Property property, final Callback callback) {
        if (element == null || property == null || context == null) {
            return;
        }
        final Context dialogContext = UiContext.material(context);
        boolean number = isNumber(property);
        final QiBottomTextInputDialog dialog;
        try {
            dialog = QiBottomTextInputDialog.make(dialogContext);
        } catch (Throwable t) {
            Toast.makeText(dialogContext, "输入弹窗起不来:" + t, Toast.LENGTH_LONG).show();
            PluginLog.write("QiBottomTextInputDialog 失败:" + t);
            return;
        }
        dialog.setTitle("属性-" + property.getName());
        dialog.setInputTitle("请输入值(类型 : " + (number ? "小数" : "文本") + ")");
        dialog.setText(currentText(element, property));
        dialog.setPositiveButton("确定", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String text = dialog.getText() == null ? "" : dialog.getText().trim();
                Value<?> value;
                if (isNumber(property)) {
                    float parsed;
                    try {
                        parsed = Float.parseFloat(text);
                    } catch (Exception e) {
                        Toast.makeText(dialogContext, "「" + property.getName() + "」要填数字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    value = property.makeValue(parsed);
                } else {
                    value = property.makeValue(text);
                }
                if (callback != null) {
                    callback.updateProperty(property, value);
                }
                dialog.dismiss();
            }
        });
    }

    private boolean isNumber(Property property) {
        return property instanceof SceneProperty && ((SceneProperty) property).type == SceneProperty.TYPE_FLOAT;
    }

    private String currentText(Element element, Property property) {
        try {
            Value<?> value = element.getProperty(property);
            if (value == null || value.get() == null) {
                return "";
            }
            return String.valueOf(value.get());
        } catch (Throwable t) {
            return "";
        }
    }
}
