package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.MinecraftFont;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;

import java.awt.image.BufferedImage;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * L'en-tête de l'accueil : la tête du joueur, « Bon retour » dans la police
 * du jeu, son pseudo en grand, et une ligne d'état sous l'ensemble.
 *
 * <p>Posé sur le décor, sans panneau : le texte porte une ombre.
 */
public final class HeroBlock extends UiWidget {

    private final String subtitle;

    public HeroBlock(String subtitle) {
        super(0f, 0f, 0f, 0f);
        this.subtitle = subtitle;
    }

    /** Hauteur voulue : la tête, l'écart et la ligne d'état. */
    public static float preferredHeight() {
        return px(88f) + px(UiTheme.Space.S24) + UiText.lineHeight(UiFont.REGULAR, px(UiTheme.Text.LG));
    }

    @Override
    public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (w <= 0f || h <= 0f) return;
        float head = px(88f);
        float top = y + h;
        float hy = top - head;

        // Ombre de la tête, puis la tête : celle du compte, sinon celle de Steve.
        r.drawRoundedRect(x - px(UiTheme.Space.S3), hy - px(UiTheme.Space.S6), x + head + px(UiTheme.Space.S3),
            top - px(UiTheme.Space.S2), px(UiTheme.Space.S8), UiTheme.SHADOW_CARD, vw, vh);
        String name = ClientData.username();
        BufferedImage face = null;
        String key = "hero-face";
        if (name != null && !name.isEmpty()) {
            key = "https://mc-heads.net/avatar/" + name + "/128";
            face = UiRemoteImage.get(key);
        }
        if (face == null) {
            face = McTexture.defaultFace();
            key = "hero-face-default";
        }
        if (face != null) r.drawIcon(key, face, x, hy, head, head, vw, vh);

        float tx = x + head + px(UiTheme.Space.S24);
        UiFont pixel = MinecraftFont.accent(false);
        float eyebrowScale = px(UiTheme.Text.LG);
        UiText.drawOnBackdrop(r, pixel, Lang.tr("Bon retour"), tx, top - px(UiTheme.Space.S24),
            UiTheme.accentLight(), eyebrowScale, vw, vh);
        float nameScale = px(1.15f);
        String shown = name == null || name.isEmpty() ? Lang.tr("Joueur") : name;
        UiText.drawOnBackdrop(r, UiFont.BOLD, UiText.fit(UiFont.BOLD, shown, x + w - tx, nameScale), tx,
            hy + px(UiTheme.Space.S10), UiTheme.TEXT_PRIMARY, nameScale, vw, vh);

        if (subtitle != null && !subtitle.isEmpty()) {
            float s = px(UiTheme.Text.LG);
            UiText.drawOnBackdrop(r, UiFont.REGULAR, UiText.fit(UiFont.REGULAR, subtitle, w, s), x, y + px(UiTheme.Space.S4),
                UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, 0.35f), s, vw, vh);
        }
    }
}
