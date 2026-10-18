package com.dsh.libgdxeditor;

import com.rapid.api.component.widget.editor.Editor;

/** 设计器打开期间共享:当前场景、宿主代码编辑器、正在用的画布。 */
public final class SceneModelHolder {

    /** 左栏点一个组件后,provider 建好元素和 impl,通知设计器把 impl 记下来。 */
    public interface ElementCreatedListener {
        void onElementCreated(SceneElement element, SceneElementImpl impl);
    }

    /** 模型被改过(加/删/改属性/拖动)→ 设计器据此判定「有改动」,退出时自动写回。 */
    public interface ModelChangedListener {
        void onModelChanged();
    }

    private static SceneModel current;
    private static String baseDir;
    private static String assetsDir;
    private static Editor editor;
    private static SceneCanvasView canvas;
    private static ElementCreatedListener elementCreatedListener;
    private static ModelChangedListener modelChangedListener;

    private SceneModelHolder() {
    }

    public static void set(SceneModel model) {
        current = model;
    }

    public static SceneModel current() {
        return current;
    }

    public static void setBaseDir(String dir) {
        baseDir = dir;
    }

    public static String baseDir() {
        return baseDir;
    }

    public static void setEditor(Editor hostEditor) {
        editor = hostEditor;
    }

    public static Editor editor() {
        return editor;
    }

    public static void setAssetsDir(String dir) {
        assetsDir = dir;
    }

    public static String assetsDir() {
        return assetsDir;
    }

    public static void setCanvas(SceneCanvasView view) {
        canvas = view;
    }

    public static SceneCanvasView canvas() {
        return canvas;
    }

    public static void setElementCreatedListener(ElementCreatedListener listener) {
        elementCreatedListener = listener;
    }

    public static void setModelChangedListener(ModelChangedListener listener) {
        modelChangedListener = listener;
    }

    public static void notifyElementCreated(SceneElement element, SceneElementImpl impl) {
        if (elementCreatedListener != null) {
            elementCreatedListener.onElementCreated(element, impl);
        }
    }

    /** 模型结构变了(加/删元素、改属性)→ 画布重排 + 标记有改动。 */
    public static void notifyModelChanged() {
        if (canvas != null) {
            canvas.sync();
        }
        if (modelChangedListener != null) {
            modelChangedListener.onModelChanged();
        }
    }

    /** 只是拖动/缩放改了坐标 → 画布重画 + 标记有改动。 */
    public static void notifyGeometryChanged() {
        if (canvas != null) {
            canvas.invalidate();
        }
        if (modelChangedListener != null) {
            modelChangedListener.onModelChanged();
        }
    }
}
