package com.dsh.libgdxeditor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.drawerlayout.widget.DrawerLayout;

import com.rapid.android.ui.app.SimplePluginPage;
import com.rapid.android.ui.widget.designer.LayoutDesignerView;
import com.rapid.api.component.designer.ElementTree;
import com.rapid.api.component.designer.OperationBar;
import com.rapid.api.component.designer.element.Element;
import com.rapid.api.component.designer.property.DiverseEditor;
import com.rapid.api.component.designer.property.Editor;
import com.rapid.api.component.designer.property.Property;
import com.rapid.api.component.widget.selector.FileSelector;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 插件的设计器页面。跟结绳本体一样是 {@code SimplePluginPage}（不是 Dialog），
 * 页面里只有框架的 {@link LayoutDesignerView}：左栏、右栏、左右两个按钮全是它自带的。
 *
 * <p>接线顺序照抄本体 {@code studio.java.android.designer.LayoutDesignerPage}：
 * {@code setActionBarEnabled(false)} → {@code new LayoutDesignerView(this)} →
 * {@code designer.setContentView(画布)} → {@code setContentView(designer)}（页面级）。</p>
 */
public class SceneDesignerPage extends SimplePluginPage {

    private static SceneModel pendingModel;
    private static String pendingBaseDir;
    private static String pendingAssetsDir;
    private static String pendingClassName;
    private static String pendingFilePath;

    /** 打开页面前把场景塞进来（Page 由宿主反射实例化，只能用静态入口传参）。 */
    public static void prepare(SceneModel model, String baseDir, String assetsDir,
                               String className, String filePath) {
        pendingModel = model;
        pendingBaseDir = baseDir;
        pendingAssetsDir = assetsDir;
        pendingClassName = className;
        pendingFilePath = filePath;
    }

    private SceneModel model;
    private String assetsDir;
    /** 目标 .t 文件路径:可视化编辑后自动写回它;没有目标文件时不写回。 */
    private String filePath;
    /** 生成代码用的类名(一般是被编辑文件的名字)。 */
    private String targetClassName;
    private boolean dirty;
    private boolean saved;
    private LayoutDesignerView designerView;
    private SceneCanvasView canvasView;
    private ElementTree elementTree;
    private StageElementImpl stageElement;
    private final Map<SceneElement, SceneElementImpl> elementImpls =
            new LinkedHashMap<SceneElement, SceneElementImpl>();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        setActionBarEnabled(false);
        try {
            setDarkMode(true);          // 设计器（框架那套 UI）是按深色做的，浅色主题下字会白底白字
        } catch (Throwable t) {
            PluginLog.write("setDarkMode 失败：" + t);
        }
        model = pendingModel == null ? new SceneModel() : pendingModel;
        assetsDir = pendingAssetsDir;
        filePath = pendingFilePath;
        targetClassName = pendingClassName;
        pendingModel = null;
        pendingFilePath = null;
        pendingClassName = null;
        saved = false;
        dirty = false;
        if ((model.className == null || model.className.isEmpty())
                && targetClassName != null && targetClassName.length() > 0) {
            model.className = CodeGen.sanitize(targetClassName);
        }
        // 换工程/重新打开时清一次贴图缓存,避免旧工程的图被复用
        AssetBitmaps.clear();

        SceneModelHolder.set(model);
        SceneModelHolder.setBaseDir(pendingBaseDir);
        SceneModelHolder.setAssetsDir(assetsDir);
        SceneModelHolder.setModelChangedListener(new SceneModelHolder.ModelChangedListener() {
            @Override
            public void onModelChanged() {
                dirty = true;
            }
        });

        // ① 框架的设计器（左栏 + 右栏 + 左右两个红按钮，都在它内部）
        designerView = new LayoutDesignerView(this);

        // ② 左栏有哪些组件
        designerView.setElementProvider(new SceneElementProvider());

        // ③ 右栏点属性用哪个编辑器：默认结绳输入弹窗，「贴图」换成工程 assets 的文件选择
        final SceneDesignerPage page = this;
        DiverseEditor editors = new DiverseEditor();
        // 一定要用宿主 Activity 的 Context：页面自己的主题 inflate 不了
        // layout/dialog_bottom_input（QiBottomTextInputDialog 会抛 InflateException 把宿主带崩）
        android.content.Context dialogContext = this;
        try {
            android.app.Activity hostActivity = getActivity();
            if (hostActivity != null) {
                dialogContext = hostActivity;
            }
        } catch (Throwable ignored) {
        }
        editors.setDefaultEditor(new ScenePropertyEditor(dialogContext));
        editors.registerEditor(SceneElementProvider.P_ASSET, new AssetPropertyEditor(page));
        designerView.setPropertyEditor(editors);

