package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.screens.Screen;

/**
 * Équivalent apimixin (audit ROADMAP-agent.md §3.3, "presque plus de
 * réflexion en 26.1.2") de l'ancien {@code mixin.client.v26_1.GlobalUiRenderBridge261}
 * — CONSTRUIT EN PARALLÈLE, PAS ENCORE BRANCHÉ : {@code GlobalUiRenderMixin261}/
 * {@code GlobalUiPresentMixin261} (mixin/) appellent toujours l'ancien pont
 * par réflexion, inchangé. Le basculement (et la suppression du dossier
 * mixin/) se décide plus tard, en bloc.
 *
 * Remplace CHAQUE {@code Class.forName}/{@code getDeclaredMethod}/{@code
 * getDeclaredField}/{@code setAccessible}/{@code invoke} de l'ancien pont —
 * {@code setScreen(Screen)}/{@code getMainRenderTarget()}/{@code
 * getInstance()} restent des appels PUBLICS directs (pas de champ en jeu,
 * rien à gagner d'un Accessor) ; {@code window}/{@code screen}/{@code
 * mouseHandler} passent par {@link MinecraftAccessor261} (2026-08-26, §22 —
 * voir sa javadoc et celle du stub {@code Minecraft.java} pour l'historique
 * du VerifyError qui avait fait éviter tout Accessor ici : RÉSOLU, revenu à
 * l'architecture apimixin par choix explicite — un accessor écrit en noms
 * Yarn "named" reste, EN PLUS, le seul chemin qui pourra un jour couvrir
 * d'autres brackets via {@code MappingsRegistry} (remapper Mixin déjà
 * branché), ce qu'un cast/champ public direct ne permettra jamais).
 *
 * API PUBLIQUE identique à l'ancien pont (mêmes signatures) — le jour du
 * basculement, {@code GlobalUiRenderMixin261}/{@code GlobalUiPresentMixin261}
 * n'auront qu'à changer leur import, aucune autre modification attendue.
 */
public final class GlobalUiRenderBridge261 {
    private GlobalUiRenderBridge261() {}

    public static volatile UiInputPoller inputPoller;

    private static volatile Minecraft mcInstanceCache;

    public static Object getMcInstance() {
        if (mcInstanceCache != null) return mcInstanceCache;
        mcInstanceCache = Minecraft.getInstance();
        return mcInstanceCache;
    }

    public static void setScreen(Object mc, Object screen) {
        ((Minecraft) mc).setScreen((Screen) screen);
    }

    /** Ferme l'écran (setScreen(null)) — voir javadoc de l'ancien pont (closingScreenType inutile ici, setScreen(Screen) est la SEULE surcharge à ce nom, pas d'ambiguïté à lever). */
    public static void closeScreen(Object mc, Class<?> closingScreenType) {
        setScreen(mc, null);
    }

    public static long getWindowHandle(Object mc) {
        if (!(mc instanceof MinecraftAccessor261)) return 0L;
        Window window = ((MinecraftAccessor261) mc).la$window();
        return window != null ? window.handle() : 0L;
    }

    /** {@code screen} — via {@link MinecraftAccessor261#la$screen()} (2026-08-26, §22). */
    public static Object getCurrentScreen(Object mc) {
        return mc instanceof MinecraftAccessor261 ? ((MinecraftAccessor261) mc).la$screen() : null;
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
            if (!(mc instanceof MinecraftAccessor261)) return true;
            MouseHandler handler = ((MinecraftAccessor261) mc).la$mouseHandler();
            return handler == null || handler.isMouseGrabbed();
        } catch (Throwable t) {
            return true; // repli permissif — voir ci-dessus
        }
    }

    public static Object getMainFramebuffer(Object mc) {
        return ((Minecraft) mc).getMainRenderTarget();
    }
}
