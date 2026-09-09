package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.core.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.Block;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.DividerBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.HeadingBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImageRowBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImgRef;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ListItemBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ParagraphBlock;
import static com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.parseBlocks;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Extrait de ModrinthProjectDetailScreen le 2026-08-27 : ce widget était
// une classe interne STATIQUE, donc sans lien avec l'instance de l'écran —
// sa sortie est un simple déplacement, pas un changement de comportement.
/**
 * Galerie navigable — une image affichée à la fois (ratio préservé, sans
 * crop, contrairement à l'ancienne grille de miniatures carrées), flèches
 * précédent/suivant + compteur "n / total". Préchargement des images
 * voisines (index ± 1) pour une navigation fluide.
 *
 * Clic sur les flèches géré via {@code pollContinuous} (comme
 * {@code ResultCard.pollContinuous} dans ModrinthContentScreen) — pas via
 * onClick()/contains(), qui ne reçoivent aucune coordonnée et ne
 * pourraient donc pas distinguer flèche gauche/droite dans un seul widget.
 */
public final class GalleryCarousel extends UiWidget {
    // Boutons flèche CIRCULAIRES flottants au-dessus de l'image (au lieu
    // des bandes translucides pleine hauteur d'origine, jugées datées) —
    // demande explicite de l'utilisateur ("un rendu beaucoup plus
    // moderne pour la galerie"). radius = ARROW_D/2 sur une boîte carrée
    // = cercle parfait (voir UiRenderer.drawRoundedRect, SDF de boîte
    // arrondie standard, pas de gestion spéciale de cercle nécessaire).
    private static final float ARROW_D = 40f;
    private static final float ARROW_MARGIN = 14f;
    private static final float DOT_D = 7f;
    private static final float DOT_GAP = 10f;
    private static final int MAX_DOTS = 10; // au-delà, un pastille "n / total" remplace les points (illisible sinon)

    private final List<String> urls;
    private final List<String> cacheKeys; // "gallery-full:"+url précalculé une fois par image — voir ImgRef.cacheKey pour le même motif (évite une concaténation String à chaque frame)
    private int index;
    private boolean prevLeftDown;
    private final UiAnimatedFloat leftHover = new UiAnimatedFloat(0f, 14f);
    private final UiAnimatedFloat rightHover = new UiAnimatedFloat(0f, 14f);
    // Fondu d'entrée ajouté (voir audit runtime/ui/) — une instance PAR
    // URL (pas un seul champ partagé) : chaque image de la galerie doit
    // rejouer son propre fondu la première fois qu'elle arrive, y
    // compris en revenant sur une image déjà vue plus tôt dans la
    // session (cache déjà chaud, mais le widget ne le sait pas avant que
    // markReady() soit appelé pour CETTE clé précise).
    private final Map<String, UiAsyncFade> imageFades = new HashMap<>();

    private UiAsyncFade fadeFor(String cacheKey) {
        return imageFades.computeIfAbsent(cacheKey, k -> new UiAsyncFade());
    }

    public GalleryCarousel(float x, float y, float w, float h, List<String> urls) {
        super(x, y, w, h);
        this.urls = urls;
        List<String> keys = new ArrayList<>(urls.size());
        for (String u : urls) keys.add("gallery-full:" + u);
        this.cacheKeys = keys;
    }

    private float leftCx() { return x + ARROW_MARGIN + ARROW_D / 2f; }
    private float rightCx() { return x + w - ARROW_MARGIN - ARROW_D / 2f; }
    private float arrowCy() { return y + h / 2f; }

    private boolean overCircle(double mx, double my, float cx, float cy) {
        double dx = mx - cx, dy = my - cy;
        float r = ARROW_D / 2f;
        return dx * dx + dy * dy <= (double) r * r;
    }