        // ④ 内容视图：舞台容器 + 元素子 View
        canvasView = new SceneCanvasView(this, model);
        designerView.setContentView(canvasView);
        // setContentView 里是 contentView.addView(view)，父容器是竖向 LinearLayoutCompat，
        // 默认给的是 MATCH_PARENT × WRAP_CONTENT（高度会塌），只能 addView 之后改回来
        ViewGroup.LayoutParams canvasParams = canvasView.getLayoutParams();
        if (canvasParams != null) {
            canvasParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            canvasParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            canvasView.setLayoutParams(canvasParams);
        }
        SceneModelHolder.setCanvas(canvasView);

        // ⑤ 内容视图 <-> 元素树 双向同步
        elementTree = designerView.getElementTree();
        canvasView.attachElementTree(elementTree);
        rebuildElementTree();
        canvasView.sync();
        SceneModelHolder.setElementCreatedListener(new SceneModelHolder.ElementCreatedListener() {
            @Override
            public void onElementCreated(final SceneElement element, SceneElementImpl impl) {
                elementImpls.put(element, impl);
                canvasView.post(new Runnable() {
                    @Override
                    public void run() {
                        selectInTree(element);
                        canvasView.sync();
                    }
                });
            }
        });
        canvasView.setSelectionListener(new SceneCanvasView.SelectionListener() {
            @Override
            public void onElementSelected(SceneElement element) {
                selectInTree(element);
            }
        });
        elementTree.setOnElementSelectListener(new ElementTree.OnElementSelectListener() {
            @Override
            public void onSelect(Element element) {
                if (element instanceof SceneElementImpl) {
                    canvasView.highlight(((SceneElementImpl) element).model);
                } else {
                    canvasView.highlight(null);
                }
            }
        });

