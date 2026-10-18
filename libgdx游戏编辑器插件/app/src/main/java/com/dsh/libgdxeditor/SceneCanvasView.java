package com.dsh.libgdxeditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.rapid.api.component.designer.ElementTree;
import com.rapid.api.component.designer.element.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容视图（交给框架 {@code LayoutDesignerView.setContentView}）：舞台容器 + 元素子 View。
 *
 * <p>坐标：模型里是 libgdx 的世界坐标——左下角是原点、Y 向上、单位和舞台像素一一对应。
 * 画布按「适配内容区」算一个缩放 scale，把舞台摆正中间，元素按
 * {@code 左 = 舞台左 + x*scale}、{@code 上 = 舞台上 + (舞台高 - y - 高)*scale} 摆进子 View，
 * 拖动/缩放后再按同样的换算倒着写回模型。</p>
 *
 * <p>框架只负责左栏（组件 / 结构树 / 操作条）和右栏（属性表），画布这一层由这里接管。</p>
 */
public class SceneCanvasView extends FrameLayout implements ElementTree.ElementTreeObserver {

    /** 画布上选中了某个元素（交给外面通知框架的元素树）。 */
    public interface SelectionListener {
        void onElementSelected(SceneElement element);
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_RESIZE = 2;
    private static final int MODE_ROTATE = 3;

    private static final int BACKDROP = 0xFF171B21;
    private static final int STAGE_FILL = 0xFF2B333D;
    private static final int STAGE_BORDER = 0xFF4A5768;
    private static final int GRID_COLOR = 0x22FFFFFF;
    private static final int AXIS_COLOR = 0x5566CCFF;
    private static final int SELECT_COLOR = 0xFFFF8A3D;
    private static final int HANDLE_FILL = 0xFFFFC107;
    private static final float GRID_STEP = 40f;   // 世界坐标里的格距

    private final SceneModel model;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<SceneElementView> views = new ArrayList<SceneElementView>();
    private final float density;

    private SelectionListener selectionListener;
    private ElementTree elementTree;
    private SceneElement active;
    private float scale = 1f;
    private float stageLeft, stageTop, stageWidthPx, stageHeightPx;
    private int mode = MODE_NONE;
    private float downX, downY;
    private float startX, startY, startW, startH;
    private boolean dragged;

    public SceneCanvasView(Context context, SceneModel model) {
        super(context);
        this.model = model == null ? new SceneModel() : model;
        this.density = context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
    }

    public SceneModel getModel() {
        return model;
    }

    public void setSelectionListener(SelectionListener listener) {
        this.selectionListener = listener;
    }

    public void attachElementTree(ElementTree tree) {
        this.elementTree = tree;
        if (tree != null) {
            tree.registerObserver(this);
        }
    }

    /** 模型变了（加元素、改属性）→ 重新对一遍子 View。 */
    public void sync() {
        List<SceneElementView> keep = new ArrayList<SceneElementView>();
        for (SceneElement e : model.elements) {
            SceneElementView found = null;
            for (SceneElementView v : views) {
                if (v.model == e) {
                    found = v;
                    break;
                }
            }
            if (found == null) {
                found = new SceneElementView(getContext(), e);
                addView(found, new FrameLayout.LayoutParams(1, 1));
                views.add(found);
            }
            keep.add(found);
        }
        for (SceneElementView v : views) {
            if (!keep.contains(v)) {
                removeView(v);
            }
        }
        views.clear();
        views.addAll(keep);
        if (active != null && !model.elements.contains(active)) {
            active = null;
        }
        if (elementTree != null) {
            elementTree.notifyElementChanged();
        }
        // 硬件加速下子 View 会复用旧的显示列表,换了贴图不显式 invalidate 看不到变化
        for (SceneElementView v : views) {
            v.invalidate();
        }
        requestLayout();
        invalidate();
    }

