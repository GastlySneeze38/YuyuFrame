package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.util.ArrayList;
import java.util.List;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La navigation flottante du menu (refaite le 2026-11-20 : ce n'est plus une
 * bande sur toute la largeur). Trois éléments posés au-dessus du monde, sans
 * fond commun :
 * <ul>
 *   <li>à gauche, la marque : une bulle en dégradé d'accent et son « Y » ;</li>
 *   <li>au centre, une capsule de verre qui porte les onglets ({@link NavTab}),
 *       bord éclairé en haut, et sous l'onglet ouvert une pastille d'accent
 *       en dégradé qui GLISSE d'un onglet à l'autre, avec un reflet sous la
 *       capsule ;</li>
 *   <li>à droite, la recherche et les boutons, chacun dans sa bulle (posés
 *       par l'écran, pas par cette classe).</li>
 * </ul>
 * La position de la pastille est gardée d'une reconstruction à l'autre
 * (changer d'onglet refait tout l'écran) : c'est ce qui lui permet de glisser.
 */
public final class TopBar extends UiWidget {

    /** Où était la pastille : partagé par les reconstructions de l'écran. */
    private static final UiAnimatedFloat PILL_X = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    private static final UiAnimatedFloat PILL_W = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    private static long lastDrawNanos;

    private float brandX, brandY, brandSize;
    private float capX, capY, capW, capH;
    private final List<NavTab> tabs = new ArrayList<>();

    public TopBar() {
        super(0f, 0f, 0f, 0f);
    }

    /** Taille de la bulle de la marque et des boutons de droite. */
    public static float bubbleSize() {
        return px(48f);
    }

    /** Écart intérieur entre la capsule et ses onglets. */
    public static float capsulePad() {
        return px(UiTheme.Space.S5);
    }

    public void brand(TaffyLayoutResult.Rect r) {
        if (r == null) return;
        brandX = r.x;
        brandY = r.y;
        brandSize = Math.min(r.w, r.h);
    }

    public void capsule(TaffyLayoutResult.Rect r, List<NavTab> navTabs) {
        if (r == null) return;
        capX = r.x;
        capY = r.y;
        capW = r.w;
        capH = r.h;
        tabs.clear();
        tabs.addAll(navTabs);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        drawBrand(r, vw, vh);
        if (capW <= 0f || capH <= 0f) return;

        // La capsule : ombre portée, verre, bord éclairé en haut.
        float radius = capH / 2f;
        r.drawShadow(capX, capY - px(UiTheme.Space.S6), capX + capW, capY + capH - px(UiTheme.Space.S6), radius,
            28f, 0f, new UiColor(0, 0, 0, 120), vw, vh);
        UiSurface.of(UiTheme.Glass.SIDEBAR).radius(radius).noBorder().draw(r, capX, capY, capW, capH, vw, vh);
        r.drawRoundedRectBorder(capX, capY, capX + capW, capY + capH, radius, UiTheme.hairline(),
            UiTheme.ACCENT.withAlpha(0.22f), vw, vh);
        // Le haut du bord accroche la lumière, plus fort à gauche : un dégradé sur le trait.
        float line = UiTheme.hairline();
        r.drawGradientRect2D(capX + radius, capY + capH - line, capX + capW - radius, capY + capH, 0f,
            UiTheme.accentLight().withAlpha(0.55f), new UiColor(255, 255, 255, 8),
            UiTheme.accentLight().withAlpha(0.55f), new UiColor(255, 255, 255, 8), vw, vh);

        drawPill(r, vw, vh);
    }

    private void drawPill(UiRenderer r, int vw, int vh) {
        NavTab active = null;
        for (NavTab t : tabs) if (t.isActive()) active = t;
        if (active == null || active.w <= 0f) return;

        // Rouvert après un moment : la pastille est déjà sous l'onglet, elle ne traverse pas la barre.
        long now = System.nanoTime();
        boolean fresh = now - lastDrawNanos > 500_000_000L || PILL_W.target() <= 0f;
        lastDrawNanos = now;
        if (fresh) {
            PILL_X.snapTo(active.x);
            PILL_W.snapTo(active.w);
        } else {
            PILL_X.setTarget(active.x);
            PILL_W.setTarget(active.w);
        }
        float px1 = PILL_X.get(), pw = PILL_W.get();
        float py1 = active.y, ph = active.h;
        float radius = ph / 2f;

        // Lueur sous la pastille, puis le reflet qu'elle jette sous la capsule.
        r.drawGlow(px1, py1, px1 + pw, py1 + ph, radius, 0.6f, UiTheme.ACCENT.withAlpha(0.5f), vw, vh);
        float rx1 = px1 + pw * 0.2f, rx2 = px1 + pw * 0.8f;
        r.drawGlow(rx1, capY - px(UiTheme.Space.S4), rx2, capY - px(UiTheme.Space.S1), px(UiTheme.Space.S2), 0.5f,
            UiTheme.accentLight().withAlpha(0.45f), vw, vh);

        // La pastille : un dégradé en diagonale, de l'accent vers l'accent clair.
        UiColor deep = UiTheme.ACCENT, light = UiTheme.accentLight();
        UiColor mid = UiColor.lerp(deep, light, 0.5f);
        r.drawGradientRect2D(px1, py1, px1 + pw, py1 + ph, radius, deep, mid, mid, light, vw, vh);
        // Un reflet net en haut : la pastille a du volume.
        float line = UiTheme.hairline();
        r.drawRoundedRect(px1 + radius, py1 + ph - line * 1.5f, px1 + pw - radius, py1 + ph - line * 0.5f, 0f,
            new UiColor(255, 255, 255, 70), vw, vh);
    }

    private void drawBrand(UiRenderer r, int vw, int vh) {
        if (brandSize <= 0f) return;
        float s = brandSize, mx = brandX, my = brandY;
        float radius = s * 0.32f;
        r.drawGlow(mx, my, mx + s, my + s, radius, 0.5f, UiTheme.ACCENT.withAlpha(0.45f), vw, vh);
        r.drawGradientRect2D(mx, my, mx + s, my + s, radius,
            UiTheme.accentDark(), UiTheme.ACCENT, UiTheme.ACCENT, UiTheme.accentLight(), vw, vh);
        r.drawRoundedRect(mx + radius, my + s - UiTheme.hairline() * 1.5f, mx + s - radius, my + s - UiTheme.hairline() * 0.5f, 0f,
            new UiColor(255, 255, 255, 70), vw, vh);
        float letterScale = px(UiTheme.Text.HEADING);
        UiText.drawCentered(r, UiFont.BOLD, "Y", mx, s,
            UiText.centeredBaseline(UiFont.BOLD, my, s, letterScale), UiTheme.TEXT_ON_ACCENT, letterScale, vw, vh);
    }
}
