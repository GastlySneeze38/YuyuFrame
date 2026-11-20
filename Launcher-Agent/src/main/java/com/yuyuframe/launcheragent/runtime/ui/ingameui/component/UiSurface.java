package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme.Glass;

/**
 * Une surface de verre : ombre portée, verre teinté (ou son aplat quand le
 * flou n'est pas disponible), contour et liseré de lumière. Toutes les cartes,
 * panneaux, barres et boutons de l'interface passent par ici : changer le
 * rendu du verre, c'est changer CE fichier.
 *
 * <pre>
 * UiSurface.card(hover).alpha(fade).draw(renderer, x, y, w, h, vw, vh);
 * </pre>
 *
 * <p>Le contour n'est posé que sur du vrai verre : un aplat opaque se détache
 * déjà du fond par sa couleur, alors qu'une surface de verre montre le même
 * décor que ce qui l'entoure, juste flouté.
 */
public final class UiSurface {
    private UiSurface() {}

    /** Une surface d'une sorte donnée, à régler puis dessiner. */
    public static Spec of(Glass kind) { return new Spec(kind); }

    /**
     * Carte (grille de modules) : ombre qui s'étoffe au survol, verre qui se
     * détend (moins de teinte, plus de décor), contour qui s'éclaire.
     */
    public static Spec card(float hover) {
        return of(Glass.CARD)
            .strength(Glass.CARD.strength - hover * 0.1f)
            .fill(UiColor.lerp(UiTheme.CARD_BG, UiTheme.CARD_HOVER, hover))
            .border(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover))
            .shadow(UiTheme.SHADOW_CARD, UiTheme.px(18f) + hover * UiTheme.px(6f), UiTheme.px(3f), UiTheme.px(6f))
            .hairlineTop();
    }

    /** Panneau de section des écrans de réglages. */
    public static Spec panel() {
        return of(Glass.PANEL)
            .shadow(UiTheme.SHADOW_SOFT, 8f, 0f, 0f)
            .hairlineTop();
    }

    /** Petite surface (bouton, champ) : verre léger, aplat de repli donné. */
    /**
     * Le survol d'une surface cliquable : un voile d'accent posé par-dessus
     * le verre. Le verre seul change trop peu d'une teinte à l'autre pour se
     * voir sur un monde sombre ; ce voile est le même partout, pour qu'un
     * survol se reconnaisse d'un composant à l'autre.
     */
    public static void hoverWash(UiRenderer r, float x, float y, float w, float h, float radius, float hover, int vw, int vh) {
        if (hover <= 0.01f) return;
        r.drawRoundedRect(x, y, x + w, y + h, radius, UiTheme.ACCENT.withAlpha(0.16f * hover), vw, vh);
        // Un reflet en haut, comme une surface qui s'approche de la lumière.
        float line = UiTheme.hairline();
        r.drawRoundedRect(x + radius, y + h - line, x + w - radius, y + h, 0f,
            UiTheme.accentLight().withAlpha(0.55f * hover), vw, vh);
    }

    public static Spec field(UiColor fill) {
        return of(Glass.FIELD).radius(UiTheme.RADIUS_SM).fill(fill);
    }

    public static final class Spec {
        private final Glass kind;
        private float radius = UiTheme.RADIUS_MD;
        private float strength;
        private UiColor fill;
        private UiColor border = UiTheme.GLASS_BORDER;
        private boolean hairlineTop, hairlineRight;
        private UiColor shadow;
        private float shadowBlur, shadowSpread, shadowDrop;
        private float alpha = 1f;

        private Spec(Glass kind) {
            this.kind = kind;
            this.strength = kind.strength;
        }

        public Spec radius(float r) { this.radius = r; return this; }
        /** Force de la teinte, 0 = flou pur, 1 = aplat. */
        public Spec strength(float s) { this.strength = s; return this; }
        /** Aplat de repli (et couleur de base du verre). Par défaut, celui de la sorte de surface. */
        public Spec fill(UiColor c) { this.fill = c; return this; }
        /** Contour, {@code null} = aucun. */
        public Spec border(UiColor c) { this.border = c; return this; }
        public Spec noBorder() { this.border = null; return this; }
        /** Liseré de lumière sur la tranche haute (verre épais). */
        public Spec hairlineTop() { this.hairlineTop = true; return this; }
        /** Liseré sur la tranche droite — pour une bande qui touche le haut et le bas de l'écran (barre latérale). */
        public Spec hairlineRight() { this.hairlineRight = true; return this; }
        /** Ombre portée ; {@code drop} la décale vers le bas (carte qui flotte). */
        public Spec shadow(UiColor color, float blur, float spread, float drop) {
            this.shadow = color;
            this.shadowBlur = blur;
            this.shadowSpread = spread;
            this.shadowDrop = drop;
            return this;
        }
        /** Opacité de toute la surface (fondu d'entrée, bord d'une zone qui défile). */
        public Spec alpha(float a) { this.alpha = a; return this; }

        public void draw(UiRenderer r, float x, float y, float w, float h, int vw, int vh) {
            if (w <= 0f || h <= 0f || alpha <= 0f) return;
            if (shadow != null) {
                r.drawShadow(x, y - shadowDrop, x + w, y + h - shadowDrop, radius, shadowBlur, shadowSpread,
                    shadow.multiplyAlpha(alpha), vw, vh);
            }
            boolean glass = r.isGlassAvailable();
            UiColor base = (fill != null ? fill : kind.fallback()).multiplyAlpha(alpha);
            float line = UiTheme.hairline();
            r.drawGlassPanel(x, y, x + w, y + h, radius, UiTheme.GLASS_TINT, strength, base,
                glass && border != null ? border.multiplyAlpha(alpha) : null, line, vw, vh);
            if (!glass) return;
            UiColor light = UiTheme.GLASS_HAIRLINE.multiplyAlpha(alpha);
            if (hairlineTop) {
                // Retiré aux extrémités : il ne doit pas déborder des coins arrondis.
                r.drawRoundedRect(x + radius, y + h - line, x + w - radius, y + h, 0f, light, vw, vh);
            }
            if (hairlineRight) {
                r.drawRoundedRect(x + w - line, y, x + w, y + h, 0f, light, vw, vh);
            }
        }
    }
}