    /** 框架的元素树选中了谁 → 画布跟着高亮。 */
    public void highlight(SceneElement element) {
        if (active != element) {
            active = element;
            invalidate();
        }
    }

    // ------------------------------------------------------------------ 元素树观察者

    @Override
    public void onElementActive(Element element) {
        if (element instanceof SceneElementImpl) {
            highlight(((SceneElementImpl) element).model);
        } else {
            highlight(null);
        }
    }

    @Override
    public void onElementResign(Element element) {
    }

    // ------------------------------------------------------------------ 布局

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateStageBox();
    }

    /** 舞台按内容区等比缩放并居中。 */
    private void updateStageBox() {
        int padding = (int) (density * 12);
        float availW = Math.max(1, getWidth() - padding * 2);
        float availH = Math.max(1, getHeight() - padding * 2);
        float sw = Math.max(1f, model.stageWidth);
        float sh = Math.max(1f, model.stageHeight);
        scale = Math.min(availW / sw, availH / sh);
        if (scale <= 0f || Float.isNaN(scale) || Float.isInfinite(scale)) {
            scale = 1f;
        }
        stageWidthPx = sw * scale;
        stageHeightPx = sh * scale;
        stageLeft = (getWidth() - stageWidthPx) / 2f;
        stageTop = (getHeight() - stageHeightPx) / 2f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        updateStageBox();
        for (SceneElementView v : views) {
            int[] size = viewSize(v.model);
            v.measure(MeasureSpec.makeMeasureSpec(size[0], MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(size[1], MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // 不调 super：FrameLayout 会按 gravity 摆，这里严格按模型坐标摆
        updateStageBox();
        for (SceneElementView v : views) {
            float[] box = viewBox(v.model);
            v.layout(Math.round(box[0]), Math.round(box[1]),
                    Math.round(box[0] + box[2]), Math.round(box[1] + box[3]));
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            v.setRotation(-v.model.rotation);
        }
    }

    private int[] viewSize(SceneElement e) {
        return new int[]{
                Math.max(1, Math.round(Math.max(1f, e.w) * scale)),
                Math.max(1, Math.round(Math.max(1f, e.h) * scale))
        };
    }

    /** 世界坐标 → 屏幕坐标（左上角 x/y + 宽高）。 */
    private float[] viewBox(SceneElement e) {
        float w = Math.max(1f, e.w) * scale;
        float h = Math.max(1f, e.h) * scale;
        float left = stageLeft + e.x * scale;
        float top = stageTop + (model.stageHeight - e.y - Math.max(1f, e.h)) * scale;
        return new float[]{left, top, w, h};
    }

    private RectF activeBox() {
        if (active == null) {
            return null;
        }
        float[] box = viewBox(active);
        return new RectF(box[0], box[1], box[0] + box[2], box[1] + box[3]);
    }

    // ------------------------------------------------------------------ 画

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        updateStageBox();
        canvas.drawColor(BACKDROP);

        RectF stage = new RectF(stageLeft, stageTop, stageLeft + stageWidthPx, stageTop + stageHeightPx);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(STAGE_FILL);
        canvas.drawRect(stage, paint);

        // 网格（每 40 个世界单位一格）
        float step = GRID_STEP * scale;
        if (step >= density * 6) {
            paint.setColor(GRID_COLOR);
            paint.setStrokeWidth(1f);
            for (float x = stage.left + step; x < stage.right; x += step) {
                canvas.drawLine(x, stage.top, x, stage.bottom, paint);
            }
            for (float y = stage.bottom - step; y > stage.top; y -= step) {
                canvas.drawLine(stage.left, y, stage.right, y, paint);
            }
        }

        // 左下角是原点：画两条轴提示 Y 向上
        paint.setColor(AXIS_COLOR);
        paint.setStrokeWidth(density * 1.5f);
        canvas.drawLine(stage.left, stage.bottom, stage.right, stage.bottom, paint);
        canvas.drawLine(stage.left, stage.bottom, stage.left, stage.top, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 1.5f);
        paint.setColor(STAGE_BORDER);
        canvas.drawRect(stage, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFB9C4D0);
        paint.setTextSize(density * 12);
        canvas.drawText("舞台 " + (int) model.stageWidth + " × " + (int) model.stageHeight
                + "（左下角是原点，Y 向上）", stage.left, Math.max(density * 14, stage.top - density * 8), paint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        RectF box = activeBox();
        if (box == null) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 2f);
        paint.setColor(SELECT_COLOR);
        canvas.drawRect(box, paint);

        float r = handleRadius();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(HANDLE_FILL);
        // 右下角：改大小
        canvas.drawCircle(box.right, box.bottom, r, paint);
        // 正上方：旋转
        canvas.drawCircle(box.centerX(), box.top - r * 2f, r, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(density * 1f);
        paint.setColor(SELECT_COLOR);
        canvas.drawLine(box.centerX(), box.top, box.centerX(), box.top - r, paint);
    }

    private float handleRadius() {
        return density * 11f;
    }

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = x;
                downY = y;
                dragged = false;
                mode = MODE_NONE;
                RectF box = activeBox();
                float r = handleRadius();
                if (box != null && hit(x, y, box.right, box.bottom, r)) {
                    mode = MODE_RESIZE;
                } else if (box != null && hit(x, y, box.centerX(), box.top - r * 2f, r)) {
                    mode = MODE_ROTATE;
                } else {
                    SceneElementView hitView = hitTest(x, y);
                    if (hitView != null) {
                        select(hitView.model);
                        mode = MODE_MOVE;
                    } else {
                        select(null);
                        mode = MODE_NONE;
                    }
                }
                rememberStart();
                return mode != MODE_NONE;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == MODE_NONE) {
                    return false;
                }
                float dx = x - downX;
                float dy = y - downY;
                if (Math.abs(dx) > density * 3 || Math.abs(dy) > density * 3) {
                    dragged = true;
                }
                applyDrag(dx, dy, x, y);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (mode != MODE_NONE && dragged) {
                    SceneModelHolder.notifyGeometryChanged();
                }
                mode = MODE_NONE;
                return true;
            }
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private boolean hit(float x, float y, float cx, float cy, float r) {
        float reach = r * 1.8f;
        return Math.abs(x - cx) <= reach && Math.abs(y - cy) <= reach;
    }

    private SceneElementView hitTest(float x, float y) {
        for (int i = views.size() - 1; i >= 0; i--) {
            SceneElementView v = views.get(i);
            if (x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom()) {
                return v;
            }
        }
        return null;
    }

    private void rememberStart() {
        if (active == null) {
            return;
        }
        startX = active.x;
        startY = active.y;
        startW = active.w;
        startH = active.h;
    }

    private void applyDrag(float dx, float dy, float x, float y) {
        if (active == null) {
            return;
        }
        if (mode == MODE_MOVE) {
            active.x = startX + dx / scale;
            active.y = startY - dy / scale;      // 屏幕 Y 向下，世界 Y 向上
        } else if (mode == MODE_RESIZE) {
            active.w = Math.max(4f, startW + dx / scale);
            active.h = Math.max(4f, startH + dy / scale);
        } else if (mode == MODE_ROTATE) {
            RectF box = activeBox();
            if (box != null) {
                double angle = Math.toDegrees(Math.atan2(box.centerY() - y, x - box.centerX()));
                active.rotation = (float) (90 - angle);
            }
        }
        requestLayout();
        invalidate();
    }

    /** 选中：画布 → 框架的元素树（右栏属性跟着换）。 */
    private void select(SceneElement element) {
        if (active != element) {
            active = element;
            invalidate();
        }
        if (selectionListener != null) {
            selectionListener.onElementSelected(element);
        } else if (elementTree != null && element == null) {
            elementTree.resignCurrentElement();
        }
    }
}
