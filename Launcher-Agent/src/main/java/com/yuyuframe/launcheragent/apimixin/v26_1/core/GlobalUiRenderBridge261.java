package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.UiInputPoller;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Window;
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
 * getDeclaredField}/{@code setAccessible}/{@code invoke} de l'ancien pont
 * par deux techniques, toutes deux SANS réflexion au runtime :
 * <pre>
 *   Minecraft.getInstance()/setScreen(Screen)/getMainRenderTarget()
 *   Window.handle()/MouseHandler.isMouseGrabbed()
 * </pre>
 * — méthodes PUBLIQUES vérifiées via javap sur le jar client 26.1.2 réel :
 * appel DIRECT possible via les stubs typés (compile-only, jamais dans le
 * JAR final — au runtime c'est toujours la VRAIE classe du jeu qui répond,
 * voir build.bat).
 * <pre>
 *   Minecraft.window / Minecraft.screen / Minecraft.mouseHandler
 * </pre>
 * — champs PRIVÉS, inaccessibles par appel direct : exposés via {@link
 * MinecraftAccessor261} (Sponge {@code @Accessor}, même dossier), généré
 * par Mixin au tissage — zéro réflexion, contrairement à {@code
 * getDeclaredField}+{@code setAccessible}.
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
        Window window = ((MinecraftAccessor261) mc).la$window();
        return window != null ? window.handle() : 0L;
    }

    public static Object getCurrentScreen(Object mc) {
        return ((MinecraftAccessor261) mc).la$screen();
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
