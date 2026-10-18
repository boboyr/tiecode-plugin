package com.dsh.libgdxeditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结绳代码 <-> 场景模型。
 *
 * 生成：把画布上的元素写成可直接编译的结绳代码（只依赖 libgdx中文游戏引擎6.5）。
 * 解析：反过来读回一段已经生成过的代码（变量声明 + 创建 + 置位置/置大小/旋转），
 *       这样在已有文件上打开编辑器就能接着改，而不是从空白开始。
 */
public class CodeGen {

    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
            "方法", "结束", "类", "属性", "事件", "变量", "常量", "定义", "如果", "否则", "循环",
            "返回", "真", "假", "空", "包名", "是", "为", "引用", "导入", "从", "到", "步长",
            "当", "选择", "判断", "继续", "跳出"));

    private static final Pattern P_VAR = Pattern.compile("^变量\\s+([^\\s:：]+)\\s*[:：]\\s*([^\\s?？]+)\\s*[?？]?\\s*$");
    private static final Pattern P_POS = Pattern.compile("^([^\\s.]+)\\.置位置\\(\\s*(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*\\)$");
    private static final Pattern P_SIZE = Pattern.compile("^([^\\s.]+)\\.置大小\\(\\s*(-?[\\d.]+)\\s*,\\s*(-?[\\d.]+)\\s*\\)$");
    private static final Pattern P_ROT = Pattern.compile("^([^\\s.]+)\\.旋转\\s*=\\s*(-?[\\d.]+)$");
    private static final Pattern P_ROTM = Pattern.compile("^([^\\s.]+)\\.置旋转\\(\\s*(-?[\\d.]+)\\s*\\)$");
    private static final Pattern P_LABEL = Pattern.compile("^([^\\s=]+)\\s*=\\s*标签\\.创建标签\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_BUTTON = Pattern.compile("^([^\\s=]+)\\s*=\\s*文本按钮\\.创建文本按钮\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern P_NEW = Pattern.compile("^([^\\s=]+)\\s*=\\s*创建\\s+([^\\s]+)$");
    private static final Pattern P_TEXTURE = Pattern.compile("^([^\\s.]+)\\.置纹理\\(\\s*纹理\\.创建纹理\\(\\s*\"([^\"]*)\"");
    private static final Pattern P_REGION = Pattern.compile("^([^\\s.]+)\\.置区域\\(\\s*纹理图集\\.创建纹理图集\\(\\s*\"([^\"]*)\"\\s*\\)\\.取区域\\(\\s*\"([^\"]*)\"");
    private static final Pattern P_IMG_ASSET = Pattern.compile("^([^\\s=]+)\\s*=\\s*图片\\.创建图片\\(\\s*纹理\\.创建纹理\\(\\s*\"([^\"]*)\"");
    private static final Pattern P_SPR_REGION = Pattern.compile("^([^\\s=]+)\\s*=\\s*精灵\\.取区域精灵\\(\\s*纹理图集\\.创建纹理图集\\(\\s*\"([^\"]*)\"\\s*\\)\\.取区域\\(\\s*\"([^\"]*)\"");
    private static final Pattern P_SPR_ASSET = Pattern.compile("^([^\\s=]+)\\s*=\\s*精灵\\.取精灵\\(\\s*纹理\\.创建纹理\\(\\s*\"([^\"]*)\"");
    private static final Pattern P_SIZEHDR = Pattern.compile("舞台\\s*(\\d+)\\s*[×x]\\s*(\\d+)");

    // ==================== 生成 ====================

    public static String generate(SceneModel scene, String className) {
        return generate(scene, className, true);
    }

    public static String generate(SceneModel scene, String className, boolean withUsage) {
        String cls = sanitize(className == null || className.isEmpty() ? "游戏场景" : className);
        final boolean comments = withUsage;   // 插入文件时不带任何注释
        StringBuilder sb = new StringBuilder();
        if (comments) {
            sb.append("// ===== libgdx 游戏引擎编辑器 自动生成（舞台 ").append(num(scene.stageWidth))
              .append(" × ").append(num(scene.stageHeight)).append("）=====\n");
            sb.append("// 依赖绳包：libgdx中文游戏引擎6.5\n");
        }
        if (comments) {
            sb.append("// 用法（在你的游戏类里）：\n");
            sb.append("//   变量 场景:").append(cls).append("?\n");
            sb.append("//   初始化里：场景=创建 ").append(cls).append("; 场景.创建场景()\n");
            sb.append("//   渲染里：场景.绘制场景(画布)\n");
        }
        sb.append("类 ").append(cls).append("\n");
        sb.append("\t变量 舞台:舞台?\n");
        if (usesSkin(scene)) sb.append("\t变量 皮肤:场景皮肤?\n");
        for (SceneElement e : scene.elements) {
            sb.append("\t变量 ").append(e.name).append(":").append(e.kind).append("?\n");
        }
        sb.append("\n\t方法 创建场景()\n");
        sb.append("\t\t舞台=创建 舞台\n");
        if (usesSkin(scene)) {
            // 创建默认皮肤() 是结绳类「皮肤」上的静态方法（返回「场景皮肤」），不能写成 场景皮肤.创建默认皮肤()
            sb.append("\t\t皮肤=皮肤.创建默认皮肤()\n");
            if (comments) sb.append("\t\t// 想换好看的样式：皮肤=皮肤.创建皮肤(\"uiskin.json\")\n");
        }
        for (SceneElement e : scene.elements) sb.append(createLine(e, comments));
        sb.append("\t结束 方法\n\n");

        sb.append("\t方法 绘制场景(画布:纹理画布)\n");
        sb.append("\t\t舞台.动作响应(操作类.取时间())\n");
        sb.append("\t\t画布.开始绘制()\n");
        sb.append("\t\t舞台.绘制()\n");
        for (SceneElement e : scene.elements) {
            if (!e.isSprite()) continue;
            if (e.asset == null || e.asset.trim().isEmpty()) {
                // 没贴图的精灵画不出来（以前会在 SpriteBatch.end 里 bind 空纹理崩）
                if (comments) {
                    sb.append("\t\t// ").append(e.name).append(" 还没设贴图，先不画；设了以后把这行注释打开：\n");
                    sb.append("\t\t// ").append(e.name).append(".绘制(画布)\n");
                }
            } else {
                sb.append("\t\t").append(e.name).append(".绘制(画布)\n");
            }
        }
        sb.append("\t\t画布.结束绘制()\n");
        sb.append("\t结束 方法\n\n");

        if (comments) sb.append("\t// 把触摸事件转给舞台（屏幕像素坐标，Y 向下）\n");
        sb.append("\t方法 触摸(屏幕X:整数, 屏幕Y:整数, 按下:逻辑型)\n");
        sb.append("\t\t如果 按下 则\n");
        sb.append("\t\t\t舞台.触摸按下(屏幕X, 屏幕Y, 0, 0)\n");
        sb.append("\t\t否则\n");
        sb.append("\t\t\t舞台.触摸抬起(屏幕X, 屏幕Y, 0, 0)\n");
        sb.append("\t\t结束 如果\n");
        sb.append("\t结束 方法\n");
        sb.append("结束 类\n");
        return sb.toString();
    }

    private static String createLine(SceneElement e) {
        return createLine(e, false);
    }

    private static String createLine(SceneElement e, boolean comments) {
        StringBuilder sb = new StringBuilder();
        String asset = e.asset == null ? "" : e.asset.trim();
        if (SceneElement.KIND_SPRITE.equals(e.kind)) {
            if (asset.contains("#") && asset.contains(".atlas")) {
                String[] p = asset.split("#", 2);
                sb.append("\t\t").append(e.name).append("=精灵.取区域精灵(纹理图集.创建纹理图集(\"")
                  .append(escape(p[0])).append("\").取区域(\"").append(escape(p[1])).append("\"))\n");
            } else if (!asset.isEmpty()) {
                sb.append("\t\t").append(e.name).append("=精灵.取精灵(纹理.创建纹理(\"").append(escape(asset)).append("\"))\n");
            } else {
                sb.append("\t\t").append(e.name).append("=创建 精灵\n");
                if (comments) sb.append("\t\t// TODO 精灵要贴图：").append(e.name)
                  .append(".置区域(纹理图集.创建纹理图集(\"你的图集.atlas\").取区域(\"区域名\"))\n");
            }
        } else if (SceneElement.KIND_IMAGE.equals(e.kind)) {
            if (!asset.isEmpty()) {
                sb.append("\t\t").append(e.name).append("=图片.创建图片(纹理.创建纹理(\"").append(escape(asset)).append("\"))\n");
            } else {
                sb.append("\t\t").append(e.name).append("=创建 图片\n");
                if (comments) sb.append("\t\t// TODO 想显示图片：").append(e.name).append(".置纹理(纹理.创建纹理(\"你的图片.png\"))\n");
            }
        } else if (SceneElement.KIND_LABEL.equals(e.kind)) {
            sb.append("\t\t").append(e.name).append("=标签.创建标签(\"").append(escape(e.text)).append("\", 皮肤)\n");
        } else if (SceneElement.KIND_BUTTON.equals(e.kind)) {
            sb.append("\t\t").append(e.name).append("=文本按钮.创建文本按钮(\"").append(escape(e.text)).append("\", 皮肤)\n");
        } else if (SceneElement.KIND_TABLE.equals(e.kind)) {
            sb.append("\t\t").append(e.name).append("=创建 表格\n");
        } else if (SceneElement.KIND_GROUP.equals(e.kind)) {
            sb.append("\t\t").append(e.name).append("=创建 组\n");
        } else {
            sb.append("\t\t").append(e.name).append("=创建 演员\n");
        }
        sb.append("\t\t").append(e.name).append(".置位置(").append(num(e.x)).append(", ").append(num(e.y)).append(")\n");
        sb.append("\t\t").append(e.name).append(".置大小(").append(num(e.w)).append(", ").append(num(e.h)).append(")\n");
        if (e.rotation != 0) {
            if (e.isSprite()) sb.append("\t\t").append(e.name).append(".置旋转(").append(num(e.rotation)).append(")\n");
            else sb.append("\t\t").append(e.name).append(".旋转=").append(num(e.rotation)).append("\n");
        }
        if (!e.isSprite()) sb.append("\t\t舞台.添加控件(").append(e.name).append(")\n");
        return sb.toString();
    }

    // ==================== 解析 ====================

    /** 从（本编辑器生成的）结绳代码里读回场景；认不出来就当空场景。 */
    public static SceneModel parse(String code) {
        SceneModel m = new SceneModel();
        if (code == null) return m;
        Matcher h = P_SIZEHDR.matcher(code);
        if (h.find()) {
            try {
                m.stageWidth = Float.parseFloat(h.group(1));
                m.stageHeight = Float.parseFloat(h.group(2));
            } catch (Throwable ignored) {
            }
        }
        Map<String, SceneElement> byName = new LinkedHashMap<String, SceneElement>();
        for (String raw : code.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;
            Matcher v = P_VAR.matcher(line);
            if (v.find()) {
                String name = v.group(1);
                String kind = kindOf(v.group(2));
                if (kind != null && !"舞台".equals(name)) {
                    SceneElement e = new SceneElement(kind, name, 0, 0,
                            SceneElement.defaultSize(kind)[0], SceneElement.defaultSize(kind)[1]);
                    byName.put(name, e);
                    m.elements.add(e);
                }
                continue;
            }
            Matcher lb = P_LABEL.matcher(line);
            if (lb.find()) {
                SceneElement e = byName.get(lb.group(1));
                if (e != null) { e.kind = SceneElement.KIND_LABEL; e.text = unescape(lb.group(2)); }
                continue;
            }
            Matcher bt = P_BUTTON.matcher(line);
            if (bt.find()) {
                SceneElement e = byName.get(bt.group(1));
                if (e != null) { e.kind = SceneElement.KIND_BUTTON; e.text = unescape(bt.group(2)); }
                continue;
            }
            Matcher nw = P_NEW.matcher(line);
            if (nw.find()) {
                SceneElement e = byName.get(nw.group(1));
                String kind = kindOf(nw.group(2));
                if (e != null && kind != null && !e.needsSkin()) e.kind = kind;
                continue;
            }
            Matcher pos = P_POS.matcher(line);
            if (pos.find()) {
                SceneElement e = byName.get(pos.group(1));
                if (e != null) { e.x = f(pos.group(2)); e.y = f(pos.group(3)); }
                continue;
            }
            Matcher sz = P_SIZE.matcher(line);
            if (sz.find()) {
                SceneElement e = byName.get(sz.group(1));
                if (e != null) { e.w = f(sz.group(2)); e.h = f(sz.group(3)); }
                continue;
            }
            Matcher rot = P_ROT.matcher(line);
            if (rot.find()) {
                SceneElement e = byName.get(rot.group(1));
                if (e != null) e.rotation = f(rot.group(2));
                continue;
            }
            Matcher rotm = P_ROTM.matcher(line);
            if (rotm.find()) {
                SceneElement e = byName.get(rotm.group(1));
                if (e != null) e.rotation = f(rotm.group(2));
                continue;
            }
            Matcher ia = P_IMG_ASSET.matcher(line);
            if (ia.find()) {
                SceneElement e = byName.get(ia.group(1));
                if (e != null) e.asset = ia.group(2);
                continue;
            }
            Matcher sr = P_SPR_REGION.matcher(line);
            if (sr.find()) {
                SceneElement e = byName.get(sr.group(1));
                if (e != null) e.asset = sr.group(2) + "#" + sr.group(3);
                continue;
            }
            Matcher sa = P_SPR_ASSET.matcher(line);
            if (sa.find()) {
                SceneElement e = byName.get(sa.group(1));
                if (e != null) e.asset = sa.group(2);
                continue;
            }
            Matcher tex = P_TEXTURE.matcher(line);
            if (tex.find()) {
                SceneElement e = byName.get(tex.group(1));
                if (e != null) e.asset = tex.group(2);
                continue;
            }
            Matcher reg = P_REGION.matcher(line);
            if (reg.find()) {
                SceneElement e = byName.get(reg.group(1));
                if (e != null) e.asset = reg.group(2) + "#" + reg.group(3);
            }
        }
        return m;
    }

    private static String kindOf(String type) {
        if (type == null) return null;
        if (type.equals(SceneElement.KIND_ACTOR)) return SceneElement.KIND_ACTOR;
        if (type.equals(SceneElement.KIND_SPRITE)) return SceneElement.KIND_SPRITE;
        if (type.equals(SceneElement.KIND_IMAGE)) return SceneElement.KIND_IMAGE;
        if (type.equals(SceneElement.KIND_LABEL)) return SceneElement.KIND_LABEL;
        if (type.equals(SceneElement.KIND_BUTTON)) return SceneElement.KIND_BUTTON;
        if (type.equals(SceneElement.KIND_TABLE)) return SceneElement.KIND_TABLE;
        if (type.equals(SceneElement.KIND_GROUP)) return SceneElement.KIND_GROUP;
        return null;
    }

    private static boolean usesSkin(SceneModel scene) {
        for (SceneElement e : scene.elements) if (e.needsSkin()) return true;
        return false;
    }

    public static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c > 127) sb.append(c);
            else sb.append('_');
        }
        if (sb.length() == 0) sb.append("元素");
        if (Character.isDigit(sb.charAt(0))) sb.insert(0, "元素_");
        String s = sb.toString();
        if (KEYWORDS.contains(s)) s = "元素_" + s;
        return s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static float f(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String num(float v) {
        if (Math.abs(v - Math.round(v)) < 0.001f) return String.valueOf(Math.round(v));
        return String.valueOf(Math.round(v * 10) / 10f);
    }
}
