package com.dsh.libgdxeditor;

/** 场景里的一个元素：舞台上的演员，或者一个直接绘制的精灵。 */
public class SceneElement {
    public static final String KIND_ACTOR  = "演员";
    public static final String KIND_SPRITE = "精灵";
    public static final String KIND_IMAGE  = "图片";
    public static final String KIND_LABEL  = "标签";
    public static final String KIND_BUTTON = "文本按钮";
    public static final String KIND_TABLE  = "表格";
    public static final String KIND_GROUP  = "组";

    public static final String[] KINDS = {
            KIND_ACTOR, KIND_IMAGE, KIND_LABEL, KIND_BUTTON, KIND_TABLE, KIND_GROUP, KIND_SPRITE
    };

    public String kind = KIND_ACTOR;
    public String name = "演员1";
    public String text = "";
    /** 贴图：图片/精灵用。图片写图片路径；精灵写「图集.atlas#区域名」或图片路径；留空就只有色块。 */
    public String asset = "";
    public float x, y, w = 120, h = 60, rotation;

    public SceneElement() {
    }

    public SceneElement(String kind, String name, float x, float y, float w, float h) {
        this.kind = kind;
        this.name = name;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        if (KIND_LABEL.equals(kind) || KIND_BUTTON.equals(kind)) {
            this.text = "文字";
            this.w = 160;
            this.h = 48;
        }
    }

    public SceneElement copy(String newName) {
        SceneElement e = new SceneElement();
        e.kind = kind;
        e.name = newName;
        e.text = text;
        e.asset = asset;
        e.x = x + 16;
        e.y = y - 16;
        e.w = w;
        e.h = h;
        e.rotation = rotation;
        return e;
    }

    /** 精灵不进舞台，是每帧直接画的。 */
    public boolean isSprite() {
        return KIND_SPRITE.equals(kind);
    }

    /** 需要「场景皮肤」才能创建的类型。 */
    public boolean needsSkin() {
        return KIND_LABEL.equals(kind) || KIND_BUTTON.equals(kind);
    }

    /** 画布上的配色（纯视觉，和生成代码无关）。 */
    public int color() {
        if (KIND_ACTOR.equals(kind)) return 0xFF5C8DD6;
        if (KIND_SPRITE.equals(kind)) return 0xFFD6A15C;
        if (KIND_IMAGE.equals(kind)) return 0xFF59B37C;
        if (KIND_LABEL.equals(kind)) return 0xFFB06BC4;
        if (KIND_BUTTON.equals(kind)) return 0xFFCC6A6A;
        if (KIND_TABLE.equals(kind)) return 0xFF7A8FA6;
        if (KIND_GROUP.equals(kind)) return 0xFF8A8F98;
        return 0xFF5C8DD6;
    }

    /** 新元素的默认大小。 */
    public static float[] defaultSize(String kind) {
        if (KIND_LABEL.equals(kind) || KIND_BUTTON.equals(kind)) return new float[]{160, 48};
        if (KIND_IMAGE.equals(kind)) return new float[]{120, 120};
        if (KIND_SPRITE.equals(kind)) return new float[]{64, 64};
        if (KIND_TABLE.equals(kind)) return new float[]{240, 160};
        if (KIND_GROUP.equals(kind)) return new float[]{200, 200};
        return new float[]{120, 60};
    }
}
