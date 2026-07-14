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

    // Défaut = main secondaire (0) : demandé explicitement par l'utilisateur
    // ("à partir des versions où on a une deuxième main, afficher la
    // deuxième main par défaut") — ignoré sur 1.8.9, qui n'a pas de main
    // secondaire (voir currentStacks(), handClass reste null sur ce bracket,
    // repli silencieux sur la main principale).
    @ConfigDropdown(name = "Main affichée", description = "Quelle main afficher pour l'objet en main (1.9+ uniquement — ignoré sur 1.8.9, qui n'a pas de main secondaire).",
        category = "Réglages", options = { "Main secondaire", "Main principale" })
    public int hand = 0;

    // "Vanilla" : VRAI sprite de case (assets/minecraft/textures/gui/sprites/
    // container/slot.png, 18x18 — trouvé via recherche GitHub/wiki Minecraft
    // à la demande explicite de l'utilisateur, confirmé présent tel quel dans
    // le vrai jar 26.1.2, PAS recréé) + VRAIE barre de durabilité vanilla
    // (DrawContext.drawItemBar/GuiGraphicsExtractor.itemBar), sans texte
    // custom — voir UiRenderer.drawVanillaItemIcon. Barre/case absentes sur
    // le bracket 1.20.4 (API différente, voir UiRenderer) — icône seule s'y
    // affiche quand même.
    //
    // Dans ce style, la position est FIXE (accolée à gauche de la vraie
    // hotbar, comme sur la capture fournie par l'utilisateur) et le HUD est
    // VERROUILLÉ (HudElement.locked=true, voir UiHudBox — plus de drag dans
    // l'éditeur) — demandé explicitement ("je ne veux plus que ça soit
    // considéré comme un HUD déplaçable dans ce mode"). x/y/w/h fournis par
    // le framework HUD sont donc IGNORÉS dans Renderer.draw() quand ce style
    // est actif (voir son code) ; anchor/offset repris tels quels au retour
    // au style "Personnalisé" (locked=false dérouille le drag normalement).
    @ConfigDropdown(name = "Style", description = "\"Personnalisé\" = position/taille libres, icône + texte de durabilité (défaut). \"Vanilla\" = position fixe façon hotbar, VRAIE case + barre de durabilité vanilla, HUD verrouillé (non déplaçable).",
        category = "Réglages", options = { "Personnalisé", "Vanilla" })
    public int style = 0;

    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", HudAnchor.BOTTOM_RIGHT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
    }

    @Override
    public void onConfigChanged() {
        RENDERER.horizontal = layout == 1;
        RENDERER.mainHand = hand == 1;
        RENDERER.vanillaStyle = style == 1;
        hudElement().locked = RENDERER.vanillaStyle;
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
        /** Mutable directement par ArmorDurabilityModule.onConfigChanged() — false = main secondaire (défaut), voir currentStacks(). */
        volatile boolean mainHand = false;
        /** Mutable directement par ArmorDurabilityModule.onConfigChanged() — false = style "Personnalisé" (défaut), voir draw()/drawRow(). */
        volatile boolean vanillaStyle = false;

        // ── Positionnement style "Vanilla" (voir draw()) ──────────────────
        // Dimensions de la VRAIE hotbar vanilla — stables depuis toujours,
        // pas de résolution dynamique nécessaire (182x22 GUI-pixels, case de
        // 18x18 avec 1px de marge/item, valeurs universellement connues du
        // modding Minecraft). Notre rangée de 5 cases (4 armure contiguës +
        // petit espace + main) est accolée à GAUCHE de cette hotbar, centrée
        // verticalement dessus — reproduit la disposition de la capture
        // utilisateur.
        private static final float HOTBAR_W_GUI = 182f;
        private static final float HOTBAR_H_GUI = 22f;
        private static final float VANILLA_SLOT_GUI = 18f;
        private static final float VANILLA_ICON_GUI = 16f;
        /** Espace (GUI-pixels) entre notre rangée et le bord gauche de la hotbar. */
        private static final float VANILLA_ROW_GAP_GUI = 4f;
        /** Espace (GUI-pixels) entre le groupe armure (4 cases contiguës) et la case main. */
        private static final float VANILLA_HAND_GAP_GUI = 6f;
        /** Décalage X (GUI-pixels, depuis le bord gauche de notre rangée) de chaque case — helmet/chest/legs/boots contiguës, puis main après l'espace. */
        private static final float[] VANILLA_SLOT_OFFSETS_GUI = {
            0f, VANILLA_SLOT_GUI, 2 * VANILLA_SLOT_GUI, 3 * VANILLA_SLOT_GUI,
            4 * VANILLA_SLOT_GUI + VANILLA_HAND_GAP_GUI
        };
        private static final float VANILLA_ROW_W_GUI =
            4 * VANILLA_SLOT_GUI + VANILLA_HAND_GAP_GUI + VANILLA_SLOT_GUI;

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

        /**
         * BUG TROUVÉ (audit modules, voir historique de session) : {@code
         * LivingEntity.getArmorSlot(int)} n'existe plus depuis la refonte
         * "Flattening" (~1.13) — remplacé par {@code getEquippedStack(EquipmentSlot)}
         * (enum, plus un entier magique). Essaie l'ancien chemin d'abord
         * (1.8.9, inchangé), sinon résout les 4 constantes d'armure de
         * l'enum {@code EquipmentSlot} (FEET/LEGS/CHEST/HEAD) dynamiquement.
         */
        private Object[] currentStacks() {
            Object helmet = null, chest = null, legs = null, boots = null, held = null;
            try {
                Object mc = McReflect.minecraftClient();
                if (mc != null) {
                    Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                    if (player != null) {
                        // BUG TROUVÉ (audit modules, voir historique de session) :
                        // getStackInHand() n'est PAS no-arg — prend un paramètre
                        // Hand (mappings 1.16.5 : (Laot;)Lbmb; b method_5998
                        // getStackInHand) — la recherche no-arg ne le trouvait
                        // donc jamais, "première main" toujours vide.
                        // 26.1+ : Hand→InteractionHand (package déplacé de
                        // net.minecraft.util vers net.minecraft.world, constante
                        // MAIN_HAND inchangée), getStackInHand→getItemInHand
                        // (vérifiés par javap sur le jar client 26.1.2 réel).
                        //
                        // Choix de main (voir champ mainHand, piloté par
                        // ArmorDurabilityModule.hand) : main secondaire par
                        // défaut, demandé explicitement par l'utilisateur.
                        // handClass reste null sur 1.8.9 (pas de Hand du tout
                        // avant 1.9) — cette branche entière est silencieusement
                        // sautée, "hand"/OFF_HAND n'existent nulle part pour ce
                        // bracket, cohérent avec "à partir des versions où on a
                        // une deuxième main".
                        Class<?> handClass = McReflect.yarnClass("net/minecraft/util/Hand", "net.minecraft.world.InteractionHand");
                        Method getStackInHand = handClass != null
                            ? McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getStackInHand", "getItemInHand", handClass)
                            : null;
                        if (getStackInHand != null && handClass != null) {
                            String constantName = mainHand ? "MAIN_HAND" : "OFF_HAND";
                            Object handConstant = McReflect.field(handClass, "net/minecraft/util/Hand", constantName).get(null);
                            held = getStackInHand.invoke(player, handConstant);
                        }

                        Method getArmorSlot = McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getArmorSlot", int.class);
                        if (getArmorSlot != null) {
                            helmet = getArmorSlot.invoke(player, 3);
                            chest = getArmorSlot.invoke(player, 2);
                            legs = getArmorSlot.invoke(player, 1);
                            boots = getArmorSlot.invoke(player, 0);
                        } else {
                            // 26.1+ : EquipmentSlot déplacé de net.minecraft.entity
                            // vers net.minecraft.world.entity (constantes HEAD/CHEST/
                            // LEGS/FEET inchangées), getEquippedStack→getItemBySlot.
                            Class<?> slotClass = McReflect.yarnClass("net/minecraft/entity/EquipmentSlot", "net.minecraft.world.entity.EquipmentSlot");
                            Method getEquippedStack = slotClass != null
                                ? McReflect.oneArgMethod(player.getClass(), "net/minecraft/entity/LivingEntity", "getEquippedStack", "getItemBySlot", slotClass)
                                : null;
                            if (slotClass != null && getEquippedStack != null) {
                                helmet = getEquippedStack.invoke(player, equipmentSlot(slotClass, "HEAD"));
                                chest = getEquippedStack.invoke(player, equipmentSlot(slotClass, "CHEST"));
                                legs = getEquippedStack.invoke(player, equipmentSlot(slotClass, "LEGS"));
                                boots = getEquippedStack.invoke(player, equipmentSlot(slotClass, "FEET"));
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            return new Object[]{ helmet, chest, legs, boots, held };
        }

        private Object equipmentSlot(Class<?> slotClass, String yarnConstantName) throws Exception {
            return McReflect.field(slotClass, "net/minecraft/entity/EquipmentSlot", yarnConstantName).get(null);
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            Object[] stacks = currentStacks();

            if (vanillaStyle) {
                drawVanillaHotbarRow(renderer, stacks, vpWidth, vpHeight);
                return;
            }

            float icon = ICON * scale;

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

        /**
         * Style "Vanilla" — position FIXE, ignore x/y/w/h fournis par le
         * framework HUD (HudElement.locked=true empêche de toute façon le
         * drag, voir ArmorDurabilityModule.onConfigChanged()). Calculée en
         * "GUI-pixels" (comme vanilla) puis convertie en pixels framebuffer
         * via {@link UiRenderer#guiScale} — accolée à gauche de la vraie
         * hotbar, centrée verticalement dessus, reproduisant la disposition
         * de la capture utilisateur (4 cases d'armure contiguës, petit
         * espace, case main).
         */
        private void drawVanillaHotbarRow(UiRenderer renderer, Object[] stacks, int vpWidth, int vpHeight) {
            float guiScale = UiRenderer.guiScale(vpWidth);
            float guiWidth = vpWidth / guiScale;

            float hotbarLeftGui = (guiWidth - HOTBAR_W_GUI) / 2f;
            float rowLeftGui = hotbarLeftGui - VANILLA_ROW_GAP_GUI - VANILLA_ROW_W_GUI;
            // Centré verticalement sur la hauteur de la hotbar (22 GUI-px),
            // case 18x18 → 2 GUI-px de marge en haut ET en bas.
            float slotTopGui = (HOTBAR_H_GUI - VANILLA_SLOT_GUI) / 2f;
            float iconTopGui = slotTopGui + (VANILLA_SLOT_GUI - VANILLA_ICON_GUI) / 2f;
            // guiHeight - iconTopGui - taille = bord BAS de l'icône en
            // GUI-space (origine haut) ; converti en framebuffer (origine
            // bas, voir UiRenderer.drawVanillaItemIcon) : hotbar flush en
            // bas d'écran (y_gui=guiHeight au bord bas de la hotbar), donc
            // le calcul se simplifie à une distance FIXE depuis le bas de
            // l'écran, indépendante de guiHeight — voir dérivation dans
            // l'historique de session.
            float iconBottomFb = (HOTBAR_H_GUI - iconTopGui - VANILLA_ICON_GUI) * guiScale;

            for (int i = 0; i < stacks.length && i < VANILLA_SLOT_OFFSETS_GUI.length; i++) {
                Object stack = stacks[i];
                if (stack == null) continue;
                float iconLeftGui = rowLeftGui + VANILLA_SLOT_OFFSETS_GUI[i] + (VANILLA_SLOT_GUI - VANILLA_ICON_GUI) / 2f;
                float iconLeftFb = iconLeftGui * guiScale;
                renderer.drawVanillaItemIcon(stack, iconLeftFb, iconBottomFb, VANILLA_ICON_GUI * guiScale, vpWidth, vpHeight, true);
            }
        }

        private void drawRow(UiRenderer renderer, float x, float y, float iconSize, Object stack, float scale, int vpWidth, int vpHeight) {
            if (stack == null) return;
            renderer.drawVanillaItemIcon(stack, x, y, iconSize, vpWidth, vpHeight);
            String text = durabilityText(stack);
            if (text != null) {
                float textScale = TEXT_SCALE * scale;
                renderer.drawText(text, x + iconSize + GAP * scale, y + iconSize * 0.35f, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            }
        }

        private String durabilityText(Object stack) {
            if (stack == null) return null;
            try {
                // 26.1+ : isDamageable→isDamageableItem, getDamage→getDamageValue
                // (vérifiés par javap sur le jar client 26.1.2 réel) ; getMaxDamage inchangé.
                Method isDamageable = McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "isDamageable", "isDamageableItem");
                if (isDamageable == null || !(boolean) isDamageable.invoke(stack)) return null;
                int max = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getMaxDamage").invoke(stack);
                int dmg = (int) McReflect.noArgMethod(stack.getClass(), "net/minecraft/item/ItemStack", "getDamage", "getDamageValue").invoke(stack);
                return (max - dmg) + "/" + max;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
