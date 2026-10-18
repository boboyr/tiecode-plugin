package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.property.DefaultProperty;

/**
 * 带类型的属性：右栏点它时，编辑器知道该按小数解析还是当文本处理。
 * 框架内部拿 Property 当 Map 的 key，所以每个属性只留一个实例（见 SceneElementProvider）。
 */
public class SceneProperty extends DefaultProperty {

    public static final int TYPE_TEXT = 0;
    public static final int TYPE_FLOAT = 1;

    public final int type;

    public SceneProperty(String name, String description, int type) {
        super(name, description, null);
        this.type = type;
    }
}
