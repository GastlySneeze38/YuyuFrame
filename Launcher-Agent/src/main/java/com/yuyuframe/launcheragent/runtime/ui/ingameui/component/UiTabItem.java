package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import java.util.function.BooleanSupplier;

/**
 * Un onglet d'une colonne de navigation (sections d'un écran de réglages) :
 * fond d'accent et liseré quand il est actif, éclaircissement au survol,
 * libellé qui glisse un peu à droite. Partagé par les écrans de réglages d'un
 * module et d'un groupe, qui avaient chacun leur copie à des tailles
 * différentes.
 */
public final class UiTabItem extends UiInteractive {

    private final String label;
    private final BooleanSupplier active;
    private final Runnable action;
    /** L'état actif suit le défilement à chaque image : transition douce plutôt que saut. */
    private final UiAnimatedFloat activeAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);

    /** @param label déjà traduit */
    public UiTabItem(String label, BooleanSupplier active, Runnable action) {
        this.label = label;
        this.active = active;
        this.action = action;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        activeAnim.setTarget(active.getAsBoolean() ? 1f : 0f);
        float on = activeAnim.get();

        UiColor bg = UiColor.lerp(UiColor.lerp(UiColor.TRANSPARENT, UiTheme.SIDEBAR_HOVER, hover), UiTheme.SIDEBAR_ACTIVE, on);
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);
        if (on > 0.01f) {
            float edge = UiTheme.px(UiTheme.Space.S3);
            renderer.drawRoundedRect(x, y + edge, x + edge, y + h - edge, UiTheme.px(UiTheme.Radius.XS),
                UiTheme.ACCENT.multiplyAlpha(on), vpWidth, vpHeight);
        }
        float scale = UiTheme.px(UiTheme.Text.LABEL);
        float shift = Math.max(hover, on) * UiTheme.px(UiTheme.Space.S4);
        float left = UiTheme.px(UiTheme.Space.S16) + shift;
        String shown = UiText.fit(UiFont.REGULAR, label, w - left - UiTheme.px(UiTheme.Space.S8), scale);
        renderer.drawText(shown, x + left, UiText.centeredBaseline(UiFont.REGULAR, y, h, scale),
            UiColor.lerp(UiTheme.TEXT_MUTED, UiTheme.TEXT_PRIMARY, Math.max(on, hover * 0.6f)), scale, vpWidth, vpHeight);
    }

    @Override
    protected void onActivate() {
        if (action != null) action.run();
    }
}
