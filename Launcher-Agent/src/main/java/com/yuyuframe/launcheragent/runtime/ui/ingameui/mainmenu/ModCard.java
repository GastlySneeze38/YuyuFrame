package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.text.UiRichText;
import com.yuyuframe.launcheragent.apigraphic.text.UiTextSpan;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiIcon;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiToggle;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Une carte de la grille des modules, dans le style de l'accueil (refonte du
 * 2026-11-20) : l'icône dans un carré teinté d'accent quand le module tourne,
 * le nom, la description, le cœur des favoris en haut à droite, et en bas une
 * BANDE qui dit l'état en toutes lettres.
 *
 * <ul>
 *   <li>Module : « Activé » en vert ou « Désactivé » à gauche, l'action
 *       (« Désactiver » / « Activer ») à droite, nette au survol ; toute la
 *       bande allume ou éteint le module (c'est le widget apparié, invisible,
 *       qui prend le clic). Le reste de la carte ouvre les réglages, et le
 *       dit au survol.</li>
 *   <li>Groupe : une pastille par module et « 3 sur 5 actifs », « Ouvrir ».</li>
 *   <li>Action (Modrinth) : « Outil », « Ouvrir ».</li>
 * </ul>
 *
 * Deux agencements : Grille d'icônes (icône au-dessus du nom, une ligne de
 * description) et Détaillé / Compacte (icône à gauche, deux lignes).
 *
 * <p>La bande et le cœur sont des widgets À PART, posés par-dessus la carte
 * ({@link #pairToggle}, {@link #pairFavorite}) : la carte leur pousse à
 * chaque image son soulèvement, son glissement et son opacité, pour qu'ils
 * bougent avec elle.
 */
final class ModCard extends UiInteractive {

    /** Soulèvement au survol, en pixels réels (volontairement non mis à l'échelle). */
    private static final float HOVER_LIFT_PX = 3f;
    /** La carte a une hauteur fixe : au-delà de deux lignes, la description déborderait. */
    private static final int DESC_MAX_LINES = 2;

    private static final UiColor ON_GREEN = new UiColor(92, 210, 128, 255);

    private final GridEntry entry;
    /** 0 Détaillé, 1 Compacte, 2 Grille d'icônes — figé : une carte neuve est créée à chaque changement. */
    private final int layoutMode;

    /** Entrée en cascade (fondu + montée), son retard vient de la position dans la grille. */
    private final UiTransition enterAnim;
    /**
     * Glissement de l'ancienne place vers la nouvelle quand la grille est
     * réordonnée (technique FLIP : la carte est déjà à sa place, on réinjecte
     * l'écart et on le résorbe). Sans rebond : elle cognerait ses voisines.
     */
    private final UiTransition moveAnim = new UiTransition(UiTheme.Motion.MOVE_S, 0f, UiEasing.CUBIC_OUT);
    private float moveDx, moveDy;
    private boolean moving;

    private final UiAnimatedFloat bandHoverAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
    /** De 0 (éteint) à 1 (allumé), pour que la teinte suive la bascule en douceur. */
    private final UiAnimatedFloat onAnim;
    private final UiAsyncFade iconFade = new UiAsyncFade();

    private UiToggle pairedToggle;
    private UiToggle pairedFavorite;
    /**
     * X d'origine des widgets appariés. PIÈGE : la liste qui défile réécrit
     * {@code y} à chaque image mais jamais {@code x} — un {@code x +=} par
     * image s'accumulerait et le widget dériverait hors de l'écran.
     */
    private float pairedToggleBaseX, pairedFavoriteBaseX;

    ModCard(GridEntry entry, int layoutMode, float enterDelay) {
        this.entry = entry;
        this.layoutMode = layoutMode;
        this.enterAnim = new UiTransition(UiTheme.Motion.ENTER_S, enterDelay, UiEasing.QUART_OUT);
        this.enterAnim.show();
        this.onAnim = new UiAnimatedFloat(on() ? 1f : 0f, UiTheme.Motion.HOVER);
    }

    GridEntry entry() { return entry; }

    private boolean on() {
        return entry.hasToggle() && entry.enabled.getAsBoolean();
    }

    /** Déjà à l'écran avant la reconstruction : pas d'entrée, qu'elle bouge ou non (sinon elle seule clignoterait). */
    void markAlreadyPresent() {
        enterAnim.snapToEnd();
    }

    /** @param dx/dy ancienne position moins nouvelle, dans le repère de la grille. */
    void startMove(float dx, float dy) {
        this.moveDx = dx;
        this.moveDy = dy;
        this.moving = true;
        this.moveAnim.replay();
    }

    void pairToggle(UiToggle toggle) {
        this.pairedToggle = toggle;
        this.pairedToggleBaseX = toggle.x;
        // Son opacité vient entièrement de la carte (sa taille diffère : son propre fondu de bord ne collerait pas).
        toggle.useExternalAlphaOnly();
    }

    void pairFavorite(UiToggle favorite) {
        this.pairedFavorite = favorite;
        this.pairedFavoriteBaseX = favorite.x;
        favorite.useExternalAlphaOnly();
    }

    /** La zone des widgets appariés n'est pas à la carte : un clic dessus doit les atteindre, eux. */
    @Override
    public boolean contains(double mx, double my) {
        if (!super.contains(mx, my)) return false;
        if (pairedToggle != null && pairedToggle.contains(mx, my)) return false;
        if (pairedFavorite != null && pairedFavorite.contains(mx, my)) return false;
        return true;
    }

    /** Montée d'entrée : rapide, léger dépassement, puis se pose. */
    private static float enterRise(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float c1 = 1.2f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        updateStates(mouseX, mouseY);
        float t = moving ? 1f : Math.max(0f, Math.min(1f, enterAnim.eased()));
        // Fondu d'entrée x fondu au bord de la liste qui défile.
        float alpha = t * clipFade;

        // Reste de l'écart à résorber (1 au départ, 0 à l'arrivée) — un seul eased() par image.
        float moveRemain = 0f;
        if (moving) {
            moveRemain = 1f - moveAnim.eased();
            if (moveAnim.isFinished()) moving = false;
        }
        float mdx = moveDx * moveRemain, mdy = moveDy * moveRemain;
        float drawX = x + mdx;
        float rise = moving ? 1f : enterRise(enterAnim.progress());
        // La bande survolée soulève aussi la carte : c'est la même carte.
        boolean bandOver = bandContains(mouseX, mouseY);
        float lift = Math.max(hover, bandOver ? 1f : 0f) * HOVER_LIFT_PX;
        float drawY = y + mdy - (1f - rise) * px(34f) + lift;

        // Les widgets appariés suivent : X en absolu depuis leur base, Y en
        // relatif (la liste vient de le poser pour cette image).
        if (pairedToggle != null) {
            pairedToggle.x = pairedToggleBaseX + mdx;
            pairedToggle.y += mdy + lift;
            pairedToggle.setExternalAlpha(alpha);
        }
        if (pairedFavorite != null) {
            pairedFavorite.x = pairedFavoriteBaseX + mdx;
            pairedFavorite.y += mdy + lift;
            pairedFavorite.setExternalAlpha(alpha);
        }

        onAnim.setTarget(on() ? 1f : 0f);
        float k = onAnim.get();
        bandHoverAnim.setTarget(bandOver ? 1f : 0f);
        float bandHover = bandHoverAnim.get();
        // Survol du corps de la carte seulement : la bande a le sien.
        float bodyHover = hover * (1f - bandHover);
        float radius = px(UiTheme.Space.S18);

        // ── Le fond : verre, teinte d'accent quand le module tourne ─────────
        UiColor border = UiColor.lerp(UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.GLASS_BORDER_HOVER, hover),
            UiTheme.accentLight().withAlpha(0.45f), k);
        UiSurface.card(hover).radius(radius).border(border).alpha(alpha).draw(renderer, drawX, drawY, w, h, vpWidth, vpHeight);
        if (k > 0.01f) {
            renderer.drawGradientRect(drawX, drawY, drawX + w, drawY + h, radius,
                UiTheme.ACCENT.withAlpha(0f), UiTheme.ACCENT.withAlpha(0.24f * k * alpha), vpWidth, vpHeight);
        }
        UiSurface.hoverWash(renderer, drawX, drawY, w, h, radius, bodyHover * alpha, vpWidth, vpHeight);

        // Éclat de clic (ou de favori basculé) : s'étend sous la carte et s'efface, avec un rebond.
        float pulse = pulse();
        if (pulse >= 0f) {
            float spread = px(UiTheme.Space.S3) * pulse;
            float glow = Math.max(0f, 1f - pulse);
            renderer.drawRoundedRect(drawX - spread, drawY - spread, drawX + w + spread, drawY + h + spread,
                radius + spread, UiTheme.ACCENT.withAlpha(0.30f * glow * alpha), vpWidth, vpHeight);
        }

        drawBand(renderer, drawX, drawY, radius, k, bandHover, alpha, vpWidth, vpHeight);

        // Traduit au dernier moment : un changement de langue se voit tout de suite.
        String name = Lang.tr(entry.name);
        // En taille « Grande », une phrase courte dédiée plutôt qu'une troncature.
        boolean largeMode = UiTheme.userScale() >= 1.4f;
        String descriptionSource = (largeMode && entry.shortDescription != null) ? entry.shortDescription : entry.description;
        String description = Lang.tr(descriptionSource);
        String initial = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        drawBody(renderer, name, description, initial, drawX, drawY, k, alpha, vpWidth, vpHeight);

        // « Réglages » au survol du corps, sous le cœur : ce que fait le clic sur la carte.
        if (entry.opensSettings && bodyHover > 0.01f) {
            float s = px(UiTheme.Text.XS);
            String text = Lang.tr("Réglages") + "  >";
            float cw = UiText.width(UiFont.BOLD, text, s) + px(UiTheme.Space.S16);
            float ch = px(UiTheme.Space.S24);
            float cx = drawX + w - ModGrid.heartInset() - cw;
            float cy = drawY + h - ModGrid.heartInset() - ModGrid.heartSize() - px(UiTheme.Space.S10) - ch;
            float a = bodyHover * alpha;
            renderer.drawRoundedRect(cx, cy, cx + cw, cy + ch, px(UiTheme.Space.S8), UiTheme.ACCENT.withAlpha(0.30f * a),
                vpWidth, vpHeight);
            renderer.drawText(UiFont.BOLD, text, cx + px(UiTheme.Space.S8), UiText.centeredBaseline(UiFont.BOLD, cy, ch, s),
                UiTheme.accentLight().multiplyAlpha(a), s, vpWidth, vpHeight);
        }
    }

    /** La bande du bas, à la position dessinée de la carte (avant le soulèvement de cette image). */
    private boolean bandContains(double mx, double my) {
        if (pairedToggle != null) return pairedToggle.contains(mx, my);
        float band = ModGrid.bandHeight();
        return super.contains(mx, my) && my >= y && my <= y + band;
    }

    // ── La bande ─────────────────────────────────────────────────────────────

    private void drawBand(UiRenderer r, float drawX, float drawY, float radius, float k, float bandHover, float alpha,
                          int vw, int vh) {
        float band = ModGrid.bandHeight();
        UiColor off = UiColor.lerp(new UiColor(0, 0, 0, 56), new UiColor(255, 255, 255, 20), bandHover);
        UiColor onFill = UiTheme.ACCENT.withAlpha(0.34f + 0.16f * bandHover);
        UiColor fill = entry.hasToggle() ? UiColor.lerp(off, onFill, k) : off;
        // Seuls les deux coins bas sont arrondis, alignés sur ceux de la carte.
        r.drawRoundedRect(drawX, drawY, drawX + w, drawY + band, radius, radius, 0f, 0f, fill.multiplyAlpha(alpha), vw, vh);
        r.drawRoundedRect(drawX, drawY + band - UiTheme.hairline(), drawX + w, drawY + band, 0f,
            new UiColor(255, 255, 255, 20).multiplyAlpha(alpha), vw, vh);

        float pad = px(UiTheme.Space.S16);
        float s = px(UiTheme.Text.SM);
        float baseline = UiText.centeredBaseline(UiFont.BOLD, drawY, band, s);
        float mid = drawY + band / 2f;

        if (entry.hasToggle()) {
            boolean on = on();
            // L'état, en toutes lettres, avec sa pastille.
            float dot = px(UiTheme.Space.S8);
            UiColor stateColor = UiColor.lerp(UiTheme.TEXT_MUTED, ON_GREEN, k);
            if (k > 0.01f) {
                r.drawGlow(drawX + pad, mid - dot / 2f, drawX + pad + dot, mid + dot / 2f, dot, 0.6f * k,
                    ON_GREEN.multiplyAlpha(alpha), vw, vh);
            }
            r.drawRoundedRect(drawX + pad, mid - dot / 2f, drawX + pad + dot, mid + dot / 2f, dot / 2f,
                UiColor.lerp(new UiColor(255, 255, 255, 70), ON_GREEN, k).multiplyAlpha(alpha), vw, vh);
            float tx = drawX + pad + dot + px(UiTheme.Space.S10);
            r.drawText(UiFont.BOLD, Lang.tr(on ? "Activé" : "Désactivé"), tx, baseline, stateColor.multiplyAlpha(alpha), s, vw, vh);

            // À droite, ce que fait le clic, écrit : discret, net au survol de la bande.
            // (Le symbole marche / arrêt d'icons8 se lisait comme une horloge à cette taille.)
            String action = Lang.tr(on ? "Désactiver" : "Activer");
            float as = px(UiTheme.Text.XS);
            float aw = UiText.width(UiFont.BOLD, action, as);
            UiColor actionColor = UiColor.lerp(UiTheme.TEXT_MUTED.multiplyAlpha(0.8f), UiTheme.TEXT_PRIMARY, bandHover);
            r.drawText(UiFont.BOLD, action, drawX + w - pad - aw, UiText.centeredBaseline(UiFont.BOLD, drawY, band, as),
                actionColor.multiplyAlpha(alpha), as, vw, vh);
            return;
        }

        float tx = drawX + pad;
        if (entry.isGroup()) {
            boolean[] members = entry.members.get();
            int active = 0;
            float dot = px(7f), gap = px(UiTheme.Space.S4);
            for (boolean m : members) {
                r.drawRoundedRect(tx, mid - dot / 2f, tx + dot, mid + dot / 2f, dot / 2f,
                    (m ? ON_GREEN : new UiColor(255, 255, 255, 52)).multiplyAlpha(alpha), vw, vh);
                tx += dot + gap;
                if (m) active++;
            }
            tx += px(UiTheme.Space.S6);
            String count = active + " " + Lang.tr("sur") + " " + members.length + " " + Lang.tr(active > 1 ? "actifs" : "actif");
            r.drawText(UiFont.BOLD, count, tx, baseline, (active > 0 ? ON_GREEN : UiTheme.TEXT_MUTED).multiplyAlpha(alpha), s, vw, vh);
        } else {
            r.drawText(UiFont.BOLD, Lang.tr("Outil"), tx, baseline, UiTheme.TEXT_MUTED.multiplyAlpha(alpha), s, vw, vh);
        }
        String open = Lang.tr("Ouvrir") + "  >";
        float ow = UiText.width(UiFont.BOLD, open, s);
        r.drawText(UiFont.BOLD, open, drawX + w - pad - ow, baseline,
            UiColor.lerp(UiTheme.accentLight(), UiTheme.TEXT_PRIMARY, hover * 0.6f).multiplyAlpha(alpha), s, vw, vh);
    }

    // ── Le corps ─────────────────────────────────────────────────────────────

    private void drawBody(UiRenderer r, String name, String description, String initial, float drawX, float drawY,
                          float k, float alpha, int vw, int vh) {
        float pad = px(UiTheme.Space.S18);
        float top = drawY + h - pad;
        float box = px(48f);
        float heartReserve = ModGrid.heartSize() + px(UiTheme.Space.S10);
        UiColor boxFill = UiColor.lerp(new UiColor(255, 255, 255, 16), UiTheme.ACCENT.withAlpha(0.62f), k);

        if (layoutMode == 2) {
            // Grille d'icônes : l'icône au-dessus, puis le nom et une ligne de description.
            this.tooltip = description;
            float bx = drawX + pad, by = top - box;
            drawIconBox(r, bx, by, box, boxFill, initial, k, alpha, vw, vh);
            float maxW = w - 2f * pad;
            float nameScale = px(UiTheme.Text.LG);
            float nameBase = by - px(UiTheme.Space.S14) - px(UiTheme.Space.S12);
            r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, name, maxW, nameScale), drawX + pad, nameBase,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), nameScale, vw, vh);
            float ds = px(UiTheme.Text.XS);
            r.drawText(UiFont.REGULAR, UiText.fit(UiFont.REGULAR, description, maxW, ds), drawX + pad,
                nameBase - px(UiTheme.Space.S20), UiTheme.TEXT_MUTED.multiplyAlpha(alpha), ds, vw, vh);
            return;
        }

        // Détaillé et Compacte : l'icône à gauche, le nom et deux lignes de description à droite.
        this.tooltip = null;
        float bx = drawX + pad, by = top - box;
        drawIconBox(r, bx, by, box, boxFill, initial, k, alpha, vw, vh);
        float textX = bx + box + px(UiTheme.Space.S16);
        float nameMaxW = drawX + w - pad - heartReserve - textX;
        float nameScale = px(UiTheme.Text.LG);
        r.drawText(UiFont.BOLD, UiText.fit(UiFont.BOLD, name, nameMaxW, nameScale), textX, top - px(UiTheme.Space.S18),
            UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), nameScale, vw, vh);
        // Coupée aux mots sur deux lignes plutôt que tronquée : une description coupée en plein mot n'apprend rien.
        UiRichText.Layout layout = descriptionLayout(description, drawX + w - pad - textX, px(UiTheme.Text.SMALL));
        if (layout != null) {
            UiRichText.draw(r, layout, textX, top - px(30f), alpha, vw, vh);
        }
    }

    private void drawIconBox(UiRenderer r, float bx, float by, float box, UiColor fill, String initial, float k, float alpha,
                             int vw, int vh) {
        if (k > 0.01f) {
            r.drawGlow(bx, by, bx + box, by + box, box * 0.4f, 0.45f * k, UiTheme.ACCENT.multiplyAlpha(alpha), vw, vh);
        }
        r.drawRoundedRect(bx, by, bx + box, by + box, px(UiTheme.Space.S14), fill.multiplyAlpha(alpha), vw, vh);
        float iconS = px(26f);
        // Un module éteint a son icône grisée : la carte se lit d'un coup d'œil.
        float iconAlpha = entry.hasToggle() ? 0.5f + 0.5f * k : 0.9f;
        drawIconOrInitial(r, bx + (box - iconS) / 2f, by + (box - iconS) / 2f, iconS, initial, alpha * iconAlpha, vw, vh);
    }

    /** Icône distante une fois chargée (fondu), sinon pastille-lettre ; le réticule est dessiné en local. */
    private void drawIconOrInitial(UiRenderer renderer, float ix, float iy, float size, String initial,
                                   float alpha, int vpWidth, int vpHeight) {
        String url = entry.iconUrl;
        if (LauncherModule.ICON_LOCAL_CROSSHAIR.equals(url)) {
            int px = Math.max(8, Math.round(size));
            renderer.drawIcon(LauncherModule.ICON_LOCAL_CROSSHAIR, crosshairImage(px), ix, iy, size, size,
                alpha, vpWidth, vpHeight);
            return;
        }
        BufferedImage icon = url != null ? UiRemoteImage.get(url) : null;
        if (icon != null) {
            iconFade.markReady();
            UiIcon.crisp(renderer, url, ix, iy, size, iconFade.alpha() * alpha, vpWidth, vpHeight);
            return;
        }
        float scale = px(UiTheme.Text.VALUE);
        float iw = renderer.textWidth(initial, scale);
        renderer.drawText(initial, ix + (size - iw) / 2f, iy + size / 2f - px(UiTheme.Space.S4),
            UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), scale, vpWidth, vpHeight);
    }

    // Mise en page de la description, gardée tant que le texte et la largeur
    // ne changent pas (la carte est redessinée à chaque image). La couleur
    // n'est pas dans la clé : le fondu passe par l'alpha du dessin.
    private UiRichText.Layout cachedDescLayout;
    private String cachedDescText;
    private float cachedDescWidth = -1f, cachedDescScale = -1f;

    private UiRichText.Layout descriptionLayout(String text, float maxWidth, float scale) {
        if (text == null || text.isEmpty()) return null;
        if (cachedDescLayout != null && text.equals(cachedDescText) && maxWidth == cachedDescWidth && scale == cachedDescScale) {
            return cachedDescLayout;
        }
        cachedDescText = text;
        cachedDescWidth = maxWidth;
        cachedDescScale = scale;
        cachedDescLayout = UiRichText.layout(
            Collections.singletonList(UiTextSpan.plain(text, UiTheme.TEXT_MUTED)), maxWidth, scale, DESC_MAX_LINES);
        return cachedDescLayout;
    }

    @Override
    protected void onActivate() {
        if (entry.open != null) entry.open.run();
    }

    // ── Réticule dessiné en local ───────────────────────────────────────────
    //
    // Aucune icône « réticule » n'existe dans le style icons8 employé partout
    // (toutes essayées, 404) : quatre branches et un point, dessinés une fois
    // par taille.

    private static final Map<Integer, BufferedImage> CROSSHAIR_CACHE = new HashMap<>();

    private static BufferedImage crosshairImage(int px) {
        BufferedImage cached = CROSSHAIR_CACHE.get(px);
        if (cached != null) return cached;
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(java.awt.Color.WHITE);
            float c = px / 2f;
            float thick = px * 0.09f, gap = px * 0.14f, arm = px * 0.28f;
            g.fill(new RoundRectangle2D.Float(c - thick / 2f, c - gap - arm, thick, arm, thick, thick));
            g.fill(new RoundRectangle2D.Float(c - thick / 2f, c + gap, thick, arm, thick, thick));
            g.fill(new RoundRectangle2D.Float(c - gap - arm, c - thick / 2f, arm, thick, thick, thick));
            g.fill(new RoundRectangle2D.Float(c + gap, c - thick / 2f, arm, thick, thick, thick));
            float dot = px * 0.035f;
            g.fill(new Ellipse2D.Float(c - dot, c - dot, dot * 2f, dot * 2f));
        } finally {
            g.dispose();
        }
        CROSSHAIR_CACHE.put(px, img);
        return img;
    }
}
