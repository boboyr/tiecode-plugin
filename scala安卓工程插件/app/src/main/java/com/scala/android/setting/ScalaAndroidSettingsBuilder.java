package com.scala.android.setting;

import android.view.View;
import android.widget.AdapterView;

import com.qiplat.open.ui.widget.dialog.QiBottomListDialog;
import com.rapid.api.component.page.setting.SettingsPage;
import com.rapid.api.component.widget.item.ISettingGroupView;
import com.rapid.api.component.widget.item.ITextArrowItemView;
import com.rapid.api.component.widget.item.ITextSwitchItemView;
import com.rapid.api.framework.common.setting.AsSubSettingsInfo;
import com.rapid.api.framework.common.setting.BaseSettingsBuilder;
import com.rapid.api.framework.common.setting.SettingsBuilder;

import java.util.Arrays;

/**
 * 插件设置页：设置 → 项目管理器 → 「Scala安卓工程」。
 *
 * <p>四项：
 * <ul>
 *   <li>离线模式 —— 只用本地已下载的依赖；</li>
 *   <li>复用宿主 Maven 目录 —— 与结绳 Java 插件共享 {@code /sdcard/Tiecode/Maven}；</li>
 *   <li>包含 Test 依赖 —— 把 {@code % Test} 的坐标也解析进来；</li>
 *   <li>依赖仓库 —— 默认（内置镜像 + 脚本声明）或仅脚本声明。</li>
 * </ul>
 * 额外仓库通过长按「依赖仓库」编辑（每行一个 URL）。
 */
public class ScalaAndroidSettingsBuilder extends BaseSettingsBuilder {

    public static final String ID = "ScalaAndroidPlugin";

    private ISettingGroupView group;
    private ITextArrowItemView repositoryItem;
    private ITextArrowItemView extraRepositoryItem;

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
                "Scala安卓工程",
                "设置 sbt 依赖解析（离线、本地仓库、额外仓库）"
        );
    }

    @Override
    public void build(SettingsPage page) {
        group = page.getOrAddSettingGroup("sbt 依赖");

        group.addSwitchItem(
                "离线模式",
                "只使用已经下载到本地仓库的依赖，不访问网络",
                ScalaAndroidSettings.offline(),
                new ITextSwitchItemView.OnCheckedChangeListener() {
                    @Override
                    public void onChange(ITextSwitchItemView item, boolean checked) {
                        ScalaAndroidSettings.setOffline(checked);
                    }
                }
        );

        group.addSwitchItem(
                "复用宿主 Maven 目录",
                "与结绳 Java 插件共用 /sdcard/Tiecode/Maven，避免重复下载",
                ScalaAndroidSettings.useHostMavenRepository(),
                new ITextSwitchItemView.OnCheckedChangeListener() {
                    @Override
                    public void onChange(ITextSwitchItemView item, boolean checked) {
                        ScalaAndroidSettings.setUseHostMavenRepository(checked);
                    }
                }
        );

        group.addSwitchItem(
                "包含 Test 依赖",
                "把 build.sbt 里 % Test 的依赖也解析进 classpath",
                ScalaAndroidSettings.includeTestDependencies(),
                new ITextSwitchItemView.OnCheckedChangeListener() {
                    @Override
                    public void onChange(ITextSwitchItemView item, boolean checked) {
                        ScalaAndroidSettings.setIncludeTestDependencies(checked);
                    }
                }
        );

        repositoryItem = group.addArrowItem(
                "依赖仓库",
                ScalaAndroidSettings.repositoryMode(),
                "",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showRepositoryPicker();
                    }
                }
        );

        extraRepositoryItem = group.addArrowItem(
                "额外仓库",
                extraRepositoriesDescription(),
                "长按编辑（每行一个 URL）",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showExtraRepositoryHint();
                    }
                }
        );
        extraRepositoryItem.setLongClick(v -> {
            showExtraRepositoryEditor();
            return true;
        });
    }

    private String extraRepositoriesDescription() {
        int count = ScalaAndroidSettings.extraRepositories().size();
        return count == 0 ? "未设置" : (count + " 个");
    }

    private void showRepositoryPicker() {
        final QiBottomListDialog dialog = QiBottomListDialog.make(getActivity());
        dialog.setTitle("选择依赖仓库策略");
        dialog.setContentItems(
                Arrays.asList(ScalaAndroidSettings.MODE_DEFAULT, ScalaAndroidSettings.MODE_SCRIPT_ONLY),
                new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                        String mode = position == 0
                                ? ScalaAndroidSettings.MODE_DEFAULT
                                : ScalaAndroidSettings.MODE_SCRIPT_ONLY;
                        ScalaAndroidSettings.setRepositoryMode(mode);
                        if (repositoryItem != null) {
                            repositoryItem.setDescription(mode);
                        }
                        dialog.dismiss();
                    }
                });
        dialog.show();
    }

    private void showExtraRepositoryHint() {
        android.widget.Toast.makeText(getActivity(),
                "长按这一项可以编辑额外仓库（每行一个 URL）",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showExtraRepositoryEditor() {
        try {
            android.content.Context context = getActivity();
            final android.widget.EditText input = new android.widget.EditText(context);
            input.setText(ScalaAndroidSettings.extraRepositoriesText());
            input.setHint("https://maven.aliyun.com/repository/public/");
            input.setMinLines(4);
            input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

            new android.app.AlertDialog.Builder(context)
                    .setTitle("额外依赖仓库")
                    .setView(input)
                    .setPositiveButton("保存", (d, which) -> {
                        ScalaAndroidSettings.setExtraRepositories(input.getText().toString());
                        if (extraRepositoryItem != null) {
                            extraRepositoryItem.setDescription(extraRepositoriesDescription());
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable error) {
            android.widget.Toast.makeText(getActivity(),
                    "打开编辑器失败：" + error, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void dispose() {
        group = null;
        repositoryItem = null;
        extraRepositoryItem = null;
    }
}
