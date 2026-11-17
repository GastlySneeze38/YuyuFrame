package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.anim.UiBreathe;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.text.UiRichText;
import com.yuyuframe.launcheragent.apigraphic.text.UiTextSpan;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiInteractive;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiSurface;
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

/**
 * Une carte de la grille des modules, dans l'un des trois agencements :
 * Détaillé et Compacte (icône, nom, deux lignes de description — Compacte ne
 * change que la grille, pas la carte) ou Grille d'icônes (grande icône, bande
 * en bas qui active le module, cœur des favoris).
 *
 * <p>L'interrupteur et le cœur sont des widgets À PART, posés par-dessus la
 * carte ({@link #pairToggle}, {@link #pairFavorite}) : la carte leur pousse
 * à chaque image son soulèvement, son glissement et son opacité, pour qu'ils
 * bougent avec elle.
 */
final class ModCard extends UiInteractive {

    /** Soulèvement au survol, en pixels réels (volontairement non mis à l'échelle). */
    private static final float HOVER_LIFT_PX = 4f;
    /** Bande d'activation qui respire quand le module est actif : lent (sous 1,5 s ça clignote) et discret. */
    private static final float BAR_BREATH_PERIOD_S = 3.2f;
    private static final float BAR_BREATH_AMPLITUDE = 0.34f;
    /** La carte a une hauteur fixe : au-delà de deux lignes, la description déborderait. */
    private static final int DESC_MAX_LINES = 2;

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

