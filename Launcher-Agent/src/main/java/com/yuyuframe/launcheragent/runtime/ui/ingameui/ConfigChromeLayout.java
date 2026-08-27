package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;

/**
 * Arbre de layout PARTAGÉ par les deux écrans de config ({@link
 * UiModConfigScreen} et {@link UiModGroupConfigScreen}) — bandeau d'en-tête
 * avec bouton retour, puis un corps [sous-sidebar | contenu défilant].
 *
 * <p>Factorisé parce que les deux écrans ont EXACTEMENT le même chrome : mêmes
 * métriques, mêmes proportions, mêmes retours utilisateur appliqués aux deux
 * en parallèle au fil du temps (voir l'historique de {@code tabH}/{@code
 * tabGap}, ajustés simultanément des deux côtés). Les dupliquer aurait garanti
 * qu'ils divergent au premier ajustement fait d'un seul côté.
 *
 * <p>Seul le CONTENU diffère entre les deux (réglages d'un module vs onglets
 * cumulant plusieurs modules) — il reste donc chez eux.
 */
final class ConfigChromeLayout {
    private ConfigChromeLayout() {}

    /** Rects résolus (repère moteur, Y montant) — champs {@code null} si le layout natif a échoué, l'appelant garde alors ses valeurs de repli. */
    static final class Chrome {
        final TaffyLayoutResult.Rect back, sidebar, viewport;
        Chrome(TaffyLayoutResult.Rect back, TaffyLayoutResult.Rect sidebar, TaffyLayoutResult.Rect viewport) {
            this.back = back; this.sidebar = sidebar; this.viewport = viewport;
        }
        /** {@code true} si TOUS les rects nécessaires sont là — un arbre partiellement résolu est traité comme un échec complet, pour ne jamais mélanger positions Taffy et positions de repli. */
        boolean complete() { return back != null && sidebar != null && viewport != null; }
    }

    /**
     * @param backBottomOffset distance entre le BAS du bouton retour et le bas
     *        du bandeau — c'est ainsi que l'ancienne version l'exprimait
     *        ({@code screenHeight - HEADER_H + offset}), donc le paramètre le
     *        reprend tel quel plutôt que de le redériver.
     */
    static Chrome solve(int screenWidth, int screenHeight, float headerH, float sideMargin,
                        float subSidebarW, float contentMaxW, float contentPad,
                        float panelBottom, float backSize, float backBottomOffset, float columnGap) {
        TaffyNode screen = new TaffyNode("screen", new TaffyStyle()
            .size(TaffyStyle.px(screenWidth), TaffyStyle.px(screenHeight))
            .flexDirection("column"));

        TaffyStyle headerStyle = new TaffyStyle();
        headerStyle.height = TaffyStyle.px(headerH);
        headerStyle.flexShrink = 0f;
        // Padding haut déduit de l'ancien `screenHeight - HEADER_H + scaled(20)`
        // : le bouton était calé sur le BAS du bandeau, il est ici calé sur son
        // HAUT — même position à l'écran, exprimée dans le sens du flux.
        headerStyle.padding = new String[]{
            TaffyStyle.px(headerH - backSize - backBottomOffset), "0", "0", TaffyStyle.px(sideMargin) };
        TaffyNode header = new TaffyNode("header", headerStyle);
        header.child(LayoutSolver.box("back", backSize, backSize));
        screen.child(header);

        TaffyStyle bodyStyle = new TaffyStyle();
        bodyStyle.flexGrow = 1f;
        bodyStyle.gapCol = TaffyStyle.px(columnGap);
        bodyStyle.padding = new String[]{ "0", TaffyStyle.px(sideMargin),
            TaffyStyle.px(panelBottom), TaffyStyle.px(sideMargin) };
        TaffyNode body = new TaffyNode("body", bodyStyle);

        // Onglets NON inclus : leur nombre n'est connu qu'après construction du
        // contenu, qui a lui-même besoin de la largeur calculée ici. Résolus à
        // part par solveTabs() — la sous-sidebar ayant une largeur fixe et une
        // hauteur indépendante des onglets, les deux calculs sont réellement
        // séparables (pas un contournement).
        TaffyStyle sidebarStyle = new TaffyStyle();
        sidebarStyle.width = TaffyStyle.px(subSidebarW);
        sidebarStyle.flexShrink = 0f;
        body.child(new TaffyNode("sidebar", sidebarStyle));

        TaffyStyle contentStyle = new TaffyStyle();
        contentStyle.flexGrow = 1f;
        // Reproduit `Math.min(CONTENT_MAX_W, ...)` sans le calculer.
        contentStyle.maxWidth = TaffyStyle.px(contentMaxW);
        contentStyle.padding = new String[]{ TaffyStyle.px(contentPad), "0", TaffyStyle.px(contentPad), "0" };
        TaffyNode content = new TaffyNode("content", contentStyle);
        TaffyStyle vpStyle = new TaffyStyle();
        vpStyle.flexGrow = 1f;
        content.child(new TaffyNode("viewport", vpStyle));
        body.child(content);
        screen.child(body);

        LayoutSolver.Solved solved = LayoutSolver.solve(screen, screenWidth, screenHeight);
        if (solved == null) return new Chrome(null, null, null);
        return new Chrome(solved.get("back"), solved.get("sidebar"), solved.get("viewport"));
    }

    /**
     * Pile verticale d'onglets, résolue DANS les bornes de la sous-sidebar —
     * l'appelant replace le résultat à l'écran via le décalage d'{@code apply}.
     *
     * <p>Hauteur de racine EXPLICITE (celle de la sous-sidebar) et non libre :
     * les onglets sont ancrés en HAUT, or l'inversion de l'axe Y se fait par
     * rapport à la hauteur de la racine (voir {@link LayoutSolver}) — une
     * racine ajustée au contenu ferait "flotter" la pile au lieu de la caler
     * sous le bord haut.
     *
     * @return {@code null} si aucun onglet, ou si le layout natif a échoué.
     */
    static LayoutSolver.Solved solveTabs(float sidebarW, float sidebarH, int tabCount,
                                          float tabH, float tabGap, float tabTopGap, float sidePad) {
        if (tabCount <= 0) return null;
        TaffyNode root = LayoutSolver.column("tabs", tabGap - tabH);
        root.style.width = TaffyStyle.px(sidebarW);
        root.style.height = TaffyStyle.px(sidebarH);
        root.style.padding = new String[]{ TaffyStyle.px(tabTopGap - tabH), TaffyStyle.px(sidePad),
            "0", TaffyStyle.px(sidePad) };
        for (int t = 0; t < tabCount; t++) {
            TaffyStyle ts = new TaffyStyle();
            ts.height = TaffyStyle.px(tabH);
            ts.flexShrink = 0f;
            root.child(new TaffyNode("tab:" + t, ts));
        }
        return LayoutSolver.solve(root, sidebarW, sidebarH);
    }
}
