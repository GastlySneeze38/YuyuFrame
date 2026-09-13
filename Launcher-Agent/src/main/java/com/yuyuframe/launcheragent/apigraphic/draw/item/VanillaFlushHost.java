package com.yuyuframe.launcheragent.apigraphic.draw.item;

/**
 * Ce que le point d'accroche Mixin tient en main quand il demande le vidage des
 * files vanilla.
 *
 * <h2>La décision qui manquait</h2>
 *
 * Le découpage de l'ancien {@code UiVanillaItemRenderer} butait sur une seule
 * question, posée noir sur blanc dans son {@code package-info} : « les séparer
 * demande d'abord de décider ce que le contrat dit du cycle de vie d'une
 * frame ». La voici.
 *
 * <p>Le moteur ne peut pas aller CHERCHER le contexte de rendu vivant : il
 * n'existe qu'à l'intérieur d'une méthode du jeu, pendant quelques
 * microsecondes. C'est donc le hook qui le DONNE. Chaque bracket a accroché
 * son vidage là où le z-order sort juste (leçon coûteuse : accroché trop tôt,
 * nos icônes passent DERRIÈRE le HUD et l'écran ouvert), et se retrouve donc
 * avec un objet différent en main. Cette énumération est la liste exhaustive
 * de ces objets — le vocabulaire commun entre les Mixins et les ères.
 *
 * <p>Une ère ne traite que les hôtes que SES points d'accroche lui envoient et
 * ignore les autres : la valeur dit ce qu'on tient, pas ce qu'il faut en faire.
 */
public enum VanillaFlushHost {

    /**
     * {@code GameRenderer} vivant — 26.1.2, {@code GuiFlushMixin261}.
     * L'ère remonte {@code guiRenderer} puis son état de GUI.
     */
    GAME_RENDERER,

    /**
     * {@code GuiRenderer} vivant — 1.21.11, {@code GuiFlushMixin1211}, en HEAD
     * de {@code render(GpuBufferSlice)} : APRÈS que l'écran ouvert a fini
     * d'ajouter son contenu, JUSTE AVANT la soumission GPU. C'est le point qui
     * a corrigé le z-order du panneau shulker. L'ère remonte l'état de GUI.
     */
    GUI_RENDERER,

    /**
     * {@code GuiRenderState} vivant, directement — chemin de
     * {@code VanillaGuiSink1211}, qui l'a déjà sous la main et n'a rien à
     * remonter.
     */
    GUI_STATE,

    /**
     * {@code DrawContext} vivant du HUD — 1.21.4, {@code HudItemFlushMixin1214},
     * en TAIL de {@code InGameHud.render(...)}. C'est l'instance que vanilla
     * utilise lui-même pour tout le HUD de la frame ; y ajouter nos commandes
     * évite tous les contournements d'état GL qu'une instance reconstruite
     * imposait.
     */
    DRAW_CONTEXT,

    /**
     * {@code DrawContext} vivant d'un écran de conteneur — 1.21.4, en TAIL de
     * {@code HandledScreen.drawForeground(...)}, seul point où un fond de
     * fenêtre passe PAR-DESSUS l'écran d'inventaire ouvert.
     *
     * <p>⚠️ Aucun point d'accroche ne l'émet depuis le 2026-08-31 : son Mixin a
     * été supprimé avec le dernier producteur de la file. La capacité est
     * conservée (elle est réelle et ce bracket n'a pas d'architecture
     * différée) ; la rebrancher demande de recréter ce Mixin, en visant
     * {@code HandledScreen} et surtout PAS la classe {@code Screen} partagée
     * par tous les écrans, nos écrans custom compris.
     */
    DRAW_CONTEXT_CONTAINER
}
