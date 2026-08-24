package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.UiWidget;

/** Texte statique non-interactif — x,y = position de la ligne de base (voir UiRenderer.drawText). */
public class UiLabel extends UiWidget {

    private final String text;
    private final UiColor color;
    private final float scale;
    private final UiFont font;
    // Ombre portée optionnelle (voir UiRenderer#drawTextShadowed) — utile
    // pour un label posé sur un fond TRANSPARENT (voir UiModConfigScreen,
    // "voir ce qui se passe derrière") : sans elle, le texte perd du
    // contraste selon ce qui défile derrière (monde, ciel clair...).
    private final boolean shadow;

    public UiLabel(float x, float y, String text, UiColor color, float scale) {
        this(x, y, text, color, scale, UiFont.REGULAR);
    }

    public UiLabel(float x, float y, String text, UiColor color, float scale, UiFont font) {
        this(x, y, text, color, scale, font, false);
    }

    public UiLabel(float x, float y, String text, UiColor color, float scale, UiFont font, boolean shadow) {
        super(x, y, 0, 0);
        this.text = text;
        this.color = color;
        this.scale = scale;
        this.font = font;
        this.shadow = shadow;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (shadow) {
            renderer.drawTextShadowed(font, text, x, y, color, new UiColor(0, 0, 0, 150), scale, scale, scale, vpWidth, vpHeight);
        } else {
            renderer.drawText(font, text, x, y, color, scale, vpWidth, vpHeight);
        }
    }
}
