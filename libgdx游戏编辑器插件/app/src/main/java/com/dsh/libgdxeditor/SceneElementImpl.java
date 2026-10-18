package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.element.AbstractElement;
import com.rapid.api.component.designer.element.ElementClass;
import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.designer.property.Value;

/**
 * 元素树里的一个组件。属性值直接读模型（{@link SceneElement} 的字段），
 * 右栏改完写回模型，画布跟着重画。
 */
public class SceneElementImpl extends AbstractElement {

    public final SceneElement model;

    public SceneElementImpl(ElementClass elementClass, SceneElement model) {
        super(elementClass);
        this.model = model;
    }

    @Override
    public String getDisplayInfo() {
        return model.kind + " · " + model.name;
    }

    @Override
    public Value<?> getProperty(Property property) {
        String name = property.getName();
        if ("名称".equals(name)) {
            return new SceneStringValue(property, textGetter(model.name), textPutter(name));
        }
        if ("文本".equals(name)) {
            return new SceneStringValue(property, textGetter(model.text), textPutter(name));
        }
        if ("贴图".equals(name)) {
            return new SceneStringValue(property, textGetter(model.asset), textPutter(name));
        }
        if ("X".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.x), floatPutter(name));
        }
        if ("Y".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.y), floatPutter(name));
        }
        if ("宽".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.w), floatPutter(name));
        }
        if ("高".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.h), floatPutter(name));
        }
        if ("旋转".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.rotation), floatPutter(name));
        }
        return super.getProperty(property);
    }

    @Override
    public void setProperty(Property property, Value<?> value) {
        super.setProperty(property, value);
        if (property != null) {
            apply(property.getName(), value == null ? null : value.get());
        }
        SceneModelHolder.notifyModelChanged();
    }

    private void apply(String name, Object raw) {
        if ("名称".equals(name)) {
            model.name = CodeGen.sanitize(asText(raw, model.name));
            return;
        }
        if ("文本".equals(name)) {
            model.text = asText(raw, model.text);
            return;
        }
        if ("贴图".equals(name)) {
            model.asset = asText(raw, model.asset);
            return;
        }
        if ("X".equals(name)) {
            model.x = asFloat(raw, model.x);
        } else if ("Y".equals(name)) {
            model.y = asFloat(raw, model.y);
        } else if ("宽".equals(name)) {
            model.w = asFloat(raw, model.w);
        } else if ("高".equals(name)) {
            model.h = asFloat(raw, model.h);
        } else if ("旋转".equals(name)) {
            model.rotation = asFloat(raw, model.rotation);
        }
    }

    private static String asText(Object raw, String fallback) {
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static float asFloat(Object raw, float fallback) {
        if (raw instanceof Number) {
            return ((Number) raw).floatValue();
        }
        try {
            return Float.parseFloat(String.valueOf(raw));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private SceneStringValue.Get textGetter(final String value) {
        return new SceneStringValue.Get() {
            @Override
            public String get() {
                return value;
            }
        };
    }

    private SceneStringValue.Put textPutter(final String propertyName) {
        return new SceneStringValue.Put() {
            @Override
            public void put(String v) {
                apply(propertyName, v);
            }
        };
    }

    private SceneFloatValue.Get floatGetter(final float value) {
        return new SceneFloatValue.Get() {
            @Override
            public float get() {
                return value;
            }
        };
    }

    private SceneFloatValue.Put floatPutter(final String propertyName) {
        return new SceneFloatValue.Put() {
            @Override
            public void put(float v) {
                apply(propertyName, Float.valueOf(v));
            }
        };
    }
}
