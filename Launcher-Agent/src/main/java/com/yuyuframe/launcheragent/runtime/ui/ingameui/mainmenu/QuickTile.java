package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Une tuile de l'accès rapide : l'icône du module, son nom, son état écrit
 * (« Activé » en vert) et un interrupteur. Tout se lit sans survoler.
 *
 * <ul>
 *   <li>clic sur la tuile : allume ou éteint le module ;</li>
 *   <li>au survol, une roue dentée apparaît avant l'interrupteur : un clic
 *       dessus ouvre les réglages du module ;</li>
 *   <li>un groupe ou une action (Modrinth) n'a pas d'état : la tuile l'ouvre.</li>
 * </ul>
 */
public final class QuickTile extends UiInteractive {

    private static final String GEAR = LauncherModule.icons8("settings");
    private static final String CHEVRON = LauncherModule.icons8("forward");

    private final GridEntry entry;
    private final UiAnimatedFloat knob;
    private double lastMouseX;

    public QuickTile(GridEntry entry) {
        this.entry = entry;
        this.knob = new UiAnimatedFloat(entry.hasToggle() && entry.enabled.getAsBoolean() ? 1f : 0f, UiTheme.Motion.HOVER);
    }

    private boolean on() {
        return entry.hasToggle() && entry.enabled.getAsBoolean();
    }

    /** Hauteur d'une tuile : assez grande pour se viser sans chercher. */
    public static float height() { return px(84f); }

    private float switchW() { return px(46f); }

    private float gearRight() {
        return x + w - px(UiTheme.Space.S16) - (entry.hasToggle() ? switchW() + px(UiTheme.Space.S12) : 0f);
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        updateStates(mouseX, mouseY);
        lastMouseX = mouseX;
        boolean on = on();
        knob.setTarget(on ? 1f : 0f);
        float k = knob.get();

        UiSurface.card(hover).radius(px(UiTheme.Space.S18)).draw(r, x, y, w, h, vw, vh);
        UiSurface.hoverWash(r, x, y, w, h, px(UiTheme.Space.S18), hover, vw, vh);

        // L'icône, dans un carré teinté d'accent quand le module tourne.
        float box = px(54f);
        float bx = x + px(UiTheme.Space.S14), by = y + (h - box) / 2f;
        UiColor boxFill = UiColor.lerp(new UiColor(255, 255, 255, 14), UiTheme.ACCENT.withAlpha(0.36f), k);
        r.drawRoundedRect(bx, by, bx + box, by + box, px(UiTheme.Space.S14), boxFill, vw, vh);
        float iconS = px(30f);
        if (entry.iconUrl != null) {
            UiIcon.crisp(r, entry.iconUrl, bx + (box - iconS) / 2f, by + (box - iconS) / 2f, iconS,
                entry.hasToggle() ? 0.45f + 0.55f * k : 0.9f, vw, vh);
        }

        // Interrupteur ou chevron, à droite.
        float right = x + w - px(UiTheme.Space.S16);
        if (entry.hasToggle()) {
            float sw = switchW(), sh = px(26f);
            float sx = right - sw, sy = y + (h - sh) / 2f;
            r.drawRoundedRect(sx, sy, sx + sw, sy + sh, sh / 2f,
                UiColor.lerp(new UiColor(255, 255, 255, 36), UiTheme.accentLight(), k), vw, vh);
            float d = sh - px(UiTheme.Space.S6);
            float kx = sx + px(UiTheme.Space.S3) + (sw - d - px(UiTheme.Space.S6)) * k;
            r.drawRoundedRect(kx, sy + px(UiTheme.Space.S3), kx + d, sy + px(UiTheme.Space.S3) + d, d / 2f,
                new UiColor(255, 255, 255, 255), vw, vh);
        } else {
            float cs = px(UiTheme.Space.S16);
            UiIcon.crisp(r, CHEVRON, right - cs, y + (h - cs) / 2f, cs, 0.4f + 0.5f * hover, vw, vh);
        }

        float gear = px(UiTheme.Space.S18);
        boolean showGear = entry.hasToggle() && hover > 0.01f;
        if (showGear) {
            float gx = gearRight() - gear;
            boolean overGear = mouseX >= gx - px(UiTheme.Space.S4);
            UiIcon.crisp(r, GEAR, gx, y + (h - gear) / 2f, gear, hover * (overGear ? 1f : 0.55f), vw, vh);
        }

        // Nom et état.
        float tx = bx + box + px(UiTheme.Space.S16);
        float maxW = (showGear ? gearRight() - gear : right - (entry.hasToggle() ? switchW() : px(UiTheme.Space.S16)))
            - px(UiTheme.Space.S10) - tx;
        float nameScale = px(UiTheme.Text.LG);
        float mid = y + h / 2f;
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, Lang.tr(entry.name), maxW, nameScale), tx, mid + px(UiTheme.Space.S3),
            UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
        String state = !entry.hasToggle() ? Lang.tr("Ouvrir") : on ? Lang.tr("Activé") : Lang.tr("Désactivé");
        UiColor stateColor = entry.hasToggle() && on ? UiTheme.SUCCESS : UiTheme.TEXT_MUTED;
        float stateScale = px(UiTheme.Text.SM);
        r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, state, maxW, stateScale), tx, mid - px(UiTheme.Space.S18),
            stateColor, stateScale, vw, vh);
    }

    @Override
    protected void onActivate() {
        if (!entry.hasToggle()) {
            if (entry.open != null) entry.open.run();
            return;
        }
        float gx = gearRight() - px(UiTheme.Space.S18) - px(UiTheme.Space.S4);
        if (lastMouseX >= gx && lastMouseX <= gearRight() + px(UiTheme.Space.S4)) {
            if (entry.open != null) entry.open.run();
            return;
        }
        entry.setEnabled.accept(!entry.enabled.getAsBoolean());
    }
}
