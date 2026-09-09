package com.yuyuframe.launcheragent.mixin.client;

import com.yuyuframe.launcheragent.apimixin.loader.FabricKnotExposer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Point d'accroche GLOBAL pour la LOGIQUE de notre moteur UI custom (input
 * GLFW, tick des modules, ouverture du menu) — PAS le dessin réel, voir
 * {@link GlobalUiPresentMixin} pour ça et pourquoi c'est séparé.
 *
 * BUG TROUVÉ (era E, 1.21.11 — "rien ne s'affiche jamais", voir historique de
 * session) : dessiner en GL brut ICI (TAIL de GameRenderer.render()) ne
 * survit pas à l'écran — confirmé par désassemblage bytecode (javap -c sur
 * MinecraftClient, mappings 1.21.11) : MinecraftClient.render(boolean) appelle
 * GameRenderer.render(...) PUIS, juste après (section profiler "blit"),
 * Framebuffer.blitToScreen() — qui recopie la cible de rendu Blaze3D interne
 * (hors-écran, "RenderPipeline"/"CommandEncoder") PAR-DESSUS le framebuffer
 * par défaut, écrasant tout dessin fait avant (confirmé indépendamment par
 * glReadPixels : le pixel EST bien écrit sur le moment, mais rien n'apparaît
 * jamais visuellement au final). Exactement le problème documenté par Fabric
 * ("injecter dans GameRenderer.render en TAIL est non-fiable depuis 1.21.6+",
 * https://github.com/FabricMC/fabric-api/issues/3908).
 *
 * Un second @Inject directement sur MinecraftClient.render(boolean), juste
 * après l'appel à blitToScreen(), a été tenté puis ABANDONNÉ : dans un gros
 * modpack (100 mods), MinecraftClient (net.minecraft.class_310) est chargée
 * trop tôt (avant que notre config Mixin ne soit prête), forçant un
 * retransform à chaud d'une classe déjà en cours d'usage — Mixin doit y
 * ajouter une méthode synthétique (le handler @Inject), ce que le JVM refuse
 * en retransform standard (`java.lang.ClassFormatError` confirmé en jeu).
 * Cette classe (GameRenderer) reste donc le point d'accroche pour la LOGIQUE
 * (chargée assez tard, jamais eu ce problème) — le dessin, lui, est déplacé
 * dans {@link GlobalUiPresentMixin}, qui cible Framebuffer.blitToScreen()
 * directement (classe instanciée au même moment que GameRenderer, jamais
 * retransformée à chaud non plus) et s'exécute donc APRÈS le blit Blaze3D.
 * Les deux Mixins partagent leur état via {@link GlobalUiRenderBridge}.
 *
 * Point d'entrée du menu : Right Shift, pollé même quand AUCUN écran n'est
 * ouvert (gameplay) — voir UiInputPoller.menuKeyPressed. N'ouvre le menu que
 * si currentScreen == null, pour ne jamais voler le focus d'un autre écran
 * déjà ouvert (inventaire, chat, etc.).
 */
@Mixin(targets = "net.minecraft.client.render.GameRenderer")
public abstract class GlobalUiRenderMixin {

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("TAIL"))
    private void la$onRenderTail(CallbackInfo ci) {
        try {
            // DOIT être la toute première chose exécutée ici — ce hook tourne
            // à CHAQUE frame dès le tout début du client, très probablement
            // avant même que TitleScreen.init() ne s'exécute une seule fois
            // (seul autre appelant de ensureExposed(), voir TitleScreenMixin).
            // Sans ça, ModuleRegistry.all() juste en dessous (et tout ce qui
            // en dépend : ModuleGroup, UiColor, etc.) se chargeait AVANT que
            // Knot ne sache que notre jar est SA PROPRE source de code — Knot
            // déléguait alors entièrement au classloader système ('app', via
            // -javaagent), qui définissait sa propre copie de ces classes.
            // Plus tard, le bytecode de nos @Inject (fusionné dans GameRenderer,
            // défini par Knot) redemande CES MÊMES classes — mais cette fois
            // via Knot, qui en définit une DEUXIÈME copie incompatible avec la
            // première (ClassCastException/LinkageError observés en jeu :
            // "ModuleGroup ... loader 'app' ... loader 'knot'" — le menu ne
            // s'ouvrait jamais sur 1.21.11 à cause de ça). this.getClass()
            // ici est le VRAI type à l'exécution (GameRenderer, fusionné par
            // Mixin), donc son classloader est bien Knot — jamais
            // GlobalUiRenderMixin.class.getClassLoader() (la classe DONOR,
            // chargée par l'agent isolé, PAS par Knot) comme utilisé par
            // erreur plus bas jusqu'ici.
            FabricKnotExposer.ensureExposed(this.getClass().getClassLoader());

            Object mc = GlobalUiRenderBridge.getMcInstance();
            if (mc == null) return;

            if (GlobalUiRenderBridge.inputPoller == null) {
                long handle = GlobalUiRenderBridge.getWindowHandle(mc);
                if (handle == 0L) return;
                GlobalUiRenderBridge.inputPoller = new UiInputPollerModern(handle, this.getClass().getClassLoader());
                // Force le chargement des modules intégrés (voir ModuleRegistry) dès
                // la première frame — sinon leurs éléments HUD (voir runtime.module)
                // ne s'enregistreraient qu'à la première ouverture du menu "YuyuFrame".
                ModuleRegistry.all();
                // Idem pour GlobalUiSettings — singleton à part, JAMAIS dans
                // ModuleRegistry (voir sa javadoc), donc jamais chargé par la
                // ligne ci-dessus. Sans ce forçage, sa classe (et donc
                // UiTheme.UI_SCALE/CARD_BG/ACCENT/etc., voir son
                // onConfigChanged()) ne se chargeait qu'à la première visite
                // de l'écran "Paramètres" — bug remonté : mauvaise taille
                // d'interface dès la première ouverture du menu (UI_SCALE
                // resté sur la valeur de départ codée en dur de UiTheme, pas
                // même le défaut de GlobalUiSettings), "corrigée" seulement
                // après être passé par Paramètres puis retour en arrière.
                GlobalUiSettings.INSTANCE.onConfigChanged();
            }
            GlobalUiRenderBridge.inputPoller.poll();

            // Logique de module continue, qu'un écran custom soit ouvert ou
            // non (ex: FOV forcé, voir FovModule) — équivalent de TickEvent
            // côté Forge, mais ici juste "cette même méthode s'exécute à
            // chaque frame" (suffisant, pas besoin d'un hook de tick séparé).
            //
            // BUG TROUVÉ : ce tickAll() n'avait pas son propre try/catch —
            // si un seul module échoue à son initialisation statique
            // (exception dans <clinit>, p.ex. constructeur d'un module),
            // ModuleRegistry est marqué en erreur PERMANENTE par la JVM
            // (NoClassDefFoundError relancé à chaque référence future) :
            // tickAll() levait alors à CHAQUE frame, pour toujours, avant
            // d'atteindre le test d'ouverture du menu juste en dessous (le
            // catch global de la méthode arrêtait tout AVANT ce test) — plus
            // AUCUNE touche n'ouvrait jamais le menu, silencieusement, sans
            // que rien d'autre ne plante. Isolé ici pour que le reste de la
            // méthode (l'ouverture du menu) continue quoi qu'il arrive au
            // moteur de modules.
            try {
                ModuleRegistry.tickAll();
            } catch (Throwable t) {
                LauncherLog.err("[LauncherAgent] ModuleRegistry.tickAll() a levé: " + t);
            }

            // Ouverture du menu SEULEMENT (le dessin réel, y compris le HUD
            // permanent affiché quand aucun écran n'est ouvert, se fait dans
            // GlobalUiPresentMixin — voir sa javadoc et celle de cette classe).
            Object currentScreen = GlobalUiRenderBridge.getCurrentScreen(mc);
            if (currentScreen == null && GlobalUiRenderBridge.inputPoller.menuKeyPressed) {
                LauncherLog.info("[LauncherAgent] DIAG4: menuKeyPressed détecté, ouverture UiMainMenuScreen");
                try {
                    GlobalUiRenderBridge.setScreen(mc, new UiMainMenuScreen(null));
                    LauncherLog.info("[LauncherAgent] DIAG4: setScreen(UiMainMenuScreen) appelé sans exception");
                } catch (Throwable t) {
                    LauncherLog.err("[LauncherAgent] DIAG4: setScreen(UiMainMenuScreen) a levé: " + t);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] GlobalUiRenderMixin: " + t);
        }
    }
}
