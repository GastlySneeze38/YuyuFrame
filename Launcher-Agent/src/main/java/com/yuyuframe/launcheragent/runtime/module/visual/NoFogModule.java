package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.renderer.fog.FogData;

import java.lang.reflect.Field;

/**
 * Désactive tout le brouillard (distance de rendu, eau, lave, ténèbres,
 * cécité, brouillard atmosphérique...).
 *
 * BUG TROUVÉ (retour utilisateur : "ne marche pas tellement") : le
 * mécanisme ci-dessous ({@code FogRenderer.fogEnabled}, interrupteur maître
 * de l'ANCIENNE architecture 1.16.5-1.21.11) ne gouverne plus rien sur
 * 26.1+ — depuis la refonte du brouillard en système {@code FogEnvironment}
 * (une implémentation par SOURCE : Atmospheric/Water/Lava/PowderedSnow/
 * Blindness/Darkness, chacune remplissant elle-même les distances de
 * {@code FogData}), ce flag global n'est simplement plus lu par le nouveau
 * pipeline — d'où l'effet partiel/inexistant. Complété par 6 Mixins dédiés
 * (même technique que {@code ClearVisionModule}, voir
 * {@code AtmosphericFogEnvironmentMixin261}/{@code WaterFogEnvironmentMixin261}/
 * etc. — recherche inspirée de mods open-source type "No Fog"/Sodium :
 * repousser les distances de coupure du brouillard très loin plutôt que de
 * chercher un interrupteur global unique, plus robuste face à une
 * architecture par environnement) : {@code AtmosphericFogEnvironmentMixin261}
 * (brouillard de distance normal — LE morceau manquant le plus visible),
 * {@code BlindnessFogEnvironmentMixin261}/{@code DarknessFogEnvironmentMixin261}
 * (effets de statut), et {@code WaterFogEnvironmentMixin261}/{@code
 * LavaFogEnvironmentMixin261}/{@code PowderedSnowFogEnvironmentMixin261}
 * (partagés avec ClearVisionModule, désormais déclenchés par CE module
 * aussi — voir leur javadoc commune).
 *
 * Le mécanisme {@code fogEnabled} ci-dessous reste utile tel quel sur
 * 1.16.5/1.20.4/1.21.4/1.21.11 (architecture pré-refonte, ce champ y est
 * toujours l'interrupteur réel) — inchangé pour ces brackets. Même
 * découpage force/restore que {@link FullbrightModule} : {@code onTick()}
 * force {@code false} à chaque frame, {@code onEnabledChanged(false)}
 * restaure {@code true} à la désactivation.
 *
 * BUG TROUVÉ (test utilisateur, 1.21.4 : "ne marche pas") : le nom Yarn de
 * la classe déclarante ({@code net/minecraft/client/render/fog/FogRenderer})
 * n'existe QUE depuis 1.21.11 — vérifié directement dans
 * {@code mappings/yarn-1.21.4-mergedv2.jar} : zéro occurrence de
 * "FogRenderer", classe et package n'existent pas encore sous ce nom. Sur
 * 1.21.4 (et vraisemblablement 1.16.5/1.20.4, jamais revérifiés
 * individuellement), la classe s'appelle encore {@code
 * net/minecraft/client/render/BackgroundRenderer} (renommée "FogRenderer"
 * seulement PLUS TARD) — champ {@code fogEnabled} confirmé présent dessus
 * avec le même nom exact. Même piège que "blit"/"drawTexture" documenté
 * dans module-bracket-audit.md : ne jamais supposer qu'un chemin de classe
 * Yarn est stable entre versions sans le vérifier dans les mappings de
 * CHAQUE bracket concerné.
 */
public final class NoFogModule extends LauncherModule {

    private static final float FAR = 1_000_000f;

    private static boolean errorLogged;

    public NoFogModule() {
        super("no-fog", "Sans brouillard", "Désactive tout le brouillard (distance, eau, lave, ténèbres, cécité...)", false,
            HookPoint.FOG_SETUP_ATMOSPHERIC, HookPoint.FOG_SETUP_WATER, HookPoint.FOG_SETUP_LAVA,
            HookPoint.FOG_SETUP_POWDERED_SNOW, HookPoint.FOG_SETUP_BLINDNESS, HookPoint.FOG_SETUP_DARKNESS);
        // 26.1.2 — voir apimixin/v26_1/fog/ : les 6 environnements dispatchent
        // ici tel quel, plus aucune vérification "no-fog OU clear-vision"
        // dupliquée dans chaque fichier Mixin (voir FogSetupWaterMixin261).
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_ATMOSPHERIC, this::pushFogFar);
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_WATER, this::pushFogFar);
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_LAVA, this::pushFogFar);
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_POWDERED_SNOW, this::pushFogFar);
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_BLINDNESS, this::pushFogFar);
        VanillaHookRegistry.register(HookPoint.FOG_SETUP_DARKNESS, this::pushFogFar);
    }

    private boolean pushFogFar(Object ctx) {
        if (!isEnabled() || !(ctx instanceof FogData)) return false;
        FogData fogData = (FogData) ctx;
        fogData.environmentalStart = FAR;
        fogData.environmentalEnd = FAR * 2f;
        fogData.renderDistanceStart = FAR;
        fogData.renderDistanceEnd = FAR * 2f;
        return true;
    }

    @Override
    public void onTick() {
        try {
            Field fogEnabledField = fogEnabledField();
            if (fogEnabledField == null) return;
            fogEnabledField.set(null, false);
        } catch (Throwable t) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[NoFogModule] onTick: " + t);
            }
        }
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        if (enabled) return;
        try {
            Field fogEnabledField = fogEnabledField();
            if (fogEnabledField != null) fogEnabledField.set(null, true);
        } catch (Throwable ignored) {
        }
    }

    private static volatile Field cachedFogEnabledField;
    private static volatile boolean fogEnabledResolveFailed;

    private Field fogEnabledField() {
        if (cachedFogEnabledField != null) return cachedFogEnabledField;
        if (fogEnabledResolveFailed) return null;
        // 1.21.11+ (nom Yarn actuel) essayé en premier, repli sur
        // BackgroundRenderer (1.16.5-1.21.4, ancien nom Yarn — voir bug
        // ci-dessus) puis sur le nom réel Mojang 26.1.2.
        Class<?> fogRendererClass = McReflect.yarnClass("net/minecraft/client/render/fog/FogRenderer");
        String yarnClassPath = "net/minecraft/client/render/fog/FogRenderer";
        if (fogRendererClass == null) {
            fogRendererClass = McReflect.yarnClass("net/minecraft/client/render/BackgroundRenderer");
            yarnClassPath = "net/minecraft/client/render/BackgroundRenderer";
        }
        if (fogRendererClass == null) {
            fogRendererClass = McReflect.yarnClass("net/minecraft/client/render/fog/FogRenderer", "net.minecraft.client.renderer.fog.FogRenderer");
        }
        if (fogRendererClass == null) {
            fogEnabledResolveFailed = true;
            return null;
        }
        cachedFogEnabledField = McReflect.field(fogRendererClass, yarnClassPath, "fogEnabled");
        if (cachedFogEnabledField == null) fogEnabledResolveFailed = true;
        return cachedFogEnabledField;
    }
}
