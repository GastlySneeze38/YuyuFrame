package com.yuyuframe.launcheragent.runtime.modules;

import com.yuyuframe.launcheragent.runtime.modules.builtin.ArmorDurabilityModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.CoordsModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.FovModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.FpsModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.HurtCamModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.KeystrokesModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.LowHealthTintModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.PingModule;
import com.yuyuframe.launcheragent.runtime.modules.builtin.PotionEffectsModule;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

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
 * joueur n'a jamais ouvert le menu "YuyuFrame".
 *
 * {@link #tickAll()}/{@link #renderOverlayAll} sont aussi appelés
 * génériquement depuis ces mêmes Mixin, UNE FOIS pour tous les modules — un
 * futur module qui a besoin d'une logique de jeu continue (voir FovModule)
 * ou d'un rendu plein écran (voir LowHealthTintModule) surcharge juste
 * {@link LauncherModule#onTick()}/{@link LauncherModule#onRenderOverlay},
 * jamais besoin de retoucher le Mixin.
 */
public final class ModuleRegistry {
    private ModuleRegistry() {}

    private static final List<LauncherModule> MODULES = new ArrayList<>();
    static {
        register(new FpsModule());
        register(new PingModule());
        register(new CoordsModule());
        register(new KeystrokesModule());
        register(new PotionEffectsModule());
        register(new ArmorDurabilityModule());
        register(new LowHealthTintModule());
        register(new FovModule());
        register(new HurtCamModule());
    }

    public static void register(LauncherModule module) { MODULES.add(module); }

    public static List<LauncherModule> all() { return Collections.unmodifiableList(MODULES); }

    public static LauncherModule get(String id) {
        for (LauncherModule m : MODULES) if (m.id.equals(id)) return m;
        return null;
    }

    public static void tickAll() {
        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try { m.onTick(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void renderOverlayAll(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try { m.onRenderOverlay(renderer, vpWidth, vpHeight); } catch (Throwable ignored) {}
            }
        }
    }
}
