package com.yuyuframe.launcheragent.apimixin.v26_2.core;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apimixin.v26_2.render.GameRendererAccessor262;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.screens.Screen;

/**
 * Équivalent apimixin (audit ROADMAP-agent.md §3.3, "presque plus de
 * réflexion en 26.1.2") de l'ancien {@code mixin.client.v26_1.GlobalUiRenderBridge262}
 * — CONSTRUIT EN PARALLÈLE, PAS ENCORE BRANCHÉ : {@code GlobalUiRenderMixin262}/
 * {@code GlobalUiPresentMixin262} (mixin/) appellent toujours l'ancien pont
 * par réflexion, inchangé. Le basculement (et la suppression du dossier
 * mixin/) se décide plus tard, en bloc.
 *
 * Remplace CHAQUE {@code Class.forName}/{@code getDeclaredMethod}/{@code
 * getDeclaredField}/{@code setAccessible}/{@code invoke} de l'ancien pont —
 * {@code setScreen(Screen)}/{@code getMainRenderTarget()}/{@code
 * getInstance()} restent des appels PUBLICS directs (pas de champ en jeu,
 * rien à gagner d'un Accessor) ; {@code window}/{@code screen}/{@code
 * mouseHandler} passent par {@link MinecraftAccessor262} (2026-08-26, §22 —
 * voir sa javadoc et celle du stub {@code Minecraft.java} pour l'historique
 * du VerifyError qui avait fait éviter tout Accessor ici : RÉSOLU, revenu à
 * l'architecture apimixin par choix explicite — un accessor écrit en noms
 * Yarn "named" reste, EN PLUS, le seul chemin qui pourra un jour couvrir
 * d'autres brackets via {@code MappingsRegistry} (remapper Mixin déjà
 * branché), ce qu'un cast/champ public direct ne permettra jamais).
 *
 * API PUBLIQUE identique à l'ancien pont (mêmes signatures) — le jour du
 * basculement, {@code GlobalUiRenderMixin262}/{@code GlobalUiPresentMixin262}
 * n'auront qu'à changer leur import, aucune autre modification attendue.
 */
public final class GlobalUiRenderBridge262 {
    private GlobalUiRenderBridge262() {}

    public static volatile UiInputPoller inputPoller;

    private static volatile Minecraft mcInstanceCache;

    public static Object getMcInstance() {
        if (mcInstanceCache != null) return mcInstanceCache;
        mcInstanceCache = Minecraft.getInstance();
        return mcInstanceCache;
    }

    /**
     * 26.2 : {@code setScreen} a quitté {@code Minecraft} pour le nouveau
     * {@code Gui} (gestionnaire d'écrans) — passe par {@link GuiAccessor262}.
     */
    public static void setScreen(Object mc, Object screen) {
        GuiAccessor262 gui = gui(mc);
        if (gui != null) gui.la$setScreen((Screen) screen);
    }

    /** Ferme l'écran (setScreen(null)) — voir javadoc de l'ancien pont (closingScreenType inutile ici, setScreen(Screen) est la SEULE surcharge à ce nom, pas d'ambiguïté à lever). */
    public static void closeScreen(Object mc, Class<?> closingScreenType) {
        setScreen(mc, null);
    }

    public static long getWindowHandle(Object mc) {
        if (!(mc instanceof MinecraftAccessor262)) return 0L;
        Window window = ((MinecraftAccessor262) mc).la$window();
        return window != null ? window.handle() : 0L;
    }

    /** {@code screen} — 26.2 : champ du nouveau {@code Gui}, via {@link GuiAccessor262#la$screen()}. */
    public static Object getCurrentScreen(Object mc) {
        GuiAccessor262 gui = gui(mc);
        return gui == null ? null : gui.la$screen();
    }

    /** {@code Minecraft} → accessor de son {@code Gui} 26.2, ou {@code null}. */
    private static GuiAccessor262 gui(Object mc) {
        Object gui = mc instanceof MinecraftAccessor262 ? ((MinecraftAccessor262) mc).la$gui() : null;
        return gui instanceof GuiAccessor262 ? (GuiAccessor262) gui : null;
    }

    /**
     * BUG SIGNALÉ PAR L'UTILISATEUR (voir javadoc de l'ancien pont pour le
     * détail complet — mods d'édition type Axiom qui ouvrent un overlay sans
     * passer par {@code Minecraft.setScreen()}) : {@code
     * Minecraft.mouseHandler.isMouseGrabbed()} reste le signal fiable et
     * agnostique du mod utilisé ici, inchangé.
     */
    public static boolean isMouseGrabbed(Object mc) {
        try {
            if (!(mc instanceof MinecraftAccessor262)) return true;
            MouseHandler handler = ((MinecraftAccessor262) mc).la$mouseHandler();
            return handler == null || handler.isMouseGrabbed();
        } catch (Throwable t) {
            return true; // repli permissif — voir ci-dessus
        }
    }

    /**
     * Cible de rendu principale. 26.2 : {@code Minecraft.getMainRenderTarget()}
     * n'existe plus, elle vit dans {@code GameRenderer.mainRenderTarget} —
     * lue par {@link MinecraftAccessor262#la$gameRenderer()} puis
     * {@link GameRendererAccessor262#la$mainRenderTarget()}.
     */
    public static Object getMainFramebuffer(Object mc) {
        if (!(mc instanceof MinecraftAccessor262)) return null;
        Object gameRenderer = ((MinecraftAccessor262) mc).la$gameRenderer();
        return gameRenderer instanceof GameRendererAccessor262
            ? ((GameRendererAccessor262) gameRenderer).la$mainRenderTarget() : null;
    }
}
