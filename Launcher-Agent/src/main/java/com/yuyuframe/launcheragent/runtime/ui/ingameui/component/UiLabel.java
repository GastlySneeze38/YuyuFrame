package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;

/** Texte statique non-interactif — x,y = position de la ligne de base (voir UiRenderer.drawText). */
public class UiLabel extends UiWidget {

    private final String text;
    private final UiColor color;
    private final float scale;
    private final UiFont font;

    public UiLabel(float x, float y, String text, UiColor color, float scale) {
        this(x, y, text, color, scale, UiFont.REGULAR);
    }

    public UiLabel(float x, float y, String text, UiColor color, float scale, UiFont font) {
        super(x, y, 0, 0);
        this.text = text;
        this.color = color;
        this.scale = scale;
        this.font = font;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        renderer.drawText(font, text, x, y, color, scale, vpWidth, vpHeight);
    }
}
