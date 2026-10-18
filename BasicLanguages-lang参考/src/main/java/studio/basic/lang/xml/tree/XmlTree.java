package studio.basic.lang.xml.tree;

import com.rapid.framework.data.document.Range;

public interface XmlTree {
    Range getRange();

    <Param, Result> Result accept(Visitor<Param, Result> visitor, Param param);
}
