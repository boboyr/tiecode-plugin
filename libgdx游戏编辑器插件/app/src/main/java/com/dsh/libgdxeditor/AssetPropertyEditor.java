package com.dsh.libgdxeditor;

import com.rapid.api.component.designer.element.Element;
import com.rapid.api.component.designer.property.Editor;
import com.rapid.api.component.designer.property.Property;

/**
 * 「贴图」属性的编辑器：不让人手打路径，直接用文件选择器挑工程 assets 里的资源
 * （png/jpg 直接选；atlas 再挑一个区域，存成 {@code ui/items.atlas#区域名}）。
 *
 * <p>对应结绳本体的 {@code ImageSelector}（图片资源 → {@code FileSelectorDialog}），
 * 这里走插件标准入口 {@code PluginPage.openFileSelector(...)}。</p>
 */
public class AssetPropertyEditor implements Editor {

    private final SceneDesignerPage page;

    public AssetPropertyEditor(SceneDesignerPage page) {
        this.page = page;
    }

    @Override
    public void editProperty(Element element, Property property, Callback callback) {
        page.pickAsset(property, callback);
    }
}
