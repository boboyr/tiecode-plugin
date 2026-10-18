package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.element.AbstractElement;
import com.rapid.api.component.designer.element.ElementClass;
import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.designer.property.Value;

/** 舞台根元素：宽 / 高 绑到 {@link SceneModel} 的舞台尺寸，画布按它缩放。 */
public class StageElementImpl extends AbstractElement {

    private final SceneModel model;

    public StageElementImpl(ElementClass elementClass, SceneModel model) {
        super(elementClass);
        this.model = model;
    }

    @Override
    public boolean isRoot() {
        return true;
    }

    @Override
    public String getDisplayInfo() {
        return "舞台 " + (int) model.stageWidth + "×" + (int) model.stageHeight;
    }

    @Override
    public Value<?> getProperty(Property property) {
        String name = property.getName();
        if ("名称".equals(name)) {
            return new SceneStringValue(property, textGetter("舞台"), textPutter(name));
        }
        if ("宽".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.stageWidth), floatPutter(name));
        }
        if ("高".equals(name)) {
            return new SceneFloatValue(property, floatGetter(model.stageHeight), floatPutter(name));
        }
        return super.getProperty(property);
    }

    @Override
    public void setProperty(Property property, Value<?> value) {
        super.setProperty(property, value);
        if (property != null && value != null) {
            Object raw = value.get();
            if ("宽".equals(property.getName())) {
                model.stageWidth = asFloat(raw, model.stageWidth);
            } else if ("高".equals(property.getName())) {
                model.stageHeight = asFloat(raw, model.stageHeight);
            }
        }
        SceneModelHolder.notifyModelChanged();
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
                // 舞台名字固定
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
                if ("宽".equals(propertyName)) {
                    model.stageWidth = v;
                } else if ("高".equals(propertyName)) {
                    model.stageHeight = v;
                }
                SceneModelHolder.notifyModelChanged();
            }
        };
    }
}
