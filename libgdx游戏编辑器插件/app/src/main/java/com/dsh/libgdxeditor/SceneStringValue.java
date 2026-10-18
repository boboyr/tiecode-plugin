package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.designer.property.StringValue;

/** 框架认的字符串属性值。 */
public class SceneStringValue extends StringValue {
    public interface Get { String get(); }
    public interface Put { void put(String v); }

    private final Get get;
    private final Put put;

    public SceneStringValue(Property property, Get get, Put put) {
        super(property);
        this.get = get;
        this.put = put;
    }

    @Override
    public String get() {
        return get.get();
    }

    @Override
    public void set(String value) {
        put.put(value == null ? "" : value);
    }

    @Override
    public boolean isNull() {
        return false;
    }
}
