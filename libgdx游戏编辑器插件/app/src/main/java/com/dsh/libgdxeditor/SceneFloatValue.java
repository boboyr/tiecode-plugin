package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.property.FloatValue;
import com.rapid.api.component.designer.property.Property;

/** 框架认的浮点属性值（直接读写 SceneElement 的字段）。 */
public class SceneFloatValue extends FloatValue {
    public interface Get { float get(); }
    public interface Put { void put(float v); }

    private final Get get;
    private final Put put;

    public SceneFloatValue(Property property, Get get, Put put) {
        super(property);
        this.get = get;
        this.put = put;
    }

    @Override
    public Float get() {
        return get.get();
    }

    @Override
    public void set(Float value) {
        if (value != null) put.put(value);
    }

    @Override
    public boolean isNull() {
        return false;
    }
}
