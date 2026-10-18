package com.dsh.libgdxeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 舞台上的一个元素子 View:优先画贴图,没有贴图(或加载失败)才画色块 + 名字/文字。
 * 位置、大小、旋转都由 {@link SceneCanvasView} 按模型坐标摆好,这里不碰坐标。
 *
 * <p>贴图字符串经 {@link AssetBitmaps} 解析:png 直接解码,{@code 图集.atlas#区域}
 * 从页贴图上裁出区域;atlas 里 {@code rotate:true} 的区域会先转正再画。</p>
 */
public class SceneElementView extends View {

    public final SceneElement model;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();

    public SceneElementView(Context context, SceneElement model) {
        super(context);
        this.model = model;
        setClickable(false);
        setFocusable(false);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        rect.set(0.5f, 0.5f, w - 0.5f, h - 0.5f);

        AssetBitmaps.Preview preview = loadPreview();
        if (preview != null && preview.bitmap != null && w > 1 && h > 1) {
            drawBitmap(canvas, preview, w, h);
        } else {
            drawColorBlock(canvas, w, h);
        }
    }

    /** 有贴图时画贴图:等比缩放居中,保持元素里的位置/大小。 */
    private void drawBitmap(Canvas canvas, AssetBitmaps.Preview preview, float w, float h) {
        Bitmap bmp = preview.bitmap;
        if (preview.rotated) {
            bmp = rotate90(bmp);
        }
        if (bmp == null) {
            drawColorBlock(canvas, w, h);
            return;
        }
        float bw = bmp.getWidth();
        float bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) {
            drawColorBlock(canvas, w, h);
            return;
        }
        float scale = Math.min(w / bw, h / bh);
        float dw = bw * scale;
        float dh = bh * scale;
        float left = (w - dw) / 2f;
        float top = (h - dh) / 2f;
        RectF dst = new RectF(left, top, left + dw, top + dh);
        canvas.drawBitmap(bmp, null, dst, bitmapPaint);
    }

    /** 没有贴图时画色块 + 标签。 */
    private void drawColorBlock(Canvas canvas, float w, float h) {
        float radius = Math.min(8f, Math.min(w, h) / 4f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(model.color());
        canvas.drawRoundRect(rect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(0xCC000000);
        canvas.drawRoundRect(rect, radius, radius, paint);

        String label = model.text == null || model.text.isEmpty() ? model.name : model.text;
        if (w > 8 && h > 8) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextSize(Math.max(10f, Math.min(h / 2.4f, w / 6f)));
            paint.setTextAlign(Paint.Align.LEFT);
            float baseline = h / 2f + paint.getTextSize() / 3f;
            canvas.save();
            canvas.clipRect(2f, 0f, w - 2f, h);
            canvas.drawText(label, 4f, baseline, paint);
            canvas.restore();
        }
    }

    private AssetBitmaps.Preview loadPreview() {
        if (model.asset == null || model.asset.trim().isEmpty()) {
            return null;
        }
        return AssetBitmaps.preview(SceneModelHolder.assetsDir(), model.asset);
    }

    private static Bitmap rotate90(Bitmap src) {
        try {
            Matrix m = new Matrix();
            m.postRotate(90);
            return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        } catch (Throwable t) {
            return src;
        }
    }
}
