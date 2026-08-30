package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigDropdown;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.module.visual.CrosshairModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

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
                (HudElement.CustomRenderer) RENDERER),
            HookPoint.HUD_EXTRACT_ARMOR);
        iconUrl = icons8("shield");

        // BUG TROUVÉ (audit modules 2026-08-25, §19, retour utilisateur :
        // "l'armure/durabilité [...] chevauche le HUD vanilla") — ce module
        // dessinait sa propre rangée d'armure SANS jamais supprimer la
        // rangée vanilla native (mixin HudExtractArmorMixin261/HookPoint
        // HUD_EXTRACT_ARMOR déjà existants, simplement jamais consultés ici,
        // faute d'un constructeur SingleHudModule capable de relayer un
        // HookPoint — voir sa javadoc). Même câblage que CrosshairModule :
        // dispatch()==true fait sauter le rendu vanilla dans
        // HudExtractArmorMixin261 tant que CE module est actif — style
        // "Vanilla" INCLUS (il double lui aussi l'armure, jamais la main,
        // voir javadoc de VANILLA_SLOT_OFFSETS_GUI).
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_ARMOR, ctx -> isEnabled());
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

        // AUDIT PERF (demandé explicitement par l'utilisateur) : naturalSize()/
        // hasContent()/draw() appelaient CHACUN currentStacks() séparément —
        // 3 recalculs complets (donc 3x ~6-10 invocations de réflexion) par
        // frame pour la MÊME donnée, alors que HudOverlayRenderer garantit
        // TOUJOURS refreshSize() (→ naturalSize()) juste avant
        // HudPanelRenderer.draw() (→ hasContent() PUIS draw()) — voir leurs
        // javadoc respectives. naturalSize() (premier appel du cycle,
        // TOUJOURS déclenché) rafraîchit ce cache ; hasContent()/draw()
        // réutilisent la MÊME valeur au lieu de recalculer.
        private Object[] cachedStacks;

        private Object[] currentStacks() {
            if (cachedStacks == null) cachedStacks = computeStacks();
            return cachedStacks;
        }

        // ── Positionnement style "Vanilla" (voir draw()) ──────────────────
        // Dimensions de la VRAIE hotbar vanilla — stables depuis toujours,
        // pas de résolution dynamique nécessaire (182x22 GUI-pixels). Case =
        // sprite "hud/hotbar_offhand_left" réel (29x24, voir UiRenderer —
        // MÊME sprite que la vraie case de main secondaire vanilla, décodage
        // manuel du PNG confirmant une icône 16x16 à l'offset (3,4) en son
        // sein). Notre rangée de 4 cases d'ARMURE est accolée à GAUCHE de la
        // hotbar, centrée verticalement dessus.
        //
        // BUG TROUVÉ (retour utilisateur) : PAS de case "main" ici — l'objet
        // en main secondaire est DÉJÀ affiché nativement par vanilla
        // lui-même, juste à gauche de la hotbar (Gui.HOTBAR_OFFHAND_LEFT_SPRITE/
        // HOTBAR_OFFHAND_RIGHT_SPRITE, confirmé par trace bytecode de
        // Gui.extractItemHotbar — voir historique de session). Le dupliquer
        // ici aurait affiché DEUX fois le même objet. Seule l'armure (jamais
        // affichée par vanilla dans le HUD) a besoin de ce style — le champ
        // "Main affichée"/style "Personnalisé" restent inchangés pour qui
        // veut quand même voir l'objet en main dans SA propre disposition.
        private static final float HOTBAR_W_GUI = 182f;
        private static final float HOTBAR_H_GUI = 22f;
        private static final float VANILLA_SPRITE_W_GUI = 29f;
        private static final float VANILLA_SPRITE_H_GUI = 24f;
        // BUG TROUVÉ (retour utilisateur, capture à l'appui) : écart trop
        // grand entre les cases + asymétrie autour de la case de main
        // secondaire. Cause : le sprite fait 29 GUI-px de large mais sa
        // partie VISIBLE (coin arrondi opaque, voir décodage PNG dans
        // UiRenderer) n'occupe que les 22 premiers — le reste (x=22..29) est
        // transparent, prévu pour DÉBORDER sur l'élément suivant sans se voir
        // (c'est comme ça que vanilla lui-même accole cette case à la
        // hotbar). J'espaçais par la largeur TOTALE du sprite (29, +2 de
        // marge) au lieu de sa partie visible (22) — d'où les vides en trop
        // et le déséquilibre. Un espacement/réserve basé sur 22 (la boîte
        // visible) laisse les débordements transparents se chevaucher
        // silencieusement, exactement comme vanilla le fait.
        private static final float VANILLA_BOX_W_GUI = 22f;
        private static final float VANILLA_ICON_GUI = 16f;
        /** Offset de l'icône 16x16 depuis le coin haut-gauche du sprite 29x24 (voir UiRenderer, SLOT_SPRITE_ICON_DX/DY). */
        private static final float VANILLA_ICON_OFFSET_X_GUI = 3f;
        private static final float VANILLA_ICON_OFFSET_Y_GUI = 4f;
        /** Distance (GUI-pixels) entre les origines de deux cases consécutives — la largeur de la boîte VISIBLE (22), cases adjacentes sans vide (voir javadoc ci-dessus). */
        private static final float VANILLA_SLOT_PITCH_GUI = VANILLA_BOX_W_GUI;
        /** Espace (GUI-pixels) entre notre rangée et le bord gauche de la hotbar (ou de la boîte visible de la case main secondaire vanilla si visible, voir vanillaOffhandVisibleOnLeft()). */
        private static final float VANILLA_ROW_GAP_GUI = 2f;
        /** Largeur (GUI-pixels) à réserver en plus quand vanilla dessine SA PROPRE case de main secondaire à gauche de la hotbar (boîte visible, même logique que VANILLA_SLOT_PITCH_GUI). */
        private static final float VANILLA_OFFHAND_RESERVED_GUI = VANILLA_BOX_W_GUI + VANILLA_ROW_GAP_GUI;
        /** Décalage X (GUI-pixels, depuis le bord gauche de notre rangée) de chaque case d'armure. */
        private static final float[] VANILLA_SLOT_OFFSETS_GUI = {
            0f, VANILLA_SLOT_PITCH_GUI, 2 * VANILLA_SLOT_PITCH_GUI, 3 * VANILLA_SLOT_PITCH_GUI
        };
        private static final float VANILLA_ROW_W_GUI = 3 * VANILLA_SLOT_PITCH_GUI + VANILLA_SPRITE_W_GUI;

        /**
         * Style "Vanilla" : gère sa propre position/fond, voir
         * ArmorDurabilityModule.onConfigChanged() et drawVanillaHotbarRow().
         */
        @Override
        public boolean skipBackground() {
            return vanillaStyle;
        }

        /**
         * Faux si RIEN à afficher — style "Vanilla" : les 4 pièces d'armure
         * uniquement (pas la main, jamais affichée dans ce style, voir
         * VANILLA_SLOT_OFFSETS_GUI). Style "Personnalisé" : les 5 emplacements
         * (armure + main). Demandé explicitement par l'utilisateur ("quand il
         * y a rien à afficher... le background ne s'affiche pas").
         */
        @Override
        public boolean hasContent() {
            Object[] stacks = currentStacks();
            int count = vanillaStyle ? VANILLA_SLOT_OFFSETS_GUI.length : stacks.length;
            for (int i = 0; i < count && i < stacks.length; i++) {
                if (stacks[i] != null) return true;
            }
            return false;
        }

        @Override
        public float[] naturalSize() {
            // TOUJOURS le premier appel du cycle HUD de ce frame (voir
            // HudOverlayRenderer.render()/renderPersistent(), qui appelle
            // refreshSize() avant HudPanelRenderer.draw()) — rafraîchit
            // explicitement le cache ICI, hasContent()/draw() (plus bas)
            // réutilisent ensuite currentStacks() sans recalculer (voir
            // javadoc de cachedStacks).
            cachedStacks = computeStacks();

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
         *
         * AUDIT PERF (demandé explicitement par l'utilisateur, "gratter des
         * fps 26.1.2") : recalcule à chaque appel — ~6-10 invocations de
         * réflexion (joueur, résolution Hand/EquipmentSlot, 4 pièces
         * d'armure, main). Appelé DIRECTEMENT ici uniquement par
         * {@link #currentStacks()} (cache), jamais ailleurs — voir sa
         * javadoc pour le pourquoi.
         */
        private Object[] computeStacks() {
            // Joueur par l'accessor Mixin (PlayerData) + getItemInHand/
            // getItemBySlot (méthodes publiques, voir stub LocalPlayer) —
            // zéro réflexion.
            //
            // Le repli réflexif multi-bracket a été supprimé le 2026-08-27.
            // À savoir avant tout portage : getStackInHand() n'est PAS no-arg
            // (il prend un Hand — une recherche no-arg ne le trouve jamais, et
            // la « première main » restait vide) ; Hand→InteractionHand et
            // getStackInHand→getItemInHand en 26.1 ; Hand n'existe pas du tout
            // avant la 1.9 ; l'armure se lisait par getArmorSlot(int) avant
            // que getEquippedStack/getItemBySlot(EquipmentSlot) ne le remplace.
            LocalPlayer player = PlayerData.player();
            if (player == null) return new Object[]{ null, null, null, null, null };
            try {
                Object held = player.getItemInHand(mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
                Object helmet = player.getItemBySlot(EquipmentSlot.HEAD);
                Object chest = player.getItemBySlot(EquipmentSlot.CHEST);
                Object legs = player.getItemBySlot(EquipmentSlot.LEGS);
                Object boots = player.getItemBySlot(EquipmentSlot.FEET);
                return new Object[]{ helmet, chest, legs, boots, held };
            } catch (Throwable ignored) {
                return new Object[]{ null, null, null, null, null };
            }

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
         * hotbar, centrée verticalement dessus. 4 cases d'armure UNIQUEMENT
         * (pas de case "main" — déjà affichée nativement par vanilla, voir
         * javadoc de VANILLA_SLOT_OFFSETS_GUI) ; {@code stacks[4]} (held) est
         * donc ignoré ici (boucle bornée par VANILLA_SLOT_OFFSETS_GUI.length).
         */
        private void drawVanillaHotbarRow(UiRenderer renderer, Object[] stacks, int vpWidth, int vpHeight) {
            float guiScale = UiRenderer.guiScale(vpWidth);
            float guiWidth = vpWidth / guiScale;

            float hotbarLeftGui = (guiWidth - HOTBAR_W_GUI) / 2f;
            float offhandReserveGui = vanillaOffhandVisibleOnLeft() ? VANILLA_OFFHAND_RESERVED_GUI : 0f;
            float rowLeftGui = hotbarLeftGui - offhandReserveGui - VANILLA_ROW_GAP_GUI - VANILLA_ROW_W_GUI;
            // Centré verticalement sur la hauteur de la hotbar (22 GUI-px) —
            // sprite 24 GUI-px de haut, donc légèrement "proud" en haut/bas
            // (-1 chacun), exactement comme la vraie case de main secondaire
            // vanilla (même sprite).
            float spriteTopGui = (HOTBAR_H_GUI - VANILLA_SPRITE_H_GUI) / 2f;
            float iconTopGui = spriteTopGui + VANILLA_ICON_OFFSET_Y_GUI;
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
                float iconLeftGui = rowLeftGui + VANILLA_SLOT_OFFSETS_GUI[i] + VANILLA_ICON_OFFSET_X_GUI;
                float iconLeftFb = iconLeftGui * guiScale;
                renderer.drawVanillaItemIcon(stack, iconLeftFb, iconBottomFb, VANILLA_ICON_GUI * guiScale, vpWidth, vpHeight, true);
            }
        }

        /**
         * Vanilla ne dessine SA PROPRE case de main secondaire (à gauche de
         * la hotbar, même sprite hud/hotbar_offhand_left que le nôtre — voir
         * UiRenderer) QUE si (a) la main secondaire n'est PAS vide (vérifié
         * via Player.getOffhandItem().isEmpty() dans le vrai bytecode de
         * Gui.extractItemHotbar, voir historique de session) ET (b) la main
         * "principale" du joueur est DROITE (Player.getMainArm()==RIGHT,
         * l'opposé — la main secondaire visuelle — se trouve alors à GAUCHE ;
         * si le joueur a réglé "main principale = gauche" dans les options
         * vanilla, sa case de main secondaire s'affiche à DROITE de la
         * hotbar, aucune collision possible avec notre rangée). Demandé
         * explicitement par l'utilisateur : notre rangée d'armure doit
         * réserver de la place pour cette case UNIQUEMENT quand elle est
         * réellement affichée, pas systématiquement.
         */
        private boolean vanillaOffhandVisibleOnLeft() {
            // Joueur par l'accessor Mixin — voir computeStacks() pour le
            // principe et pour l'historique des renommages de mappings.
            LocalPlayer player = PlayerData.player();
            if (player == null) return false;
            try {
                Object offHandStackObj = player.getItemInHand(InteractionHand.OFF_HAND);
                if (!(offHandStackObj instanceof ItemStack) || ((ItemStack) offHandStackObj).isEmpty()) return false;
                // "Arm" (Yarn) == "HumanoidArm" (vrai nom 26.1.2, confirmé par
                // désassemblage de Gui.extractItemHotbar réel).
                return player.getMainArm() == HumanoidArm.RIGHT;
            } catch (Throwable t) {
                return false;
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
            // Méthodes publiques d'ItemStack — zéro réflexion. Repli
            // multi-bracket supprimé le 2026-08-27 ; renommages 26.1 à
            // connaître pour un portage : isDamageable→isDamageableItem,
            // getDamage→getDamageValue (getMaxDamage inchangé).
            if (!(stack instanceof ItemStack)) return null;
            try {
                ItemStack is = (ItemStack) stack;
                if (!is.isDamageableItem()) return null;
                int max = is.getMaxDamage();
                int dmg = is.getDamageValue();
                return (max - dmg) + "/" + max;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
