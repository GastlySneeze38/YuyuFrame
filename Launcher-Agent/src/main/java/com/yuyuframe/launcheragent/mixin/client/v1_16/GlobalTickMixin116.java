package com.yuyuframe.launcheragent.mixin.client.v1_16;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ouverture/fermeture RÉELLE de notre écran custom — sur {@code
 * MinecraftClient.tick()}, PAS sur le TAIL de {@code GameRenderer.render()}
 * (voir {@link GlobalUiRenderMixin116}, qui ne fait plus que dessiner/sonder
 * l'input désormais). Exactement le point que les vrais mods Fabric utilisent
 * pour appeler {@code setScreen()} (voir doc officielle Fabric, "Custom
 * Screens" : "from many places, such as a key binding, a command, or a
 * client packet handler" — jamais depuis un hook de rendu).
 *
 * Raison du déplacement (voir historique de session) : la vraie {@code
 * setScreen(Screen)} de Minecraft pompe les évènements GLFW en direct
 * ({@code RenderSystem.limitDisplayFPS() -> glfwWaitEventsTimeout()}) —
 * l'appeler depuis le TAIL de render() (déjà imbriqué au plus profond de la
 * boucle de rendu) causait un traitement RÉENTRANT de clics en attente, à
 * l'origine d'un déluge de NullPointerException ET d'une fermeture cassée
 * (finissait par renvoyer au menu Échap vanilla au lieu du jeu). `tick()` est
 * un point de la boucle principale déjà sûr et non-réentrant pour ce genre
 * d'appel — plus besoin de différer via {@code MinecraftClient.execute(Runnable)}
 * (voir {@code ScreenBridge116}, qui appelle maintenant directement).
 *
 * `this` DANS cette méthode injectée EST déjà l'instance MinecraftClient (le
 * Mixin est fusionné dans sa propre classe) — contrairement à
 * GlobalUiRenderMixin116 (fusionné dans GameRenderer), qui doit résoudre `mc`
 * par réflexion. Aucune résolution de `mc` nécessaire ici.
 */
@Mixin(targets = "net.minecraft.client.MinecraftClient")
public abstract class GlobalTickMixin116 {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void la$onTickHead(CallbackInfo ci) {
        try {
            // Même précaution qu'au TAIL de render() — voir sa javadoc pour le
            // détail du bug de classloader Knot/app que ça corrige.
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = this;
            Object currentScreen = ScreenBridge116.getCurrentScreen(mc);

            if (currentScreen == null) {
                if (ScreenBridge116.consumePendingMenuOpen()) {
                    LauncherLog.info("[LauncherAgent] DIAG-116: ouverture UiMainMenuScreen (tick)");
                    UiMainMenuScreen screen = new UiMainMenuScreen(null);
                    ScreenBridge116.setScreen(mc, screen);
                    diagDumpClickMethods(screen);
                }
                return;
            }

            // Navigation demandée par l'écran (voir UiScreenBase.closeTo, appelé
            // depuis les vraies méthodes mouseClicked()/keyPressed() désormais —
            // voir leur historique) — consommée ici, jamais depuis render().
            if (currentScreen instanceof UiScreenBase) {
                UiScreenBase uiScreen = (UiScreenBase) currentScreen;
                if (uiScreen.hasPendingNavigation()) {
                    Object target = uiScreen.consumePendingNavigation();
                    if (target != null) ScreenBridge116.setScreen(mc, target);
                    else ScreenBridge116.closeScreen(mc, currentScreen.getClass());
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalTickMixin116: " + t);
        }
    }

    /**
     * Diagnostic ponctuel (une seule fois, à la première ouverture) — dump
     * réflexif de la VRAIE table de méthodes une fois la superclasse patchée,
     * pour vérifier empiriquement que mouseClicked()/keyPressed() existent
     * bien à l'endroit attendu (voir historique de session : ces deux
     * méthodes ne se déclenchent jamais malgré une résolution setScreen
     * désormais correcte — cette dump doit dire si c'est un problème de
     * méthode manquante/masquée ou d'un dispatch qui n'atteint jamais notre
     * écran pour une autre raison).
     */
    private static boolean diagDumped;

    private static void diagDumpClickMethods(Object screen) {
        if (diagDumped) return;
        diagDumped = true;
        Class<?> screenClass = screen.getClass();
        try {
            java.lang.reflect.Method mc = screenClass.getMethod("mouseClicked", double.class, double.class, int.class);
            LauncherLog.info("[LauncherAgent] DIAG-116: mouseClicked trouvé par réflexion, déclarée sur "
                + mc.getDeclaringClass());
        } catch (NoSuchMethodException e) {
            LauncherLog.err("[LauncherAgent] DIAG-116: mouseClicked(double,double,int) INTROUVABLE sur " + screenClass + ": " + e);
        }
        try {
            java.lang.reflect.Method kp = screenClass.getMethod("keyPressed", int.class, int.class, int.class);
            LauncherLog.info("[LauncherAgent] DIAG-116: keyPressed trouvé par réflexion, déclarée sur "
                + kp.getDeclaringClass());
        } catch (NoSuchMethodException e) {
            LauncherLog.err("[LauncherAgent] DIAG-116: keyPressed(int,int,int) INTROUVABLE sur " + screenClass + ": " + e);
        }
        // UiScreenBase (compilée par nous) est le PARENT DIRECT — sa PROPRE
        // superclasse est celle patchée par ScreenStubPatcher vers la vraie
        // classe Screen du jeu ("dot"). C'est CETTE classe qu'il faut inspecter,
        // pas celle de l'écran lui-même (qui ne remonte qu'à UiScreenBase, du
        // code 100% à nous, sans intérêt ici).
        Class<?> uiScreenBaseSuper = screenClass.getSuperclass().getSuperclass();
        LauncherLog.info("[LauncherAgent] DIAG-116: vraie classe Screen patchée = " + uiScreenBaseSuper
            + " superclasse=" + uiScreenBaseSuper.getSuperclass()
            + " interfaces=" + java.util.Arrays.toString(uiScreenBaseSuper.getInterfaces()));
        // Vérifie la résolution NATURELLE (sur la vraie classe "dot" elle-même,
        // AVANT toute sous-classe à nous) — pour savoir si mouseClicked existe
        // bien nativement ici, indépendamment de notre override.
        try {
            java.lang.reflect.Method natural = uiScreenBaseSuper.getMethod("mouseClicked", double.class, double.class, int.class);
            LauncherLog.info("[LauncherAgent] DIAG-116: mouseClicked NATIF sur dot, déclarée sur " + natural.getDeclaringClass());
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] DIAG-116: mouseClicked NATIF introuvable sur dot: " + t);
        }
        // Dump de TOUS les champs déclarés (pas juste publics) sur "dot" —
        // width/height pourraient exister sous un autre nom/visibilité que
        // supposé (même leçon que "setScreen"/"openScreen").
        try {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : uiScreenBaseSuper.getDeclaredFields()) {
                sb.append(f.getType().getSimpleName()).append(' ').append(f.getName()).append(", ");
            }
            LauncherLog.info("[LauncherAgent] DIAG-116: champs déclarés sur dot = " + sb);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] DIAG-116: dump champs dot échoué: " + t);
        }
    }
}
