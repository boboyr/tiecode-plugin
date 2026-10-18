package com.dsh.libgdxeditor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 把模型里的「贴图」字符串解析成画布能画的 {@link Bitmap}。
 *
 * <p>贴图字符串有两种:
 * <ul>
 *   <li>图片路径:{@code ui/items.png}(相对工程 {@code assets/})</li>
 *   <li>图集区域:{@code ui/items.atlas#background}(图集路径 + {@code #} + 区域名)</li>
 * </ul>
 * 图集还要读出区域在图集页贴图里的 {@code xy/size},从页贴图上裁出那一块。</p>
 */
public final class AssetBitmaps {

    /** 一张位图 + 它是否是带旋转的区域(画布上按旋转处理)。 */
    public static final class Preview {
        public final Bitmap bitmap;
        public final boolean rotated;

        Preview(Bitmap bitmap, boolean rotated) {
            this.bitmap = bitmap;
            this.rotated = rotated;
        }
    }

    private static final Map<String, Bitmap> FILE_CACHE = new HashMap<String, Bitmap>();
    private static final Map<String, Preview> ATLAS_CACHE = new HashMap<String, Preview>();
    private static final Map<String, Boolean> MISS_CACHE = new HashMap<String, Boolean>();

    private AssetBitmaps() {
    }

    /** 只解析图片路径(不含 #)时用;找不到返回 null。 */
    public static Preview preview(String assetsDir, String asset) {
        if (asset == null || asset.trim().isEmpty()) {
            return null;
        }
        String value = asset.trim();
        String key = value + "@" + (assetsDir == null ? "" : assetsDir);
        if (MISS_CACHE.containsKey(key)) {
            return null;
        }
        Preview result = null;
        try {
            int hash = value.indexOf('#');
            if (hash > 0 && value.substring(0, hash).toLowerCase().endsWith(".atlas")) {
                result = atlasPreview(assetsDir, value.substring(0, hash), value.substring(hash + 1));
            } else {
                Bitmap bmp = fileBitmap(assetsDir, value);
                if (bmp != null) {
                    result = new Preview(bmp, false);
                }
            }
        } catch (Throwable t) {
            PluginLog.write("加载贴图失败 " + value + ":" + t);
        }
        if (result == null) {
            MISS_CACHE.put(key, Boolean.TRUE);
        }
        return result;
    }

    private static Preview atlasPreview(String assetsDir, String atlasPath, String region) {
        String cacheKey = atlasPath + "#" + region + "@" + (assetsDir == null ? "" : assetsDir);
        Preview cached = ATLAS_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        File atlasFile = resolve(assetsDir, atlasPath);
        if (atlasFile == null || !atlasFile.isFile()) {
            return null;
        }
        AtlasRegions.Region r = AtlasRegions.find(atlasFile, region);
        if (r == null || r.w <= 0 || r.h <= 0) {
            return null;
        }
        String page = AtlasRegions.pageImageName(atlasFile);
        if (page == null) {
            return null;
        }
        File pageFile = new File(atlasFile.getParentFile(), page);
        Bitmap pageBitmap = fileBitmapFile(pageFile);
        if (pageBitmap == null) {
            return null;
        }
        int x = Math.max(0, Math.min(r.x, pageBitmap.getWidth() - 1));
        int y = Math.max(0, Math.min(r.y, pageBitmap.getHeight() - 1));
        // libgdx 的旋转区域:页贴图里实际矩形是「宽高对调」的(atlas 写的是原图 w/h)
        int cropW = r.swapOnRotate ? r.h : r.w;
        int cropH = r.swapOnRotate ? r.w : r.h;
        int w = Math.min(cropW, pageBitmap.getWidth() - x);
        int h = Math.min(cropH, pageBitmap.getHeight() - y);
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap crop = Bitmap.createBitmap(pageBitmap, x, y, w, h);
        Preview preview = new Preview(crop, r.rotated);
        ATLAS_CACHE.put(cacheKey, preview);
        return preview;
    }

    private static Bitmap fileBitmap(String assetsDir, String path) {
        File f = resolve(assetsDir, path);
        return f == null ? null : fileBitmapFile(f);
    }

    private static Bitmap fileBitmapFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        String key = f.getAbsolutePath();
        Bitmap cached = FILE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeFile(key);
            if (bmp != null) {
                FILE_CACHE.put(key, bmp);
            }
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 assets 相对路径解析成真实文件:先按工程 assets,再按绝对路径兜底。 */
    private static File resolve(String assetsDir, String path) {
        if (path == null) {
            return null;
        }
        File direct = new File(path);
        if (direct.isAbsolute()) {
            return direct;
        }
        if (assetsDir != null) {
            File f = new File(assetsDir, path);
            if (f.exists()) {
                return f;
            }
        }
        return direct;
    }

    /** 工程换了、模型变了时清缓存(可选调用)。 */
    public static void clear() {
        FILE_CACHE.clear();
        ATLAS_CACHE.clear();
        MISS_CACHE.clear();
    }
}
