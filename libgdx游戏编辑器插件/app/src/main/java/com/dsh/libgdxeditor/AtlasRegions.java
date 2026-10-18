package com.dsh.libgdxeditor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * libgdx 纹理图集({@code .atlas})的极简解析。
 *
 * <p>格式(参考 libgdx TextureAtlas 写出格式):先是一段页贴图头
 * ({@code xxx.png}、{@code size:}、{@code format:}、{@code filter:}、{@code repeat:}),
 * 后面每个区域是「顶格、不含冒号的一行区域名 + 若干缩进属性行」。</p>
 *
 * <p>区域属性里要用的是:{@code xy: x, y}、{@code size: w, h}、{@code rotate: true/false}。</p>
 */
public final class AtlasRegions {

    /** 一个区域在页贴图里的位置和大小。 */
    public static final class Region {
        public final String name;
        public final int x, y, w, h;
        public final boolean rotated;
        /** true = libgdx TextureAtlas(旋转区域在页贴图里宽高对调,裁剪时要交换 w/h)。 */
        public final boolean swapOnRotate;

        Region(String name, int x, int y, int w, int h, boolean rotated, boolean swapOnRotate) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.rotated = rotated;
            this.swapOnRotate = swapOnRotate;
        }
    }

    private AtlasRegions() {
    }

    /** 列出图集里的区域名(顶格、不含冒号、不是 .png 头的才算区域)。 */
    public static List<String> names(File atlas) {
        List<String> out = new ArrayList<String>();
        if (atlas == null || !atlas.isFile()) {
            return out;
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(atlas), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || !line.equals(line.trim())) {
                    continue;   // 空行 / 缩进行(区域属性)
                }
                if (line.indexOf(':') >= 0) {
                    continue;   // size:/format:/filter:/repeat:
                }
                if (line.toLowerCase().endsWith(".png")) {
                    continue;   // 页贴图文件名
                }
                String name = line.trim();
                if (!name.isEmpty() && !out.contains(name)) {
                    out.add(name);
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 图集里页贴图(.png)的文件名;拿不到时返回 null。 */
    public static String pageImageName(File atlas) {
        if (atlas == null || !atlas.isFile()) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(atlas), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.toLowerCase().endsWith(".png")) {
                    r.close();
                    return t;
                }
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 找到某个区域;找不到返回 null。支持 libgdx(xy/size)和 Spine(bounds)两种写法。 */
    public static Region find(File atlas, String regionName) {
        if (atlas == null || !atlas.isFile() || regionName == null) {
            return null;
        }
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(new FileInputStream(atlas), "UTF-8"));
            String line;
            String current = null;
            int x = 0, y = 0, w = 0, h = 0;
            boolean rotated = false;
            boolean libgdx = false;   // 读到过 xy/size 就是 libgdx 格式
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || !line.equals(line.trim())) {
                    // 缩进行:当前区域的属性
                    String t = line.trim();
                    if (current == null) continue;
                    if (t.startsWith("xy:")) {
                        int[] v = ints(t.substring(3));
                        if (v.length >= 2) { x = v[0]; y = v[1]; libgdx = true; }
                    } else if (t.startsWith("size:")) {
                        int[] v = ints(t.substring(5));
                        if (v.length >= 2) { w = v[0]; h = v[1]; libgdx = true; }
                    } else if (t.startsWith("bounds:")) {
                        // Spine 图集:bounds 就是 x,y,w,h(页贴图里的实际矩形)
                        int[] v = ints(t.substring(7));
                        if (v.length >= 4) { x = v[0]; y = v[1]; w = v[2]; h = v[3]; }
                    } else if (t.startsWith("rotate:")) {
                        String rv = t.substring(7).trim();
                        rotated = rv.startsWith("true") || rv.equals("90");
                    }
                    continue;
                }
                if (line.indexOf(':') >= 0 || line.toLowerCase().endsWith(".png")) {
                    current = null;
                    continue;
                }
                // 新的顶格区域名:先结算上一个
                if (current != null && current.equals(regionName)) {
                    r.close();
                    return new Region(current, x, y, w, h, rotated, rotated && libgdx);
                }
                current = line.trim();
                x = y = w = h = 0;
                rotated = false;
                libgdx = false;
            }
            if (current != null && current.equals(regionName)) {
                r.close();
                return new Region(current, x, y, w, h, rotated, rotated && libgdx);
            }
            r.close();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int[] ints(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                out[n++] = (int) Float.parseFloat(p.trim());
            } catch (Throwable t) {
                out[n++] = 0;
            }
        }
        return out;
    }
}
