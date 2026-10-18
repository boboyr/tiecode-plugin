package studio.basic.lang.xml.tree.impl;

import com.rapid.framework.data.document.Range;

import studio.basic.lang.xml.tree.XmlTree;

public abstract class JXmlTree implements XmlTree {
    public Range range = Range.NONE;

    @Override
    public Range getRange() {
        return range;
    }
}
