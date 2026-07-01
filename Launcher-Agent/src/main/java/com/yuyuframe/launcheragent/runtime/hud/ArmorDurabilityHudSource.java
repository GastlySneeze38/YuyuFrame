package com.yuyuframe.launcheragent.runtime.hud;

import java.lang.reflect.Method;

/**
 * Durabilité armure + objet en main — port de PvP-Mod ArmorDurabilityHud.
 * PAS d'icône d'objet ici (l'original utilise le vrai rendu vanilla
 * {@code RenderItem.renderItemAndEffectIntoGUI}) : notre GraphicAPI ne sait
 * dessiner que des rectangles/texte pour l'instant — texte uniquement, même
 * compromis assumé que l'omission de la ligne "Biome" sur CoordsHudSource
 * (capacité de rendu manquante, pas donnée manquante).
 *
 * Slots : {@code getArmorSlot(0..3)} = bottes, jambières, plastron, casque
 * (LivingEntity, vérifié dans mappings-1.8.9.tiny — même ordre que
 * l'équivalent MCP {@code getCurrentArmor} utilisé par PvP-Mod).
 */
public final class ArmorDurabilityHudSource implements HudElement.ContentSource {

    private static final String[] LABELS = { "Casque", "Plastron", "Jambières", "Bottes", "Main" };
    private static final String[] FALLBACK = { "--" };

    @Override
    public String[] lines() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return FALLBACK;
            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
            if (player == null) return FALLBACK;

            Method getArmorSlot = McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getArmorSlot", int.class);
            Method getStackInHand = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStackInHand");
            if (getArmorSlot == null || getStackInHand == null) return FALLBACK;

            Object[] stacks = {
                getArmorSlot.invoke(player, 3), // casque
                getArmorSlot.invoke(player, 2), // plastron
                getArmorSlot.invoke(player, 1), // jambières
                getArmorSlot.invoke(player, 0), // bottes
                getStackInHand.invoke(player),  // main
            };

            String[] lines = new String[LABELS.length];
            for (int i = 0; i < LABELS.length; i++) {
                lines[i] = LABELS[i] + ": " + durabilityText(stacks[i]);
            }
            return lines;
        } catch (Throwable t) {
            return FALLBACK;
        }
    }

    private String durabilityText(Object stack) {
        if (stack == null) return "-";
        try {
            Method isDamageable = McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "isDamageable");
            if (isDamageable == null || !(boolean) isDamageable.invoke(stack)) return "-";
            int max = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getMaxDamage").invoke(stack);
            int dmg = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getDamage").invoke(stack);
            return (max - dmg) + "/" + max;
        } catch (Throwable t) {
            return "-";
        }
    }
}