    @Override
    public void pollContinuous(UiInputPoller input) {
        boolean justPressed = input.leftDown && !prevLeftDown;
        prevLeftDown = input.leftDown;
        if (!justPressed || urls.size() <= 1) return;
        if (overCircle(input.mouseX, input.mouseY, leftCx(), arrowCy())) index = (index - 1 + urls.size()) % urls.size();
        else if (overCircle(input.mouseX, input.mouseY, rightCx(), arrowCy())) index = (index + 1) % urls.size();
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float fade = clipFade;
        // Panneau de verre (rework 2026-08-27) — contour repris sur
        // GLASS_BORDER quand le verre est actif : TRACK_OFF (un gris plein)
        // se voyait bien sur un fond opaque, il se confond avec le décor
        // flouté une fois le panneau translucide.
        UiColor panelBorder = renderer.isGlassAvailable()
            ? UiTheme.GLASS_BORDER.multiplyAlpha(fade)
            : UiTheme.TRACK_OFF.multiplyAlpha(fade * 0.8f);
        renderer.drawGlassPanel(x, y, x + w, y + h, UiTheme.RADIUS_MD,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_PANEL, UiTheme.PANEL_BG_ALT.multiplyAlpha(fade),
            panelBorder, 1.4f, vpWidth, vpHeight);

        String url = urls.get(index);
        BufferedImage img = UiRemoteImage.get(url);
        if (urls.size() > 1) {
            UiRemoteImage.get(urls.get((index + 1) % urls.size()));
            UiRemoteImage.get(urls.get((index - 1 + urls.size()) % urls.size()));
        }

        if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
            UiAsyncFade imgFade = fadeFor(cacheKeys.get(index));
            imgFade.markReady();
            float boxW = w - (ARROW_MARGIN + ARROW_D) * 2f - 16f, boxH = h - 16f;
            float scale = Math.min(Math.min(boxW / img.getWidth(), boxH / img.getHeight()), 4f);
            float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
            renderer.drawIcon(cacheKeys.get(index), img, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh,
                imgFade.alpha() * fade, vpWidth, vpHeight);
        } else {
            String label = "Chargement...";
            float lw = renderer.textWidth(label, 0.42f);
            renderer.drawText(label, x + (w - lw) / 2f, y + h / 2f - 6f, UiTheme.TEXT_MUTED, 0.42f, vpWidth, vpHeight);
        }

        if (urls.size() > 1) {
            boolean hoverLeft = overCircle(mouseX, mouseY, leftCx(), arrowCy());
            boolean hoverRight = overCircle(mouseX, mouseY, rightCx(), arrowCy());
            leftHover.setTarget(hoverLeft ? 1f : 0f);
            rightHover.setTarget(hoverRight ? 1f : 0f);
            drawArrowButton(renderer, leftCx(), arrowCy(), "<", leftHover.get(), fade, vpWidth, vpHeight);
            drawArrowButton(renderer, rightCx(), arrowCy(), ">", rightHover.get(), fade, vpWidth, vpHeight);

            if (urls.size() <= MAX_DOTS) drawDots(renderer, fade, vpWidth, vpHeight);
            else drawCounterPill(renderer, fade, vpWidth, vpHeight);
        }
    }

    private void drawArrowButton(UiRenderer renderer, float cx, float cy, String glyph, float hover, float fade, int vpWidth, int vpHeight) {
        float radius = ARROW_D / 2f;
        UiColor bg = UiColor.lerp(UiTheme.OVERLAY_BG, UiTheme.ACCENT, hover * 0.85f).multiplyAlpha(fade * (0.55f + hover * 0.35f));
        renderer.drawRoundedRect(cx - radius, cy - radius, cx + radius, cy + radius, radius, bg, vpWidth, vpHeight);
        float gw = renderer.textWidth(glyph, 0.5f);
        renderer.drawText(glyph, cx - gw / 2f, cy - 7f, UiTheme.TEXT_PRIMARY.multiplyAlpha(fade), 0.5f, vpWidth, vpHeight);
    }

    /** Indicateurs à points façon carrousel mobile (Instagram/iOS) — point plein ACCENT pour l'image courante, points estompés pour le reste. Seulement si assez peu d'images pour rester lisible (voir MAX_DOTS). */
    private void drawDots(UiRenderer renderer, float fade, int vpWidth, int vpHeight) {
        float totalW = urls.size() * DOT_D + (urls.size() - 1) * DOT_GAP;
        float startX = x + (w - totalW) / 2f;
        float dotY = y + 16f;
        for (int i = 0; i < urls.size(); i++) {
            float cx = startX + i * (DOT_D + DOT_GAP) + DOT_D / 2f;
            boolean active = i == index;
            UiColor color = (active ? UiTheme.ACCENT : UiTheme.TEXT_MUTED).multiplyAlpha(fade * (active ? 1f : 0.55f));
            renderer.drawRoundedRect(cx - DOT_D / 2f, dotY, cx + DOT_D / 2f, dotY + DOT_D, DOT_D / 2f, color, vpWidth, vpHeight);
        }
    }

    private void drawCounterPill(UiRenderer renderer, float fade, int vpWidth, int vpHeight) {
        String counter = (index + 1) + " / " + urls.size();
        float cw = renderer.textWidth(counter, 0.38f);
        float pillW = cw + 24f, pillH = 26f;
        float px = x + w - pillW - 14f, py = y + 14f;
        renderer.drawRoundedRect(px, py, px + pillW, py + pillH, pillH / 2f, UiTheme.OVERLAY_BG.multiplyAlpha(fade * 0.85f), vpWidth, vpHeight);
        renderer.drawText(counter, px + 12f, py + 8f, UiTheme.TEXT_SECONDARY.multiplyAlpha(fade), 0.38f, vpWidth, vpHeight);
    }
}
