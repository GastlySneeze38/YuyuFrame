package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Method;

/**
 * Port de PvP-Mod ArmorDurabilityConfig/ArmorDurabilityHud — sa propre carte,
 * comme dans la référence, AVEC la vraie icône d'objet vanilla cette fois
 * (voir UiRenderer.drawVanillaItemIcon — premier module à utiliser ce pont
 * vers le rendu d'item fixed-function de vanilla, capacité qui manquait
 * avant, d'où le texte seul de la passe précédente).
 *
 * Slots : getArmorSlot(0..3) = bottes, jambières, plastron, casque
 * (LivingEntity, vérifié dans mappings-1.8.9.tiny — même ordre que
 * l'équivalent MCP getCurrentArmor utilisé par PvP-Mod).
 */
public final class ArmorDurabilityModule extends SingleHudModule {
    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", HudAnchor.BOTTOM_RIGHT, 8f, 8f,
                (HudElement.CustomRenderer) new Renderer()));
    }

    /** Rendu personnalisé (voir HudElement.CustomRenderer) : une icône à côté d'un texte, par ligne, ne rentre pas dans le modèle "une ligne de texte" de ContentSource. */
    private static final class Renderer implements HudElement.CustomRenderer {
        // 28, pas 16 : UiRenderer.drawVanillaItemIcon reçoit maintenant une
        // taille RÉELLE en pixels physiques (voir sa javadoc) — 16 tout rond
        // rendait l'icône minuscule (notre pipeline ignore volontairement le
        // "GUI Scale" de vanilla partout ailleurs, donc aucune compensation
        // automatique). Boîte agrandie en conséquence (NATURAL_WIDTH/ROW_H).
        private static final float ICON = 28f;
        private static final float GAP = 6f;
        private static final float ROW_H = 30f;
        private static final float PADDING = 5f;
        private static final float NATURAL_WIDTH = 130f;

        @Override
        public float[] naturalSize() {
            return new float[]{ NATURAL_WIDTH, 5 * ROW_H + 2 * PADDING };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            float icon = ICON * scale, gap = GAP * scale, rowH = ROW_H * scale, padding = PADDING * scale;

            Object helmet = null, chest = null, legs = null, boots = null, held = null;
            try {
                Object mc = McReflect.minecraftClient();
                if (mc != null) {
                    Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                    if (player != null) {
                        Method getArmorSlot = McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getArmorSlot", int.class);
                        Method getStackInHand = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStackInHand");
                        if (getArmorSlot != null && getStackInHand != null) {
                            helmet = getArmorSlot.invoke(player, 3);
                            chest = getArmorSlot.invoke(player, 2);
                            legs = getArmorSlot.invoke(player, 1);
                            boots = getArmorSlot.invoke(player, 0);
                            held = getStackInHand.invoke(player);
                        }
                    }
                }
            } catch (Throwable ignored) {}

            Object[] stacks = { helmet, chest, legs, boots, held };
            float rowY = y + h - padding - icon;
            for (Object stack : stacks) {
                drawRow(renderer, x + padding, rowY, icon, stack, scale, vpWidth, vpHeight);
                rowY -= rowH;
            }
        }

        private void drawRow(UiRenderer renderer, float x, float y, float iconSize, Object stack, float scale, int vpWidth, int vpHeight) {
            if (stack != null) {
                renderer.drawVanillaItemIcon(stack, x, y, iconSize, vpWidth, vpHeight);
            }
            String text = durabilityText(stack);
            if (text != null) {
                float textScale = 0.4f * scale;
                renderer.drawText(text, x + iconSize + GAP * scale, y + iconSize * 0.35f, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            }
        }

        private String durabilityText(Object stack) {
            if (stack == null) return null;
            try {
                Method isDamageable = McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "isDamageable");
                if (isDamageable == null || !(boolean) isDamageable.invoke(stack)) return null;
                int max = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getMaxDamage").invoke(stack);
                int dmg = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getDamage").invoke(stack);
                return (max - dmg) + "/" + max;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
