package com.dsh.libgdxeditor;

import com.rapid.framework.vfs.VirtualFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

/**
 * 设计器的持久化:读/写目标 {@code .t} 文件文本、读写 {@code <文件>.scene.json}。
 *
 * <p>写回策略:
 * <ul>
 *   <li>目标文件正被宿主代码编辑器打开 → 用 {@code editor.setText(...)}(保持编辑器同步);</li>
 *   <li>否则直接写磁盘文件。</li>
 * </ul>
 * {@code .scene.json} 用来记住舞台尺寸和元素快照,保证「下次进入还能接着编辑」。</p>
 */
public final class SceneStore {

    private SceneStore() {
    }

    public static String jsonPath(String filePath) {
        return filePath == null ? null : filePath + ".scene.json";
    }

    /** 宿主代码编辑器当前打开的文件路径;拿不到返回 null。 */
    public static String editorPath() {
        try {
            com.rapid.api.component.widget.editor.Editor editor = SceneModelHolder.editor();
            VirtualFile vf = editor == null ? null : editor.getVirtualFile();
            return vf == null ? null : vf.getPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读目标文件文本:优先宿主编辑器(正打开同一文件),否则读磁盘。 */
    public static String readText(String filePath) {
        com.rapid.api.component.widget.editor.Editor editor = SceneModelHolder.editor();
        if (editor != null && filePath != null && filePath.equals(editorPath())) {
            CharSequence cs = editor.getText();
            if (cs != null) {
                return cs.toString();
            }
        }
        return readFile(filePath);
    }

    public static String readFile(String filePath) {
        if (filePath == null) {
            return null;
        }
        try {
            File f = new File(filePath);
            if (!f.isFile()) {
                return null;
            }
            byte[] buf = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Throwable t) {
            PluginLog.write("读文件失败 " + filePath + ":" + t);
            return null;
        }
    }

    /** 写目标文件文本:宿主编辑器同一文件时 setText,同时落盘。 */
    public static boolean writeText(String filePath, String text) {
        if (filePath == null || text == null) {
            return false;
        }
        boolean ok = false;
        com.rapid.api.component.widget.editor.Editor editor = SceneModelHolder.editor();
        if (editor != null && filePath.equals(editorPath())) {
            try {
                editor.setText(text);
                ok = true;
            } catch (Throwable t) {
                PluginLog.write("编辑器 setText 失败:" + t);
            }
        }
        try {
            File f = new File(filePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
            w.write(text);
            w.close();
            ok = true;
        } catch (Throwable t) {
            PluginLog.write("写文件失败 " + filePath + ":" + t);
        }
        return ok;
    }

    /** 目标文件文本(编辑器优先,磁盘兜底);都没有返回空串。 */
    public static String currentText(String filePath) {
        String s = readText(filePath);
        if (s != null) {
            return s;
        }
        com.rapid.api.component.widget.editor.Editor editor = SceneModelHolder.editor();
        if (editor != null && editor.getText() != null) {
            return editor.getText().toString();
        }
        return "";
    }

    /** 把场景模型存到 {@code <文件>.scene.json}。 */
    public static void saveModel(String filePath, SceneModel model) {
        if (filePath == null || model == null) {
            return;
        }
        try {
            OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(new File(jsonPath(filePath))), "UTF-8");
            w.write(model.toJson());
            w.close();
        } catch (Throwable t) {
            PluginLog.write("保存场景失败:" + t);
        }
    }

    /**
     * 读回场景:先用代码文本回读(反映用户手改过的最新内容),
     * 再用 {@code .scene.json} 补舞台尺寸、并在代码里读不出元素时兜底。
     */
    public static SceneModel loadModel(String filePath, String text) {
        SceneModel parsed = text == null ? null : CodeGen.parse(text);
        if (parsed == null) {
            parsed = new SceneModel();
        }
        String jp = jsonPath(filePath);
        if (jp != null) {
            String s = readFile(jp);
            if (s != null && s.trim().length() > 0) {
                SceneModel json = SceneModel.fromJson(s);
                parsed.stageWidth = json.stageWidth;
                parsed.stageHeight = json.stageHeight;
                if (json.className != null && !json.className.isEmpty()) {
                    parsed.className = json.className;
                }
                if (parsed.elements.isEmpty()) {
                    parsed.elements.addAll(json.elements);
                }
            }
        }
        return parsed;
    }

    /** 文本里是否已有顶层类(决定能不能往非场景文件里追加新类)。 */
    public static boolean hasTopLevelClass(String text) {
        return CodeMerge.classNameOf(text) != null;
    }
}
