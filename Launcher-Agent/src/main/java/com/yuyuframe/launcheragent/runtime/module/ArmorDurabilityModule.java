package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiFont;
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

    // STATIC (pas un champ d'instance capturé par lambda) : le renderer doit
    // être construit et passé au super(...) de SingleHudModule AVANT que le
    // constructeur de cette classe n'ait fini — javac interdit toute
    // référence à `this` dans les arguments d'un appel super() (même pattern
    // que KeystrokesModule.RENDERER).
    private static final Renderer RENDERER = new Renderer();

    @ConfigDropdown(name = "Disposition", description = "Empilée verticalement (une ligne par emplacement) ou côte à côte horizontalement.",
        category = "Réglages", options = { "Verticale", "Horizontale" })
    public int layout = 0;

    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", HudAnchor.BOTTOM_RIGHT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
    }

    @Override
    public void onConfigChanged() {
        RENDERER.horizontal = layout == 1;
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
        private static final float TEXT_SCALE = 0.4f;
        /** Largeur mini du CONTENU avant que le joueur ne soit chargé (repli, comme FPS/Ping). */
        private static final float FALLBACK_WIDTH = 90f;
        /** Espace entre deux emplacements consécutifs en disposition horizontale. */
        private static final float ITEM_GAP = 10f;

        /** Mutable directement par ArmorDurabilityModule.onConfigChanged() — false = verticale (défaut). */
        volatile boolean horizontal = false;

        @Override
        public float[] naturalSize() {
            // Largeur = icône + espace + texte le plus large parmi les 5
            // emplacements, RECALCULÉE À CHAQUE FRAME (comme FPS/Ping, voir
            // HudElement.refreshSize()) — une largeur FIXE (120 en dur, choisie
            // au hasard) était soit trop large pour "363/363" (gros espace vide
            // à droite), soit trop étroite pour un objet à plus de 3 chiffres.
            float itemW = itemWidth(currentStacks());
            if (horizontal) return new float[]{ 5 * itemW + 4 * ITEM_GAP, ROW_H };
            return new float[]{ itemW, 5 * ROW_H };
        }

        private float itemWidth(Object[] stacks) {
            float maxTextW = 0f;
            for (Object stack : stacks) {
                String text = durabilityText(stack);
                if (text != null) maxTextW = Math.max(maxTextW, UiFont.REGULAR.textWidth(text, TEXT_SCALE));
            }
            return maxTextW > 0f ? ICON + GAP + maxTextW : FALLBACK_WIDTH;
        }

        private Object[] currentStacks() {
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
            return new Object[]{ helmet, chest, legs, boots, held };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            float icon = ICON * scale;
            Object[] stacks = currentStacks();

            if (horizontal) {
                float itemW = itemWidth(stacks) * scale, itemGap = ITEM_GAP * scale;
                float rowY = y + h - icon;
                float colX = x;
                for (Object stack : stacks) {
                    drawRow(renderer, colX, rowY, icon, stack, scale, vpWidth, vpHeight);
                    colX += itemW + itemGap;
                }
                return;
            }

            float rowH = ROW_H * scale;
            float rowY = y + h - icon;
            for (Object stack : stacks) {
                drawRow(renderer, x, rowY, icon, stack, scale, vpWidth, vpHeight);
                rowY -= rowH;
            }
        }

        private void drawRow(UiRenderer renderer, float x, float y, float iconSize, Object stack, float scale, int vpWidth, int vpHeight) {
            if (stack != null) {
                renderer.drawVanillaItemIcon(stack, x, y, iconSize, vpWidth, vpHeight);
            }
            String text = durabilityText(stack);
            if (text != null) {
                float textScale = TEXT_SCALE * scale;
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