        // ⑥ 结构树下面的操作条（本体那套：上移 / 下移 / 复制 / 删除，加上代码动作）
        OperationBar bar = designerView.getOperationBar();
        bar.addButton("插入代码", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertCode();
            }
        });
        bar.addButton("复制代码", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyCode();
            }
        });
        bar.addButton("上移", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(-1);
            }
        });
        bar.addButton("下移", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                move(1);
            }
        });
        bar.addButton("复制", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                duplicate();
            }
        });
        bar.addButton("删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteCurrent();
            }
        });

        // ⑧ 最后把整页交给宿主；然后把左右抽屉铺满（框架的抽屉默认被系统栏边距 + 64dp 挤窄）
        setContentView(designerView);
        stretchDrawers(designerView);
        designerView.post(new Runnable() {
            @Override
            public void run() {
                stretchDrawers(designerView);
            }
        });

        selectInTree(model.elements.isEmpty() ? null : model.elements.get(0));
    }

    @Override
    public void onDestroy() {
        // 兜底:正常退出走 onBack,这里再保一次,避免异常销毁丢改动
        autoSave(false);
        SceneModelHolder.setCanvas(null);
        SceneModelHolder.setElementCreatedListener(null);
        SceneModelHolder.setModelChangedListener(null);
        super.onDestroy();
    }

    /** 宿主的写法是 {@code if (page.onBack()) super.onBackPressed();} —— 关抽屉必须返回 false。 */
    @Override
    public boolean onBack() {
        DrawerLayout drawer = findDrawer(designerView);
        if (drawer != null
                && (drawer.isDrawerOpen(Gravity.LEFT) || drawer.isDrawerOpen(Gravity.RIGHT))) {
            drawer.closeDrawers();
            return false;               // 抽屉收起来了，页面留着
        }
        // 退出前把可视化改动自动写回目标 .t(有改动才写)
        autoSave(false);
        setResult(android.app.Activity.RESULT_OK);
        return super.onBack();
    }

    /**
     * 「贴图」属性:默认直接列工程 {@code assets/} 里的资源(文件夹可进、png 直接选、
     * {@code .atlas} 展开成区域),底部还有「浏览其它文件...」走宿主文件选择器挑别处的图。
     * 拿不到工程 assets 目录时才直接用宿主文件选择器。
     */
    public void pickAsset(final Property property, final Editor.Callback callback) {
        if (assetsDir != null && new File(assetsDir).isDirectory()) {
            new AssetPicker(this, new File(assetsDir), new AssetPicker.OnPick() {
                @Override
                public void onPick(String asset) {
                    apply(callback, property, asset);
                }
            }, new AssetPicker.RemotePicker() {
                @Override
                public void browse(AssetPicker.OnPick onPick) {
                    browseRemote(onPick);
                }
            }).show();
            return;
        }
        browseRemote(new AssetPicker.OnPick() {
            @Override
            public void onPick(String asset) {
                apply(callback, property, asset);
            }
        });
    }

    /** 走宿主自带的文件选择器(跳出工程 assets 目录、或拿不到 assets 时用)。 */
    private void browseRemote(final AssetPicker.OnPick onPick) {
        try {
            openFileSelector(new String[]{".png", ".jpg", ".jpeg", ".webp", ".atlas"},
                    new FileSelector.FileCallback() {
                        @Override
                        public void onSelect(String path) {
                            handlePicked(path, onPick);
                        }
                    });
        } catch (Throwable t) {
            PluginLog.write("宿主文件选择器不可用:" + t);
            Toast.makeText(this, "文件选择器不可用:" + t, Toast.LENGTH_LONG).show();
        }
    }

    private void handlePicked(String path, final AssetPicker.OnPick onPick) {
        if (path == null) {
            return;
        }
        final String relative = relativeToAssets(path);
        if (relative.toLowerCase().endsWith(".atlas")) {
            // 图集要再挑一个区域:ui/items.atlas#basketball0
            AssetPicker.forAtlas(this, new File(path), new AssetPicker.OnPick() {
                @Override
                public void onPick(String region) {
                    onPick.onPick(relative + "#" + region);
                }
            }).show();
        } else {
            onPick.onPick(relative);
        }
    }

    private void apply(Editor.Callback callback, Property property, String value) {
        if (callback != null) {
            callback.updateProperty(property, property.makeValue(value));
        }
        // 换完贴图立刻刷新预览(框架只 setProperty,不会帮画布重画)
        if (canvasView != null) {
            canvasView.post(new Runnable() {
                @Override
                public void run() {
                    canvasView.sync();
                }
            });
        }
    }

    private String relativeToAssets(String path) {
        if (assetsDir == null) {
            return path;
        }
        String base = new File(assetsDir).getAbsolutePath();
        if (path.startsWith(base)) {
            String rest = path.substring(base.length());
            if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            return rest;
        }
        return path;
    }

    // ------------------------------------------------------------------ 抽屉铺满

    private void stretchDrawers(View root) {
        DrawerLayout drawer = findDrawer(root);
        if (drawer == null) {
            return;
        }
        drawer.setFitsSystemWindows(false);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        for (int i = 0; i < drawer.getChildCount(); i++) {
            View child = drawer.getChildAt(i);
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof DrawerLayout.LayoutParams)) {
                continue;
            }
            DrawerLayout.LayoutParams lp = (DrawerLayout.LayoutParams) raw;
            int gravity = lp.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            if (gravity == Gravity.LEFT || gravity == Gravity.RIGHT) {
                lp.width = screenWidth;                    // 不给 DrawerLayout 留 64dp 边距的余地
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lp.setMargins(0, 0, 0, 0);
                child.setLayoutParams(lp);
            }
        }
    }

    private DrawerLayout findDrawer(View view) {
        if (view instanceof DrawerLayout) {
            return (DrawerLayout) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                DrawerLayout found = findDrawer(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 元素树与操作

    private void rebuildElementTree() {
        elementImpls.clear();
        stageElement = new StageElementImpl(
                SceneElementProvider.elementClass(SceneElementProvider.KIND_STAGE), model);
        for (SceneElement element : model.elements) {
            SceneElementImpl impl = new SceneElementImpl(
                    SceneElementProvider.elementClass(element.kind), element);
            elementImpls.put(element, impl);
            stageElement.addChild(impl);
        }
        elementTree.setElements(stageElement);
    }

    private void selectInTree(SceneElement element) {
        Element target = element == null ? null : elementImpls.get(element);
        if (target == null) {
            elementTree.activeElement(stageElement);
        } else {
            elementTree.activeElement(target);
        }
        canvasView.highlight(element);
    }

    private void syncTree() {
        canvasView.sync();
        rebuildElementTree();
    }

    private SceneElement currentElement() {
        Element current = elementTree.getCurrentElement();
        if (current instanceof SceneElementImpl) {
            return ((SceneElementImpl) current).model;
        }
        return null;
    }

    private void move(int delta) {
        SceneElement element = currentElement();
        if (element == null) {
            Toast.makeText(this, "先在结构树或画布上选中一个组件", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = model.elements.indexOf(element);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= model.elements.size()) {
            Toast.makeText(this, delta < 0 ? "已经是第一个了" : "已经是最后一个了", Toast.LENGTH_SHORT).show();
            return;
        }
        model.elements.remove(index);
        model.elements.add(target, element);
        syncTree();
        selectInTree(element);
    }

    private void duplicate() {
        SceneElement element = currentElement();
        if (element == null) {
            Toast.makeText(this, "先在结构树或画布上选中一个组件", Toast.LENGTH_SHORT).show();
            return;
        }
        SceneElement copy = element.copy(model.uniqueName(element.kind));
        model.elements.add(copy);
        syncTree();
        selectInTree(copy);
    }

    private void deleteCurrent() {
        SceneElement element = currentElement();
        if (element == null) {
            Toast.makeText(this, "先在结构树或画布上选中一个组件（舞台删不掉）", Toast.LENGTH_SHORT).show();
            return;
        }
        model.elements.remove(element);
        syncTree();
        selectInTree(model.elements.isEmpty() ? null : model.elements.get(0));
    }

    private void insertCode() {
        String noTexture = elementsWithoutTexture();
        if (noTexture != null) {
            Toast.makeText(this, noTexture + " 还没选贴图,画不出来:选中它,在「属性 → 贴图」里选一张",
                    Toast.LENGTH_LONG).show();
        }
        if (filePath != null) {
            dirty = true;
            boolean ok = autoSave(true);
            Toast.makeText(this, ok ? "代码已写回 " + fileName() : "写入失败:" + filePath,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // 没有目标文件(从工程菜单进来):插到当前打开的编辑器,没有就提示复制
        try {
            com.rapid.api.component.widget.editor.Editor hostEditor = SceneModelHolder.editor();
            if (hostEditor != null) {
                String code = CodeGen.generate(model, sceneClassName(), false);
                String current = hostEditor.getText() == null ? "" : hostEditor.getText().toString();
                hostEditor.setText(CodeMerge.merge(current, code));
                PluginLog.write("设计布局插入代码:" + code.length() + " 字符");
                Toast.makeText(this, "代码已插入", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "没有目标文件,也没有打开的编辑器,先复制代码吧", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            PluginLog.write("插入代码失败:" + t);
        }
        finish();
    }

    /**
     * 把当前可视化场景写回目标 .t:同名类整块替换、否则追加;同时存一份 {@code <文件>.scene.json}。
     *
     * @param force true = 即使没检测到改动也写(点「插入代码」时用)
     * @return 是否真的写了
     */
    private boolean autoSave(boolean force) {
        if (saved && !force) {
            return false;
        }
        if (filePath == null) {
            saved = true;
            return false;
        }
        if (!dirty && !force) {
            saved = true;
            return false;
        }
        String current = SceneStore.currentText(filePath);
        // 安全护栏:同名类若不是本编辑器生成的场景类(比如用户的「类 XXX : 游戏类」),
        // 换一个不重名的「XXX场景」,绝不整块替换用户自己的类
        String className = CodeMerge.resolveSceneClassName(current, sceneClassName());
        if (className != null && !className.equals(model.className)) {
            model.className = className;
        }
        String code = CodeGen.generate(model, className, false);
        String merged = CodeMerge.merge(current, code);
        boolean ok = SceneStore.writeText(filePath, merged);
        SceneStore.saveModel(filePath, model);
        saved = true;
        dirty = false;
        PluginLog.write("自动写回 " + filePath + ":" + code.length() + " 字符 ok=" + ok);
        return ok;
    }

    private String fileName() {
        return filePath == null ? "" : new File(filePath).getName();
    }

    /** 没贴图的精灵/图片：生成出来也是空白，插入前提醒一下。 */
    private String elementsWithoutTexture() {
        StringBuilder sb = new StringBuilder();
        for (SceneElement e : model.elements) {
            boolean needsTexture = SceneElement.KIND_SPRITE.equals(e.kind) || SceneElement.KIND_IMAGE.equals(e.kind);
            if (needsTexture && (e.asset == null || e.asset.trim().isEmpty())) {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(e.name);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void copyCode() {
        try {
            String code = CodeGen.generate(model, sceneClassName(), false);
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("libgdx", code));
                Toast.makeText(this, "代码已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            PluginLog.write("复制代码失败：" + t);
        }
    }

    /** 生成的类名：优先用被编辑的那个文件名（小鸡顶篮球场景.t → 小鸡顶篮球场景）。 */
    private String sceneClassName() {
        if (targetClassName != null && targetClassName.length() > 0) {
            return CodeGen.sanitize(targetClassName);
        }
        if (model != null && model.className != null && model.className.length() > 0) {
            return CodeGen.sanitize(model.className);
        }
        return CodeGen.sanitize("游戏场景");
    }
}
