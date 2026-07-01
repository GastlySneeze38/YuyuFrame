package com.yuyuframe.launcheragent.runtime.modules;

import com.yuyuframe.launcheragent.runtime.modules.builtin.VanillaHudModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Registre global des modules — {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen}
 * construit sa grille de cartes UNIQUEMENT à partir de {@link #all()}, plus
 * aucune donnée factice codée en dur dans l'écran lui-même : un nouveau
 * module s'ajoute ICI (ou s'auto-enregistre via {@link #register}), jamais
 * dans le code de l'écran.
 *
 * Chargée paresseusement (comme toute classe Java) au premier accès — forcée
 * dès la première frame par GlobalUiRenderMixin/GlobalUiRenderMixin189, pour
 * que les éléments HUD des modules intégrés soient déjà présents même si le
 * joueur n'a jamais ouvert le menu "YuyuFrame" (voir VanillaHudModule).
 */
public final class ModuleRegistry {
    private ModuleRegistry() {}

    private static final List<LauncherModule> MODULES = new ArrayList<>();
    static {
        register(new VanillaHudModule());
    }

    public static void register(LauncherModule module) { MODULES.add(module); }

    public static List<LauncherModule> all() { return Collections.unmodifiableList(MODULES); }
}
