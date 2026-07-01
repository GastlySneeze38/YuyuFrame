package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;

import java.lang.reflect.Method;

/** Port de PvP-Mod ArmorDurabilityConfig/ArmorDurabilityHud — sa propre carte, comme dans la référence. */
public final class ArmorDurabilityModule extends SingleHudModule {
    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", HudAnchor.BOTTOM_RIGHT, 8f, 8f, new ContentSource()));
    }

    /**
     * Durabilité armure + objet en main. PAS d'icône d'objet (l'original
     * utilise le vrai rendu vanilla RenderItem.renderItemAndEffectIntoGUI,
     * capacité absente de notre GraphicAPI) — texte uniquement.
     *
     * Slots : getArmorSlot(0..3) = bottes, jambières, plastron, casque
     * (LivingEntity, vérifié dans mappings-1.8.9.tiny — même ordre que
     * l'équivalent MCP getCurrentArmor utilisé par PvP-Mod).
     */
    private static final class ContentSource implements HudElement.ContentSource {
        private static final String[] LABELS = { "Casque", "Plastron", "Jambières", "Bottes", "Main" };
        // MÊME NOMBRE DE LIGNES que le vrai contenu (5) — pas juste "--" (1
        // ligne) : HudElement.naturalSize() appelle lines() à la construction
        // du module, AVANT qu'un joueur existe.
        private static final String[] FALLBACK = {
            "Casque: -", "Plastron: -", "Jambières: -", "Bottes: -", "Main: -"
        };

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
}
