package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Équivalent AppleSkin — révèle Saturation et Exhaustion, deux valeurs
 * normalement CACHÉES par l'UI vanilla (seule la barre de faim, par
 * demi-coeurs entiers, y est visible).
 *
 * {@code PlayerEntity.getHungerManager()} (Yarn) / réel Mojang 26.1+
 * {@code Player.getFoodData()} → {@code HungerManager}/{@code FoodData}
 * (vérifié par javap sur le jar client 26.1.2 réel). {@code
 * getSaturationLevel()} inchangé des deux côtés. Aucun getter pour
 * l'exhaustion sur AUCUN bracket (ni Yarn ni réel, seulement {@code
 * addExhaustion(float)}) — lecture directe du champ {@code exhaustion}
 * (Yarn) / {@code exhaustionLevel} (réel).
 */
public final class SaturationModule extends SingleHudModule {

    public SaturationModule() {
        super("saturation", "Saturation (AppleSkin)", "Affiche la saturation et l'exhaustion, cachées par l'UI vanilla", false,
            new HudElement("saturation", "Saturation", HudAnchor.TOP_LEFT, 8f, 72f, new ContentSource()));
        hudElement().textColor = new UiColor(120, 220, 140, 255);
    }

    private static final class ContentSource implements HudElement.ContentSource {
        private static boolean errorLogged;

        @Override
        public String[] lines() {
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return fallback();
                Field playerField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player");
                if (playerField == null) return fallback();
                Object player = playerField.get(mc);
                if (player == null) return fallback();

                Method getHungerManager = McReflect.noArgMethod(player.getClass(),
                    "net/minecraft/entity/player/PlayerEntity", "getHungerManager", "getFoodData");
                if (getHungerManager == null) return fallback();
                Object hungerManager = getHungerManager.invoke(player);
                if (hungerManager == null) return fallback();

                Method getSaturation = McReflect.noArgMethod(hungerManager.getClass(),
                    "net/minecraft/entity/player/HungerManager", "getSaturationLevel");
                float saturation = getSaturation != null ? (float) getSaturation.invoke(hungerManager) : Float.NaN;

                Field exhaustionField = McReflect.field(hungerManager.getClass(),
                    "net/minecraft/entity/player/HungerManager", "exhaustion", "exhaustionLevel");
                float exhaustion = exhaustionField != null ? exhaustionField.getFloat(hungerManager) : Float.NaN;

                String satText = Float.isNaN(saturation) ? "--" : String.format(Locale.ROOT, "%.1f", saturation);
                String exhText = Float.isNaN(exhaustion) ? "--" : String.format(Locale.ROOT, "%.2f", exhaustion);
                return new String[]{ "Saturation : " + satText, "Exhaustion : " + exhText };
            } catch (Throwable t) {
                if (!errorLogged) {
                    errorLogged = true;
                    LauncherLog.err("[SaturationModule] lines: " + t);
                }
                return fallback();
            }
        }

        private String[] fallback() {
            return new String[]{ "Saturation : --", "Exhaustion : --" };
        }
    }
}
