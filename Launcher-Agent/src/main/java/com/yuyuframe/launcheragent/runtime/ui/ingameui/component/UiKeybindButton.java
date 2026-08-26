package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;

import java.util.function.Consumer;

/**
 * Bouton de réassignation de touche — clic pour entrer en mode "écoute", puis
 * la prochaine touche pressée (voir UiInputPoller.pollAnyKeyJustPressed) est
 * capturée et affichée. Stocke un NOM de touche (ex: "A", "F1"), pas un code
 * numérique brut : LWJGL2 (1.8.9) et GLFW (1.21+) n'utilisent pas le même
 * référentiel de codes, un nom textuel reste portable entre versions.
 */
public class UiKeybindButton extends UiWidget {

    private static final UiColor BASE  = new UiColor(40, 40, 49, 255);
    private static final UiColor HOVER = new UiColor(52, 52, 62, 255);

    private String keyName;
    private boolean listening;
    private final Consumer<String> onChange;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public UiKeybindButton(float x, float y, float w, float h, String initialKeyName, Consumer<String> onChange) {
        super(x, y, w, h);
        this.keyName = initialKeyName != null ? initialKeyName : "NONE";
        this.onChange = onChange;
    }

    public String keyName() { return keyName; }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        hoverAnim.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
        UiColor bg = listening ? UiTheme.ACCENT_DIM : UiColor.lerp(BASE, HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        String label = listening ? "..." : keyName;
        float scale = UiTheme.scaled(0.48f);
        float tw = renderer.textWidth(label, scale);
        float baseline = y + h / 2f - (UiFont.REGULAR.ascent - UiFont.REGULAR.descent) * scale * UiFont.SIZE_CORRECTION / 2f;
        renderer.drawText(label, x + (w - tw) / 2f, baseline, listening ? UiTheme.ACCENT : UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
    }

    @Override
    public void onClick() {
        listening = true;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        if (!listening) return;
        String pressed = input.pollAnyKeyJustPressed();
        if (pressed != null) {
            keyName = pressed;
            listening = false;
            if (onChange != null) onChange.accept(keyName);
        }
    }
}
