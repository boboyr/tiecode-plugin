package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.element.DefaultElementClass;
import com.rapid.api.component.designer.element.DefaultElementClassGroup;
import com.rapid.api.component.designer.element.Element;
import com.rapid.api.component.designer.element.ElementClass;
import com.rapid.api.component.designer.element.ElementClassGroup;
import com.rapid.api.component.designer.element.ElementProvider;
import com.rapid.api.component.designer.property.DefaultPropertyGroup;
import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.designer.property.PropertyGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 告诉结绳「设计布局」：左栏有哪些 libgdx 组件、每个组件能改哪些属性。
 *
 * <p>属性对象（{@link SceneProperty}）全局只留一份：框架把 Property 当 Map 的 key 用，
 * 每次新建的话右栏点出来的值会取不到。</p>
 */
public class SceneElementProvider implements ElementProvider {

    public static final String KIND_STAGE = "舞台";

    public static final SceneProperty P_NAME =
            new SceneProperty("名称", "生成代码里的变量名", SceneProperty.TYPE_TEXT);
    public static final SceneProperty P_TEXT =
            new SceneProperty("文本", "标签 / 文本按钮的文字", SceneProperty.TYPE_TEXT);
    public static final SceneProperty P_ASSET =
            new SceneProperty("贴图", "图片路径，或 图集.atlas#区域名", SceneProperty.TYPE_TEXT);
    public static final SceneProperty P_X =
            new SceneProperty("X", "横坐标（左下角是原点）", SceneProperty.TYPE_FLOAT);
    public static final SceneProperty P_Y =
            new SceneProperty("Y", "纵坐标（向上为正）", SceneProperty.TYPE_FLOAT);
    public static final SceneProperty P_W =
            new SceneProperty("宽", "宽度", SceneProperty.TYPE_FLOAT);
    public static final SceneProperty P_H =
            new SceneProperty("高", "高度", SceneProperty.TYPE_FLOAT);
    public static final SceneProperty P_ROTATION =
            new SceneProperty("旋转", "旋转角度", SceneProperty.TYPE_FLOAT);

    private static final Map<String, ElementClass> CLASS_CACHE = new HashMap<String, ElementClass>();

    /** 组件名 → 组件类型（左栏和元素树共用同一份，右栏才拿得到属性表）。 */
    public static ElementClass elementClass(String kind) {
        ElementClass cached = CLASS_CACHE.get(kind);
        if (cached == null) {
            cached = new DefaultElementClass(kind, descriptionOf(kind), null, propertyGroups(kind));
            CLASS_CACHE.put(kind, cached);
        }
        return cached;
    }

    public static List<PropertyGroup> propertyGroups(String kind) {
        List<Property> items = new ArrayList<Property>();
        items.add(P_NAME);
        if (KIND_STAGE.equals(kind)) {
            items.add(P_W);
            items.add(P_H);
        } else {
            // 属性按组件类型区分:只有标签/文本按钮有「文本」,只有图片/精灵有「贴图」,
            // 演员/表格/组等既不显示文本也不显示贴图(避免改了不生效的迷惑项)
            if (usesText(kind)) {
                items.add(P_TEXT);
            }
            if (usesAsset(kind)) {
                items.add(P_ASSET);
            }
            items.add(P_X);
            items.add(P_Y);
            items.add(P_W);
            items.add(P_H);
            items.add(P_ROTATION);
        }
        List<PropertyGroup> groups = new ArrayList<PropertyGroup>();
        groups.add(new DefaultPropertyGroup(KIND_STAGE.equals(kind) ? "舞台属性" : "组件属性", items));
        return groups;
    }

    /** 这个组件有没有「文本」属性。 */
    public static boolean usesText(String kind) {
        return SceneElement.KIND_LABEL.equals(kind) || SceneElement.KIND_BUTTON.equals(kind);
    }

    /** 这个组件有没有「贴图」属性。 */
    public static boolean usesAsset(String kind) {
        return SceneElement.KIND_IMAGE.equals(kind) || SceneElement.KIND_SPRITE.equals(kind);
    }

    private static String descriptionOf(String kind) {
        if (KIND_STAGE.equals(kind)) {
            return "整个舞台（只会有一个）";
        }
        return "libgdx " + kind;
    }

    @Override
    public List<ElementClassGroup> getElementClassGroups() {
        List<ElementClassGroup> groups = new ArrayList<ElementClassGroup>();

        // 「添加组件」：演员 / 精灵 / 图片 / 标签 / 文本按钮 / 表格 / 组
        List<ElementClass> items = new ArrayList<ElementClass>();
        for (String kind : SceneElement.KINDS) {
            if (KIND_STAGE.equals(kind)) {
                continue;
            }
            items.add(elementClass(kind));
        }
        groups.add(new DefaultElementClassGroup("添加组件", items));

        // 「添加布局组件」：舞台
        List<ElementClass> layout = new ArrayList<ElementClass>();
        layout.add(elementClass(KIND_STAGE));
        groups.add(new DefaultElementClassGroup("添加布局组件", layout));
        return groups;
    }

    @Override
    public Element addElement(Element parent, ElementClass elementClass) {
        String kind = elementClass == null ? SceneElement.KIND_ACTOR : elementClass.getName();
        SceneModel model = SceneModelHolder.current();
        if (KIND_STAGE.equals(kind)) {
            return new StageElementImpl(elementClass, model == null ? new SceneModel() : model);
        }
        if (model == null) {
            model = new SceneModel();
            SceneModelHolder.set(model);
        }
        String name = model.uniqueName(kind);
        float[] size = SceneElement.defaultSize(kind);
        SceneElement element = new SceneElement(kind, name, 40, 40, size[0], size[1]);
        model.elements.add(element);
        SceneElementImpl impl = new SceneElementImpl(elementClass, element);
        SceneModelHolder.notifyElementCreated(element, impl);
        SceneModelHolder.notifyModelChanged();
        return impl;
    }
}
