package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.Window;

/**
 * État partagé entre {@link GlobalUiRenderMixin1211} (logique) et
 * {@link GlobalUiPresentMixin1211} (dessin) — pendant exact de
 * {@code GlobalUiRenderBridge261}, API publique identique.
 *
 * <h2>Plus de réflexion (2026-09-13)</h2>
 *
 * Ce pont résolvait Minecraft, la fenêtre, l'écran courant et le framebuffer
 * par RÉFLEXION sur des noms OFFICIELS écrits en dur ({@code "gfj"},
 * {@code "fyk"}, champs {@code "O"} et {@code "x"}, méthodes {@code "a"},
 * {@code "h"}, {@code "l"}), traduits vers le schéma actif — c'était le code
 * d'avant le dégel de la tranche, déplacé sans être réécrit. Des noms
 * obfusqués en dur ne valent que pour UN jar : c'était le fichier le plus
 * fragile de l'agent.
 *
 * <p>Il est désormais TYPÉ, en noms Yarn, et traduit au chargement grâce à
 * {@link YarnNamed}. Tout ce qu'il touche est PUBLIC sur cette version
 * ({@code getInstance}, {@code setScreen}, {@code currentScreen},
 * {@code getWindow().getHandle()}, {@code getFramebuffer}) : ni accessor ni
 * réflexion nécessaires. Ce n'est pas un Mixin — les corps de méthode d'un
 * Mixin, eux, ne sont PAS traduits par le remappeur.
 *
 * <p>Les signatures restent en {@code Object} : les Mixins appelants ne
 * nomment aucun type du jeu.
 */
@YarnNamed
public final class GlobalUiRenderBridge1211 {
    private GlobalUiRenderBridge1211() {}

    public static volatile UiInputPoller inputPoller;

    public static Object getMcInstance() {
        return MinecraftClient.getInstance();
    }

    public static void setScreen(Object mc, Object screen) {
        ((MinecraftClient) mc).setScreen((Screen) screen);
    }

    /**
     * Ferme l'écran ({@code setScreen(null)}). L'ancien pont avait besoin du
     * type de l'écran fermé pour retrouver la bonne surcharge par réflexion
     * ({@code isInstance(null)} vaut toujours faux) ; un appel typé désigne la
     * surcharge par son descripteur, le paramètre est conservé pour l'API.
     */
    public static void closeScreen(Object mc, Class<?> closingScreenType) {
        ((MinecraftClient) mc).setScreen(null);
    }

    public static long getWindowHandle(Object mc) {
        Window window = ((MinecraftClient) mc).getWindow();
        return window != null ? window.getHandle() : 0L;
    }

    public static Object getCurrentScreen(Object mc) {
        return ((MinecraftClient) mc).currentScreen;
    }

    /**
     * Le framebuffer PRINCIPAL, pour la garde de {@link GlobalUiPresentMixin1211} :
     * le blit à l'écran pourrait en théorie être appelé sur une autre instance
     * (rendu hors écran d'un mod).
     */
    public static Object getMainFramebuffer(Object mc) {
        return ((MinecraftClient) mc).getFramebuffer();
    }
}
