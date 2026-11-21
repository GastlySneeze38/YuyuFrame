package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiAurora;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScreenHeader;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTabItem;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Le cadre commun des écrans de réglages (un module, un groupe de modules) :
 * barre de titre, colonne des sections à gauche, une seule liste qui défile à
 * droite (cliquer une section y fait défiler, la section active suit le
 * défilement), et une colonne d'appoint facultative (l'aperçu d'un module).
 *
 * <p>Les deux écrans avaient chacun leur copie de ce cadre — barre latérale,
 * onglets, bouton retour — à des tailles qui avaient fini par diverger. Une
 * sous-classe ne fournit plus que son CONTENU ({@link #buildContent}).
 */
abstract class UiConfigScreenBase extends UiScreenBase {

    protected final Object lastScreen;
    private final String title;
    protected UiScrollContainer scroll;
    /** Section → haut de son en-tête dans la liste (voir {@code UiScrollContainer#scrollToAnchor}). */
    protected LinkedHashMap<String, Float> anchors = new LinkedHashMap<>();
    private String activeSection;

    private int lastWidth = -1, lastHeight = -1;
    private float lastScale = -1f;

    /** @param title texte source, traduit à l'affichage */
    protected UiConfigScreenBase(String title, Object lastScreen) {
        super(title);
        this.title = title;
        this.lastScreen = lastScreen;
        this.escapeTarget = lastScreen;
    }

    /** Largeur de la colonne des sections (pixels de référence). */
    protected float sidebarWidth() { return 200f; }

    /** Remplit la liste ; rend les ancres des sections, dans l'ordre. */
    protected abstract LinkedHashMap<String, Float> buildContent(UiScrollContainer scroll, float rowX, float rowW);

    /** Le contenu doit-il être refait même sans changement de taille (un module qui ajoute une ligne) ? */
    protected boolean contentChanged() { return false; }

    /** Colonne d'appoint à droite de la liste, ou {@code null}. Sa place est rendue par {@link #extraLaidOut}. */
    protected Box extraColumn() { return null; }

    protected void extraLaidOut(TaffyLayoutResult.Rect rect, TaffyLayoutResult.Rect viewport) {}

    /** Après chaque reconstruction (se placer sur un module, par exemple). */
    protected void afterBuild() {}

    /** Dessin d'appoint, après la liste. */
    protected void drawExtras(UiRenderer renderer) {}

    /**
     * Comme l'accueil (2026-11-20) : pas de voile uniforme presque opaque, le
     * monde reste visible derrière ; {@code UiAurora} l'assombrit en dégradé.
     * Sans verre, le fond opaque reste, sinon le texte serait illisible.
     */
    @Override
    protected com.yuyuframe.launcheragent.apigraphic.value.UiColor overlayColor() {
        return UiRenderer.get(getClass().getClassLoader()).isGlassAvailable()
            ? com.yuyuframe.launcheragent.apigraphic.value.UiColor.TRANSPARENT : UiTheme.OVERLAY_BG;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastWidth || screenHeight != lastHeight || UiTheme.UI_SCALE != lastScale || contentChanged())) {
            lastWidth = screenWidth;
            lastHeight = screenHeight;
            lastScale = UiTheme.UI_SCALE;
            rebuild();
        }
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        // Le flou partagé de l'image, AVANT tout dessin, puis le même fond que
        // l'accueil : le monde reste visible, assombri en dégradé.
        renderer.beginGlassFrame(UiTheme.GLASS_PASSES, screenWidth, screenHeight);
        if (renderer.isGlassAvailable()) UiAurora.draw(renderer, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            updateActiveSection();
            if (scroll != null) scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            drawExtras(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[" + getClass().getSimpleName() + "] uiDraw: " + t);
        }
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        // Sous une fenêtre ouverte, la liste ne réagit pas : la pile le règle.
        if (scroll != null) scroll.pollInput(input);
    }

    private void rebuild() {
        float margin = px(UiTheme.Space.S48);
        float gap = px(UiTheme.Space.S16);

        UiScreenHeader header = new UiScreenHeader(Lang.tr(title), () -> closeTo(lastScreen), () -> closeTo(null));
        UiWidget sidebarPanel = new UiWidget(0f, 0f, 0f, 0f) {
            @Override
            public boolean contains(double mx, double my) { return false; }

            @Override
            public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
                UiPanel.draw(renderer, x, y, w, h, null, vpWidth, vpHeight);
            }
        };

        Box root = UiLayout.column().size(screenWidth, screenHeight)
            .pad(px(UiTheme.Space.S24), margin, px(UiTheme.Space.S20), margin).gap(px(UiTheme.Space.S20));
        root.add(header.layout());
        Box body = root.add(UiLayout.row().grow(1f).gap(gap));
        Box sidebar = body.add(UiLayout.box().width(px(sidebarWidth())).rigid().bind(sidebarPanel));
        Box content = body.add(UiLayout.column().grow(1f).maxWidth(px(620f)).pad(px(UiTheme.Space.S12), 0f, px(UiTheme.Space.S12), 0f));
        Box viewport = content.add(UiLayout.box().grow(1f));
        Box extra = extraColumn();
        if (extra != null) body.add(extra);

        UiLayout.Result layout = UiLayout.solve(root, screenWidth, screenHeight);
        TaffyLayoutResult.Rect vp = layout == null ? null : layout.rect(viewport);
        TaffyLayoutResult.Rect sb = layout == null ? null : layout.rect(sidebar);
        widgets.clear();
        if (vp == null || sb == null) {
            // Déjà journalisé : sans mise en page, seul Échap ferme l'écran.
            scroll = null;
            return;
        }
        if (extra != null) extraLaidOut(layout.rect(extra), vp);

        // La barre de défilement est collée au bord droit de la fenêtre, comme
        // sur le menu (2026-11-21) : les réglages gardent toute la largeur.
        scroll = new UiScrollContainer(vp.x, vp.y, vp.w, Math.max(1f, vp.h))
            .railAt(screenWidth - px(UiTheme.Space.S16));
        anchors = buildContent(scroll, vp.x, vp.w);
        if (activeSection == null || !anchors.containsKey(activeSection)) {
            activeSection = anchors.isEmpty() ? null : anchors.keySet().iterator().next();
        }

        // Le fond de la colonne d'abord : il est dessiné sous les onglets.
        widgets.add(sidebarPanel);
        header.addTo(widgets);

        // Les onglets, une fois les sections connues, dans la colonne.
        if (!anchors.isEmpty()) {
            Box tabs = UiLayout.column().size(sb.w, sb.h).pad(px(UiTheme.Space.S12), px(UiTheme.Space.S8))
                .gap(px(UiTheme.Space.S4));
            for (String section : anchors.keySet()) {
                UiTabItem tab = new UiTabItem(Lang.tr(section), () -> section.equals(activeSection), () -> {
                    Float anchor = anchors.get(section);
                    if (anchor != null && scroll != null) scroll.scrollToAnchor(anchor);
                });
                tabs.add(UiLayout.box().height(px(38f)).rigid().bind(tab));
                widgets.add(tab);
            }
            UiLayout.solve(tabs, sb.w, sb.h, sb.x, sb.y);
        }
        afterBuild();
    }

    /**
     * La section active d'après le défilement, en POURCENTAGE : la dernière
     * dont la part du contenu est déjà dépassée. Ainsi la dernière section
     * devient toujours active tout en bas, quelle que soit la hauteur des
     * sections (un seuil en pixels ne le garantissait pas).
     */
    private void updateActiveSection() {
        if (scroll == null || anchors.isEmpty()) return;
        float progress = scroll.scrollProgress();
        String best = null;
        for (Map.Entry<String, Float> entry : anchors.entrySet()) {
            if (scroll.baseYFraction(entry.getValue()) <= progress + 0.001f) best = entry.getKey();
        }
        activeSection = best != null ? best : anchors.keySet().iterator().next();
    }
}
