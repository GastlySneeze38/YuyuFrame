package com.yuyuframe.launcheragent.mixin.client.v1_8;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPollerLegacy;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Équivalent 1.8.9 de GlobalUiRenderMixin (package client, 1.21+) — même
 * principe : un seul point d'accroche global sur GameRenderer.render(),
 * CallbackInfo seul (aucun paramètre capturé), donc aucun stub requis — la
 * signature réelle est juste (float, long) ici de toute façon (vérifié dans
 * mappings/mappings-1.8.9.tiny : GameRenderer.render(F J)V, nommé "render"
 * côté Yarn, comme en 1.21).
 *
 * Refmap écrit systématiquement par IsolatedBootstrap.start() (voir ce
 * fichier) — nécessaire même en vanilla pour que Mixin valide '@Inject' contre
 * le nom officiel réel, pas la chaîne Yarn named littérale.
 *
 * MinecraftClient 1.8.9 n'a PAS de notion de "window handle" (LWJGL2, Display
 * global implicite) — UiInputPollerLegacy n'en a donc pas besoin, contrairement
 * à UiInputPollerModern (GLFW) côté 1.21+.
 *
 * Point d'entrée du menu : Right Shift, pollé même sans écran ouvert — voir
 * GlobalUiRenderMixin (1.21+) pour le détail, logique identique ici.
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin189 {

    private static final String CLS_MC = "ave"; // net.minecraft.client.MinecraftClient

    private static UiInputPoller inputPoller;

    @Inject(method = "render(FJ)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            Object mc = getMcInstance();
            if (mc == null) return;

            if (inputPoller == null) {
                inputPoller = new UiInputPollerLegacy(GlobalUiRenderMixin189.class.getClassLoader());
                // Force le chargement des modules intégrés (voir ModuleRegistry) dès
                // la première frame — sinon leurs éléments HUD (voir runtime.module)
                // ne s'enregistreraient qu'à la première ouverture du menu "YuyuFrame".
                ModuleRegistry.all();
                // Idem pour GlobalUiSettings — voir GlobalUiRenderMixin (1.21+)
                // pour le détail du bug que ça corrige (mauvaise taille
                // d'interface dès la première ouverture du menu).
                GlobalUiSettings.INSTANCE.onConfigChanged();
            }
            inputPoller.poll();

            // Logique de module continue, qu'un écran custom soit ouvert ou
            // non (ex: FOV forcé, voir FovModule) — équivalent de TickEvent
            // côté Forge, mais ici juste "cette même méthode s'exécute à
            // chaque frame" (suffisant, pas besoin d'un hook de tick séparé).
            ModuleRegistry.tickAll();

            Object currentScreen = getCurrentScreen(mc);

            if (currentScreen == null) {
                UiRenderer renderer = UiRenderer.get(GlobalUiRenderMixin189.class.getClassLoader());
                // Overlay HUD permanent — même règle que le HUD vanilla
                // (hotbar/vie), qui ne s'affiche pas non plus quand un écran
                // est ouvert. Pendant l'édition (UiHudEditorScreen), ce sont
                // les UiHudBox de cet écran qui dessinent, pas cet appel.
                // Idem F1 (voir HudOverlayRenderer#vanillaHudHidden) : nos
                // éléments HUD/modules doivent disparaître avec le HUD vanilla.
                if (!HudOverlayRenderer.vanillaHudHidden()) {
                    HudOverlayRenderer.render(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                    ModuleRegistry.renderOverlayAll(renderer, inputPoller.fbWidth, inputPoller.fbHeight);
                }
                if (inputPoller.menuKeyPressed) {
                    setScreen(mc, new UiMainMenuScreen(null));
                }
                return;
            }

            if (!(currentScreen instanceof UiDrawable)) {
                // Écran NON custom ouvert (chat, inventaire, tout autre GUI
                // vanilla/mod) — visibilité selon le TYPE d'écran (voir
                // HudOverlayRenderer.renderPersistent/HudScreenKind/GlobalUiSettings).
                HudOverlayRenderer.renderPersistent(UiRenderer.get(GlobalUiRenderMixin189.class.getClassLoader()),
                    currentScreen, inputPoller.fbWidth, inputPoller.fbHeight);
                return;
            }
            UiDrawable ui = (UiDrawable) currentScreen;
            ui.uiPollInput(inputPoller);
            ui.uiDraw(inputPoller.mouseX, inputPoller.mouseY);

            // Navigation demandée par l'écran lui-même (UiScreenBase.closeTo,
            // ex: clic sur une carte de mod ou bouton retour) — appliquée ICI,
            // seul endroit qui connaît déjà mc + setScreen correctement résolus
            // pour 1.8.9 (voir UiScreenBase.closeTo pour le pourquoi).
            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) setScreen(mc, target);
                    else closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin189: " + t);
        }
    }

    private static void setScreen(Object mc, Object screen) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a"); // setScreen(Screen)
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isInstance(screen)) {
                m.invoke(mc, screen);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderMixin189: setScreen introuvable");
    }

    /**
     * Ferme l'écran (setScreen(null)) — méthode séparée de setScreen() car
     * Method.getParameterTypes()[0].isInstance(null) vaut TOUJOURS false (donc
     * inutilisable pour retrouver le bon overload quand screen==null) : on
     * matche ici par assignabilité depuis la classe de l'écran qu'on ferme.
     */
    private static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "a");
        for (java.lang.reflect.Method m : mc.getClass().getMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 1
                    && !m.getParameterTypes()[0].isPrimitive()
                    && m.getParameterTypes()[0].isAssignableFrom(closingScreenType)) {
                m.invoke(mc, (Object) null);
                return;
            }
        }
        LauncherLog.warn("[LauncherAgent] GlobalUiRenderMixin189: closeScreen: setScreen introuvable");
    }

    private static Object mcInstanceCache;

    private static Object getMcInstance() throws Exception {
        if (mcInstanceCache != null) return mcInstanceCache;
        ClassLoader cl = GlobalUiRenderMixin189.class.getClassLoader();
        String mcClassName = MappingsRegistry.runtimeClass(CLS_MC);
        Class<?> mcClass = Class.forName(mcClassName, true, cl);
        java.util.Set<String> names = MappingsRegistry.runtimeMethodNames(CLS_MC, "A"); // getInstance()
        for (java.lang.reflect.Method m : mcClass.getDeclaredMethods()) {
            if (names.contains(m.getName()) && m.getParameterCount() == 0
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                m.setAccessible(true);
                mcInstanceCache = m.invoke(null);
                return mcInstanceCache;
            }
        }
        return null;
    }

    private static Object getCurrentScreen(Object mc) throws Exception {
        String fieldName = MappingsRegistry.runtimeField(CLS_MC, "m"); // currentScreen
        Class<?> c = mc.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(mc);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
