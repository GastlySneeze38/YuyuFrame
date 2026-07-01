package com.yuyuframe.launcheragent.runtime.ui;

/** Palette centralisée style OneConfig (fond sombre, accent violet) — cohérence visuelle entre tous les widgets/écrans custom. */
public final class UiTheme {
    private UiTheme() {}

    public static final UiColor OVERLAY_BG     = new UiColor(8, 8, 12, 235);
    public static final UiColor SIDEBAR_BG     = new UiColor(17, 17, 22, 255);
    public static final UiColor SIDEBAR_HOVER  = new UiColor(255, 255, 255, 16);
    public static final UiColor SIDEBAR_ACTIVE  = new UiColor(139, 124, 255, 34);
    public static final UiColor PANEL_BG       = new UiColor(27, 27, 34, 255);
    public static final UiColor PANEL_BG_ALT   = new UiColor(34, 34, 43, 255);
    public static final UiColor CARD_BG        = new UiColor(31, 31, 39, 255);
    public static final UiColor CARD_HOVER     = new UiColor(40, 40, 50, 255);

    public static final UiColor ACCENT      = new UiColor(139, 124, 255, 255);
    public static final UiColor ACCENT_DIM  = new UiColor(139, 124, 255, 70);
    public static final UiColor DANGER      = new UiColor(230, 95, 95, 255);

    public static final UiColor TEXT_PRIMARY   = new UiColor(232, 232, 238, 255);
    public static final UiColor TEXT_SECONDARY = new UiColor(150, 150, 163, 255);
    public static final UiColor TEXT_MUTED      = new UiColor(105, 105, 118, 255);

    public static final UiColor TRACK_OFF = new UiColor(55, 55, 65, 255);

    public static final float RADIUS_SM = 4f;
    public static final float RADIUS_MD = 8f;
    public static final float RADIUS_LG = 12f;
}
