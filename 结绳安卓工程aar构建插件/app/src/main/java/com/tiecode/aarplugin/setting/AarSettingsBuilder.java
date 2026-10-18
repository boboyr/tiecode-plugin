package com.tiecode.aarplugin.setting;

import android.view.View;
import android.widget.AdapterView;

import com.qiplat.open.ui.widget.dialog.QiBottomListDialog;
import com.rapid.api.component.page.setting.SettingsPage;
import com.rapid.api.component.widget.item.ISettingGroupView;
import com.rapid.api.component.widget.item.ITextArrowItemView;
import com.rapid.api.framework.common.setting.AsSubSettingsInfo;
import com.rapid.api.framework.common.setting.BaseSettingsBuilder;
import com.rapid.api.framework.common.setting.SettingsBuilder;

import java.util.Arrays;

/**
 * 插件设置页：设置 → 项目管理器 → 「AAR/JAR 打包」。
 *
 * <p>目前只有一个「Java 版本」：点开选 默认 / 1.8 / 11 / 17 / 21 / 25，
 * 只作用于「构建aar」「构建jar」这两次构建（见 {@link JavaVersionSetting}）。
 */
public class AarSettingsBuilder extends BaseSettingsBuilder {

    public static final String ID = "AarPlugin";

    private ISettingGroupView buildGroup;
    private ITextArrowItemView javaVersionItem;

    @Override
    public String getParentId() {
        return SettingsBuilder.ID_ROOT;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public AsSubSettingsInfo getAsSubSettingsInfo() {
        return new AsSubSettingsInfo(
                "项目管理器",
                "AAR/JAR 打包",
                "设置「构建aar」「构建jar」打包时使用的 Java 版本"
        );
    }

    @Override
    public void build(SettingsPage page) {
        buildGroup = page.getOrAddSettingGroup("打包设置");
        javaVersionItem = buildGroup.addArrowItem(
                "Java 版本",
                JavaVersionSetting.describe(),
                "",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showVersionPicker();
                    }
                }
        );
    }

    private void showVersionPicker() {
        final QiBottomListDialog dialog = QiBottomListDialog.make(getActivity());
        dialog.setTitle("选择打包用的 Java 版本");
        dialog.setContentItems(Arrays.asList(JavaVersionSetting.OPTIONS),
                new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        JavaVersionSetting.set(JavaVersionSetting.OPTIONS[position]);
                        if (javaVersionItem != null) {
                            javaVersionItem.setDescription(JavaVersionSetting.describe());
                        }
                        dialog.dismiss();
                    }
                });
        dialog.show();
    }

    @Override
    public void dispose() {
        buildGroup = null;
        javaVersionItem = null;
    }
}
