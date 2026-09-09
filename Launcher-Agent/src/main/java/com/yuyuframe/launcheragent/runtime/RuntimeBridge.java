package com.yuyuframe.launcheragent.runtime;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apimixin.AgentBridge;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.command.ClientCommandRegistry;
import com.yuyuframe.launcheragent.runtime.ipc.ReadyEventSignal;
import com.yuyuframe.launcheragent.runtime.ui.GlobalUiSettings;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;

/**
 * Ce que la couche haute fournit aux mixins hub — implémentation de
 * {@link AgentBridge}, chargée PAR NOM depuis le classloader du code tissé
 * (voir la javadoc d'{@code AgentBridge} pour le pourquoi).
 *
 * <h2>Le seul point d'entrée descendant</h2>
 *
 * Avant (2026-09-09), les trois mixins hub appelaient directement huit classes
 * de {@code runtime/}. Tout est passé ici : {@code apimixin} ne nomme plus
 * qu'une interface qu'il définit lui-même, et cette classe est la seule de
 * {@code runtime/} que la couche d'injection atteint — par son nom, jamais par
 * un import.
 *
 * <p>Chaque méthode est un report FIDÈLE de ce que le mixin faisait, try/catch
 * compris : le comportement en cas d'échec ne change pas, seule la direction de
 * la dépendance change. C'est délibéré — un déplacement de frontière ne doit
 * jamais changer ce qui se passe en jeu au passage.
 *
 * <p>Instanciée par réflexion : le constructeur public sans argument est requis
 * par {@code AgentBridge.Holder}, il n'est appelé de nulle part ailleurs.
 */
public final class RuntimeBridge implements AgentBridge {

    public RuntimeBridge() {}

    @Override
    public void bootstrap() {
        ModuleRegistry.all();
        GlobalUiSettings.INSTANCE.onConfigChanged();

        // Phase 4.5 — le commentaire d'origine du mixin reste valable : ceci
        // DOIT être touché depuis le classloader du jeu, jamais depuis
        // premain0() (classloader système), sous peine de LinkageError sur
        // VanillaHookRegistry. La résolution par nom d'AgentBridge conserve
        // exactement cette propriété.
        try {
            ClientCommandRegistry.bootstrap();
        } catch (Throwable t) {
            LauncherLog.err("[RuntimeBridge] ClientCommandRegistry.bootstrap() a levé: " + t);
        }

        // Audit du catalogue statique — ICI parce que c'est le premier instant
        // où TOUS les enregistrements ont eu lieu (modules ci-dessus +
        // ClientCommandRegistry juste avant). Sans lui, les déclarations
        // LauncherModule.hookPoints dérivent en silence, et une gate qui s'y
        // appuie écarterait des mixins pourtant nécessaires.
        try {
            VanillaHookRegistry.auditDeclarations(ModuleRegistry.declaredHookPoints());
        } catch (Throwable t) {
            LauncherLog.err("[RuntimeBridge] audit HookPoint a levé: " + t);
        }
    }

    @Override
    public void tick() {
        try {
            ModuleRegistry.tickAll();
        } catch (Throwable t) {
            LauncherLog.err("[RuntimeBridge] ModuleRegistry.tickAll() a levé: " + t);
        }
    }

    @Override
    public boolean hudHidden() {
        return HudOverlayRenderer.vanillaHudHidden();
    }

    @Override
    public void renderOverlay(Object uiRenderer, int fbWidth, int fbHeight) {
        // Le type réel est reconstitué ICI, où apigraphic est en dessous —
        // apimixin transportait l'objet sans le nommer.
        if (!(uiRenderer instanceof UiRenderer)) return;
        ModuleRegistry.renderOverlayAll((UiRenderer) uiRenderer, fbWidth, fbHeight);
    }

    @Override
    public Object mainMenuScreen() {
        return new UiMainMenuScreen(null);
    }

    @Override
    public boolean hasPendingNavigation(Object screen) {
        return screen instanceof UiScreenBase && ((UiScreenBase) screen).hasPendingNavigation();
    }

    @Override
    public Object consumePendingNavigation(Object screen) {
        if (!(screen instanceof UiScreenBase)) return null;
        return ((UiScreenBase) screen).consumePendingNavigation();
    }

    @Override
    public void signalReady(String eventProperty) {
        ReadyEventSignal.signalOnce(eventProperty);
    }
}
