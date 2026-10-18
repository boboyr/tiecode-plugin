package com.dsh.libgdxeditor;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 「贴图」属性的选择器:默认直接列工程 {@code assets/} 里的资源,不用手打路径
 * (避免 basketballo / basketball0 这种笔误)。
 *
 * <p>列表规则:
 * <ul>
 *   <li>子目录 → 可点进入;非根目录时最上面有「⬆ 上一级」</li>
 *   <li>png/jpg/jpeg/webp → 直接选,存相对 {@code assets/} 的路径</li>
 *   <li>{@code .atlas} → 展开成一个个区域,选中的存成 {@code ui/items.atlas#区域名}</li>
 * </ul>
 * 底部还有「浏览其它文件...」,走宿主自带的文件选择器(拿不到 assets 时也能选别处的图)。</p>
 *
 * <p>{@link #forAtlas} 是另一种用法:宿主文件选择器挑到一个 {@code .atlas} 之后,
 * 只列这一个图集里的区域。</p>
 */
public class AssetPicker extends Dialog {

    public interface OnPick {
        void onPick(String asset);
    }

    /** 「浏览其它文件...」交给外面(宿主文件选择器)处理。 */
    public interface RemotePicker {
        void browse(OnPick onPick);
    }

    private static final String[] IMAGE_EXT = {".png", ".jpg", ".jpeg", ".webp"};

    private final File root;
    private File currentDir;
    private final File singleAtlas;
    private final OnPick callback;
    private final RemotePicker remote;
    private LinearLayout content;
    private TextView titleView;

    public AssetPicker(Context context, File root, OnPick callback) {
        this(context, root, root, null, callback, null);
    }

    public AssetPicker(Context context, File root, OnPick callback, RemotePicker remote) {
        this(context, root, root, null, callback, remote);
    }

    /** 只列某一个图集里的区域(宿主文件选择器挑到 .atlas 之后用)。 */
    public static AssetPicker forAtlas(Context context, File atlasFile, OnPick callback) {
        File dir = atlasFile == null ? null : atlasFile.getParentFile();
        return new AssetPicker(context, dir, dir, atlasFile, callback, null);
    }

    private AssetPicker(Context context, File root, File currentDir, File singleAtlas,
                        OnPick callback, RemotePicker remote) {
        super(context);
        this.root = root;
        this.currentDir = currentDir;
        this.singleAtlas = singleAtlas;
        this.callback = callback;
        this.remote = remote;
    }

    @Override
    protected void onCreate(android.os.Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(0xFF1B1F26);

        titleView = new TextView(getContext());
        titleView.setTextColor(0xFFE6EDF3);
        titleView.setTextSize(15);
        titleView.setPadding(dp(12), dp(10), dp(12), dp(6));
        layout.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(getContext());
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content);
        layout.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(getContext());
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        if (remote != null) {
            Button other = makeButton("浏览其它文件...");
            other.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    remote.browse(new OnPick() {
                        @Override
                        public void onPick(String asset) {
                            if (callback != null) callback.onPick(asset);
                            dismiss();
                        }
                    });
                }
            });
            bottom.addView(other, new LinearLayout.LayoutParams(0, dp(44), 1f));
        }
        Button close = makeButton("关闭");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        bottom.addView(close, new LinearLayout.LayoutParams(0, dp(44), 1f));
        layout.addView(bottom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(layout);
        bindTitle();
        refresh();

        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    (int) (getContext().getResources().getDisplayMetrics().heightPixels * 0.75f));
            w.setBackgroundDrawable(new ColorDrawable(0xFF1B1F26));
        }
    }

    private void bindTitle() {
        if (titleView == null) {
            return;
        }
        if (singleAtlas != null) {
            titleView.setText("选择 " + singleAtlas.getName() + " 里的区域");
        } else if (currentDir != null) {
            titleView.setText("选择贴图(" + currentDir.getAbsolutePath() + ")");
        } else {
            titleView.setText("选择贴图(找不到工程 assets 目录)");
        }
    }

    // ------------------------------------------------------------------ 列表

    private void refresh() {
        content.removeAllViews();

        if (singleAtlas != null) {
            refreshAtlasOnly();
            return;
        }
        if (currentDir == null || !currentDir.isDirectory()) {
            content.addView(hint("找不到工程 assets 目录:"
                    + (root == null ? "null" : root.getAbsolutePath())
                    + "\n可以用底部的「浏览其它文件...」挑一张图"));
            return;
        }

        boolean atRoot = root != null
                && currentDir.getAbsolutePath().equals(root.getAbsolutePath());
        if (!atRoot) {
            content.addView(item("⬆ 上一级", null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentDir = currentDir.getParentFile();
                    refreshDialog();
                }
            }));
        }

        File[] files = currentDir.listFiles();
        if (files == null) {
            content.addView(hint("读不到目录:" + currentDir.getAbsolutePath()));
            return;
        }
        Arrays.sort(files);
        List<File> dirs = new ArrayList<File>();
        List<File> images = new ArrayList<File>();
        List<File> atlases = new ArrayList<File>();
        for (File f : files) {
            String n = f.getName().toLowerCase();
            if (f.isDirectory()) {
                dirs.add(f);
            } else if (n.endsWith(".atlas")) {
                atlases.add(f);
            } else if (imageExt(n)) {
                images.add(f);
            }
        }
        for (final File d : dirs) {
            content.addView(item("📁 " + d.getName() + "/", null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentDir = d;
                    refreshDialog();
                }
            }));
        }
        for (File f : images) {
            final String rel = relative(f);
            content.addView(item(rel, null, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callback != null) callback.onPick(rel);
                    dismiss();
                }
            }));
        }
        for (File atlas : atlases) {
            final String atlasRel = relative(atlas);
            content.addView(section(atlasRel + " 的区域"));
            List<String> regions = AtlasRegions.names(atlas);
            for (String region : regions) {
                final String asset = atlasRel + "#" + region;
                content.addView(item("    " + region, Color_LABEL, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (callback != null) callback.onPick(asset);
                        dismiss();
                    }
                }));
            }
            if (regions.isEmpty()) {
                content.addView(hint("    (这个图集里没读到区域)"));
            }
        }
        if (dirs.isEmpty() && images.isEmpty() && atlases.isEmpty()) {
            content.addView(hint("(这个目录里没有图片或图集)"));
        }
    }

    private void refreshAtlasOnly() {
        content.removeAllViews();
        String rel = relative(singleAtlas);
        content.addView(section(rel + " 的区域"));
        List<String> regions = AtlasRegions.names(singleAtlas);
        for (String region : regions) {
            final String asset = rel + "#" + region;
            content.addView(item("    " + region, Color_LABEL, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (callback != null) callback.onPick(asset);
                    dismiss();
                }
            }));
        }
        if (regions.isEmpty()) {
            content.addView(hint("(这个图集里没读到区域)"));
        }
    }

    /** 切目录后重画标题和列表。 */
    private void refreshDialog() {
        bindTitle();
        refresh();
    }

    private static final int Color_LABEL = 0xFF4C8DFF;

    private View item(String label, Integer color, View.OnClickListener click) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(color == null ? 0xFFE6EDF3 : color);
        t.setTextSize(13);
        t.setPadding(dp(16), dp(10), dp(12), dp(10));
        t.setOnClickListener(click);
        return t;
    }

    private View hint(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(0xFF7A8FA6);
        t.setTextSize(13);
        t.setPadding(dp(16), dp(12), dp(12), dp(12));
        return t;
    }

    private View section(String label) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextColor(0xFF4C8DFF);
        t.setTextSize(12);
        t.setPadding(dp(10), dp(12), dp(10), dp(4));
        return t;
    }

    private Button makeButton(String text) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    // ------------------------------------------------------------------ 工具

    private String relative(File f) {
        if (root == null) {
            return f.getName();
        }
        String base = root.getAbsolutePath();
        String path = f.getAbsolutePath();
        if (path.startsWith(base)) {
            path = path.substring(base.length());
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
        }
        return path;
    }

    private static boolean imageExt(String lowerName) {
        for (String ext : IMAGE_EXT) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private int dp(float v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density);
    }
}
