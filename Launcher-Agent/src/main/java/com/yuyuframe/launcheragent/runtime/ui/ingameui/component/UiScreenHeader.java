package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;

import java.util.List;

/**
 * La barre de titre COMMUNE à tous les écrans (sauf le menu, qui est la
 * racine) : retour à gauche, titre, gestes propres à l'écran, croix à droite.
 * Partout au même endroit, à la même taille — la main cherche le retour au
 * même endroit d'un écran à l'autre.
 *
 * <pre>
 * UiScreenHeader header = new UiScreenHeader(title, () -> closeTo(parent), () -> closeTo(null));
 * root.add(header.layout());
 * header.addTo(widgets);
 * </pre>
 *
 * <p>Le titre est posé sur le décor : ombre portée, lisible sur un ciel clair.
 */
public final class UiScreenHeader extends UiWidget {

    public static final String ICON_BACK = UiIcon.named("back");
    public static final String ICON_INFO = UiIcon.named("info");

    private final String title;
    private final UiIconButton back;
    private final UiIconButton close;
    /** Boutons propres à l'écran, entre le titre et la croix (l'aide « i » du score PvP). */
    private final UiWidget[] extras;

    /**
     * @param title  déjà traduit
     * @param onBack {@code null} = pas de retour
     * @param onClose ferme tout et rend la main au jeu ; {@code null} = pas de croix
     */
    public UiScreenHeader(String title, Runnable onBack, Runnable onClose, UiWidget... extras) {
        super(0f, 0f, 0f, 0f);
        this.title = title;
        this.back = onBack == null ? null : new UiIconButton(ICON_BACK, onBack);
        this.close = onClose == null ? null : new UiCloseButton(onClose);
        this.extras = extras;
    }

    /** Hauteur de la barre (et côté des boutons). */
    public static float height() { return UiTheme.px(UiTheme.Space.S40); }

    /** La barre dans l'arbre de l'écran : une rangée pleine largeur. */
    public Box layout() {
        float h = height();
        Box row = UiLayout.row().height(h).rigid().gap(UiTheme.px(UiTheme.Space.S12)).alignItems("center");
        if (back != null) row.add(UiLayout.fixed(h, h).bind(back));
        row.add(UiLayout.box().grow(1f).height(h).bind(this));
        for (UiWidget extra : extras) row.add(UiLayout.fixed(h, h).bind(extra));
        if (close != null) row.add(UiLayout.fixed(h, h).bind(close));
        return row;
    }

    /** Ajoute la barre et ses boutons aux widgets de l'écran. */
    public void addTo(List<UiWidget> widgets) {
        widgets.add(this);
        if (back != null) widgets.add(back);
        for (UiWidget extra : extras) widgets.add(extra);
        if (close != null) widgets.add(close);
    }

    @Override
    public boolean contains(double mx, double my) {
        return false;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (w <= 0f || title == null) return;
        float scale = UiTheme.px(UiTheme.Text.TITLE);
        String shown = UiText.fit(UiFont.BOLD, title, w, scale);
        UiText.drawOnBackdrop(renderer, UiFont.BOLD, shown, x, UiText.centeredBaseline(UiFont.BOLD, y, h, scale),
            UiTheme.TEXT_PRIMARY, scale, vpWidth, vpHeight);
    }
}
