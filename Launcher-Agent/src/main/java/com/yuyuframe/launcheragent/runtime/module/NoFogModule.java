package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

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
 */
public final class NoFogModule extends LauncherModule {

    private static boolean errorLogged;

    public NoFogModule() {
        super("no-fog", "Sans brouillard", "Désactive tout le brouillard (distance, eau, lave, ténèbres, cécité...)", false);
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

    private Field fogEnabledField() {
        Class<?> fogRendererClass = McReflect.yarnClass("net/minecraft/client/render/fog/FogRenderer", "net.minecraft.client.renderer.fog.FogRenderer");
        if (fogRendererClass == null) return null;
        return McReflect.field(fogRendererClass, "net/minecraft/client/render/fog/FogRenderer", "fogEnabled");
    }
}
