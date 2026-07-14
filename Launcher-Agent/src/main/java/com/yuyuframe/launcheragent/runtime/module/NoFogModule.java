package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.lang.reflect.Field;

/**
 * Désactive tout le brouillard (distance de rendu, eau, lave, ténèbres,
 * cécité, brouillard atmosphérique...) — AUCUN Mixin nécessaire : le jeu
 * expose déjà lui-même un interrupteur global, {@code FogRenderer.
 * fogEnabled} (champ statique), vérifié PAR BYTECODE (javap -c sur le jar
 * client 26.1.2 réel) comme la garde EN TÊTE de {@code
 * FogRenderer.getBuffer(FogMode)} : si {@code false}, la méthode renvoie
 * un buffer GPU vide (aucune donnée de brouillard envoyée au shader) AVANT
 * même de regarder le mode/l'environnement de brouillard actif — donc un
 * VRAI interrupteur maître, pas juste un des multiples types de brouillard.
 * Présent tel quel dans {@code mappings.tiny} (Yarn nommé
 * {@code FogRenderer.fogEnabled}/{@code toggleFog()}), donc couvert par les
 * mêmes noms sur 1.16.5/1.20.4/1.21.4 — absent sur 1.8.9 (ancien
 * brouillard GL fixe, aucun mapping) : dégrade proprement, ne fait rien.
 *
 * Même découpage force/restore que {@link FullbrightModule} : {@code
 * onTick()} force {@code false} à CHAQUE frame (au cas où autre chose,
 * comme un changement de dimension, le remettrait à {@code true}),
 * {@code onEnabledChanged(false)} restaure {@code true} (valeur par défaut
 * vanilla, confirmée dans le bloc {@code <clinit>} par javap) à la
 * désactivation.
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
