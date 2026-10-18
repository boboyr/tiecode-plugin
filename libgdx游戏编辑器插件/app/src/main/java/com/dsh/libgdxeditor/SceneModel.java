package com.dsh.libgdxeditor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 一个可视场景：舞台尺寸 + 元素列表（可以存成 json 跟着代码文件走）。 */
public class SceneModel {
    public float stageWidth = 480;
    public float stageHeight = 800;
    /** 生成的场景类名(持久化,保证重入时用同一个类名去替换而不是新增)。 */
    public String className = "";
    public final List<SceneElement> elements = new ArrayList<SceneElement>();

    public SceneModel() {
    }

    public SceneElement find(String name) {
        for (SceneElement e : elements) {
            if (e.name.equals(name)) return e;
        }
        return null;
    }

    public String uniqueName(String kind) {
        for (int i = 1; i < 999; i++) {
            String n = kind + i;
            if (find(n) == null) return n;
        }
        return kind + System.currentTimeMillis();
    }

    public SceneModel copy() {
        SceneModel m = new SceneModel();
        m.stageWidth = stageWidth;
        m.stageHeight = stageHeight;
        for (SceneElement e : elements) {
            SceneElement c = e.copy(e.name);
            c.x = e.x;
            c.y = e.y;
            m.elements.add(c);
        }
        return m;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("stageWidth", stageWidth);
            o.put("stageHeight", stageHeight);
            o.put("className", className == null ? "" : className);
            JSONArray arr = new JSONArray();
            for (SceneElement e : elements) {
                JSONObject j = new JSONObject();
                j.put("kind", e.kind);
                j.put("name", e.name);
                j.put("text", e.text);
                j.put("asset", e.asset);
                j.put("x", e.x);
                j.put("y", e.y);
                j.put("w", e.w);
                j.put("h", e.h);
                j.put("rotation", e.rotation);
                arr.put(j);
            }
            o.put("elements", arr);
            return o.toString(2);
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static SceneModel fromJson(String s) {
        SceneModel m = new SceneModel();
        try {
            JSONObject o = new JSONObject(s);
            m.stageWidth = (float) o.optDouble("stageWidth", 480);
            m.stageHeight = (float) o.optDouble("stageHeight", 800);
            m.className = o.optString("className", "");
            JSONArray arr = o.optJSONArray("elements");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject j = arr.getJSONObject(i);
                    SceneElement e = new SceneElement();
                    e.kind = j.optString("kind", SceneElement.KIND_ACTOR);
                    e.name = j.optString("name", "元素" + i);
                    e.text = j.optString("text", "");
                    e.asset = j.optString("asset", "");
                    e.x = (float) j.optDouble("x", 0);
                    e.y = (float) j.optDouble("y", 0);
                    e.w = (float) j.optDouble("w", 100);
                    e.h = (float) j.optDouble("h", 60);
                    e.rotation = (float) j.optDouble("rotation", 0);
                    m.elements.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return m;
    }
}