    private final UiAnimatedFloat barHoverAnim = new UiAnimatedFloat(0f, UiTheme.Motion.HOVER);
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
    }

    GridEntry entry() { return entry; }

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
        float drawY = y + mdy - (1f - rise) * UiTheme.px(34f) + hover * HOVER_LIFT_PX;

        // Les widgets appariés suivent : X en absolu depuis leur base, Y en
        // relatif (la liste vient de le poser pour cette image).
        if (pairedToggle != null) {
            pairedToggle.x = pairedToggleBaseX + mdx;
            pairedToggle.y += mdy + hover * HOVER_LIFT_PX;
            pairedToggle.setExternalAlpha(alpha);
        }
        if (pairedFavorite != null) {
            pairedFavorite.x = pairedFavoriteBaseX + mdx;
            pairedFavorite.y += mdy + hover * HOVER_LIFT_PX;
            pairedFavorite.setExternalAlpha(alpha);
        }

        UiSurface.card(hover).alpha(alpha).draw(renderer, drawX, drawY, w, h, vpWidth, vpHeight);

        // Éclat de clic (ou de favori basculé) : s'étend sous la carte et s'efface, avec un rebond.
        float pulse = pulse();
        if (pulse >= 0f) {
            float spread = UiTheme.px(UiTheme.Space.S3) * pulse;
            float glow = Math.max(0f, 1f - pulse);
            renderer.drawRoundedRect(drawX - spread, drawY - spread, drawX + w + spread, drawY + h + spread,
                UiTheme.RADIUS_MD + spread, UiTheme.ACCENT.withAlpha(0.30f * glow * alpha), vpWidth, vpHeight);
        }

        // Traduit au dernier moment : un changement de langue se voit tout de suite.
        String displayName = Lang.tr(entry.name);
        // En taille « Grande », une phrase courte dédiée plutôt qu'une troncature.
        boolean largeMode = UiTheme.userScale() >= 1.4f;
        String descriptionSource = (largeMode && entry.shortDescription != null) ? entry.shortDescription : entry.description;
        String displayDescription = Lang.tr(descriptionSource);
        String initial = displayName.isEmpty() ? "?" : displayName.substring(0, 1).toUpperCase(Locale.ROOT);

        if (layoutMode == 2) {
            drawIconGrid(renderer, displayName, displayDescription, initial, drawX, drawY, alpha, mouseX, mouseY, vpWidth, vpHeight);
        } else {
            drawDetailed(renderer, displayName, displayDescription, initial, drawX, drawY, alpha, vpWidth, vpHeight);
        }
    }

    /** Icône distante une fois chargée (fondu), sinon pastille-lettre ; le réticule est dessiné en local. */
    private void drawIconOrInitial(UiRenderer renderer, float ix, float iy, float size, String initial,
                                   float alpha, int vpWidth, int vpHeight) {
        String url = entry.iconUrl;
        if (LauncherModule.ICON_LOCAL_CROSSHAIR.equals(url)) {
            int px = Math.max(8, Math.round(size));
            renderer.drawIcon(LauncherModule.ICON_LOCAL_CROSSHAIR, crosshairImage(px), ix, iy, size, size,
                alpha * UiTheme.ICON_ALPHA, vpWidth, vpHeight);
            return;
        }
        BufferedImage icon = url != null ? UiRemoteImage.get(url) : null;
        if (icon != null) {
            iconFade.markReady();
            renderer.drawIcon(url, icon, ix, iy, size, size, iconFade.alpha() * alpha * UiTheme.ICON_ALPHA, vpWidth, vpHeight);
            return;
        }
        UiColor top = UiTheme.accentLight().multiplyAlpha(UiTheme.ACCENT_DIM.a * alpha);
        UiColor bottom = UiTheme.ACCENT_DIM.multiplyAlpha(alpha);
        renderer.drawGradientRect(ix, iy, ix + size, iy + size, UiTheme.RADIUS_SM, bottom, top, vpWidth, vpHeight);
        float scale = UiTheme.px(UiTheme.Text.VALUE);
        float iw = renderer.textWidth(initial, scale);
        renderer.drawText(initial, ix + (size - iw) / 2f, iy + size / 2f - UiTheme.px(UiTheme.Space.S4),
            UiTheme.ACCENT.multiplyAlpha(alpha), scale, vpWidth, vpHeight);
    }

    /** Détaillé et Compacte : icône en bas à gauche, nom tronqué sur une ligne, description sur deux. */
    private void drawDetailed(UiRenderer renderer, String name, String description, String initial,
                              float drawX, float drawY, float alpha, int vpWidth, int vpHeight) {
        this.tooltip = null;
        float iconSize = UiTheme.px(UiTheme.Space.S36);
        float pad = UiTheme.px(UiTheme.Space.S12);
        drawIconOrInitial(renderer, drawX + pad, drawY + h - iconSize - pad, iconSize, initial, alpha, vpWidth, vpHeight);

        float textX = drawX + pad + iconSize + pad;
        float textMaxW = (drawX + w) - textX - pad;
        float nameScale = UiTheme.px(UiTheme.Text.BODY);
        renderer.drawText(renderer.truncate(name, nameScale, textMaxW), textX, drawY + h - UiTheme.px(26f),
            UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), nameScale, vpWidth, vpHeight);

        // Coupée aux mots sur deux lignes plutôt que tronquée : une description coupée en plein mot n'apprend rien.
        UiRichText.Layout layout = descriptionLayout(description, textMaxW, UiTheme.px(UiTheme.Text.SMALL));
        if (layout != null) {
            UiRichText.draw(renderer, layout, textX, drawY + h - UiTheme.px(34f), alpha, vpWidth, vpHeight);
        }
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
            Collections.singletonList(UiTextSpan.plain(text, UiTheme.TEXT_SECONDARY)), maxWidth, scale, DESC_MAX_LINES);
        return cachedDescLayout;
    }

    /**
     * Grille d'icônes : grande icône au-dessus, bande en bas avec le nom. La
     * bande ENTIÈRE active le module (c'est le widget apparié, invisible, qui
     * prend le clic) : sa couleur dit l'état, elle s'éclaire au survol et
     * respire quand le module est actif. Le cœur est un favori indépendant.
     */
    private void drawIconGrid(UiRenderer renderer, String name, String description, String initial,
                              float drawX, float drawY, float alpha, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        this.tooltip = description;
        float barH = ModGrid.bandHeight();
        float pad = UiTheme.px(UiTheme.Space.S8);

        boolean enabled = pairedToggle != null && pairedToggle.value();
        barHoverAnim.setTarget(pairedToggle != null && pairedToggle.contains(mouseX, mouseY) ? 1f : 0f);
        float barHover = barHoverAnim.get();
        UiColor base = enabled ? UiTheme.ACCENT : UiTheme.TRACK_OFF;
        UiColor hovered = enabled ? UiTheme.accentLight() : UiColor.lerp(UiTheme.TRACK_OFF, UiTheme.TEXT_MUTED, 0.35f);
        UiColor barColor = UiColor.lerp(base, hovered, barHover);
        // Respiration vers l'accent clair (jamais une autre teinte, qui se
        // lirait comme un changement d'état) ; elle s'efface au survol.
        if (enabled) {
            float breath = UiBreathe.wave(BAR_BREATH_PERIOD_S) * BAR_BREATH_AMPLITUDE * (1f - barHover);
            barColor = UiColor.lerp(barColor, UiTheme.accentLight(), breath);
        }
        // Seuls les deux coins bas sont arrondis, alignés sur ceux de la carte.
        renderer.drawRoundedRect(drawX, drawY, drawX + w, drawY + barH,
            UiTheme.RADIUS_MD, UiTheme.RADIUS_MD, 0f, 0f, barColor.multiplyAlpha(alpha), vpWidth, vpHeight);

        float iconAreaH = h - barH;
        float iconSize = Math.max(UiTheme.px(UiTheme.Space.S18), Math.min(w, iconAreaH) * 0.55f);
        drawIconOrInitial(renderer, drawX + (w - iconSize) / 2f, drawY + barH + (iconAreaH - iconSize) / 2f,
            iconSize, initial, alpha, vpWidth, vpHeight);

        float heartReserve = pairedFavorite != null ? (pairedFavorite.w + UiTheme.px(UiTheme.Space.S14)) : pad;
        float nameScale = UiTheme.px(UiTheme.Text.CAPTION);
        String truncName = renderer.truncate(name, nameScale, w - pad * 2f - heartReserve);
        renderer.drawText(truncName, drawX + pad, drawY + barH / 2f - UiTheme.px(UiTheme.Space.S4),
            UiTheme.TEXT_ON_ACCENT.multiplyAlpha(alpha), nameScale, vpWidth, vpHeight);
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
