package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;

import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

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

    public int layout = 0;

    @Override
    protected void settings(SettingList s) {
        s.dropdown("layout", "Disposition",
            "Empilée verticalement (une ligne par emplacement) ou côte à côte horizontalement.",
            "Réglages", new String[]{ "Verticale", "Horizontale" }, null,
            () -> layout, v -> layout = v);
        s.dropdown("hand", "Main affichée",
            "Quelle main afficher pour l'objet en main (1.9+ uniquement — ignoré sur 1.8.9, qui n'a pas de main secondaire).",
            "Réglages", new String[]{ "Main secondaire", "Main principale" }, null,
            () -> hand, v -> hand = v);
        s.dropdown("style", "Style",
            "\"Personnalisé\" = position/taille libres, icône + texte de durabilité (défaut). \"Vanilla\" = position fixe façon hotbar, VRAIE case + barre de durabilité vanilla, HUD verrouillé (non déplaçable).",
            "Réglages", new String[]{ "Personnalisé", "Vanilla" }, null,
            () -> style, v -> style = v);

        s.toggle("durabilityColor", "Couleur selon la durabilité",
            "Colore le texte de durabilité du vert au rouge en passant par le jaune, exactement comme la barre de durabilité vanilla. Sans effet sur le style \"Vanilla\" (qui affiche la vraie barre, déjà colorée) ni sur le nombre d'objets d'une pile.",
            "Réglages", null, () -> durabilityColor, v -> durabilityColor = v);

        s.toggle("lowDurabilityAlert", "Alerte sonore",
            "Joue un carillon quand une pièce sur le point de casser encaisse un coup.",
            "Alerte", null, () -> lowDurabilityAlert, v -> lowDurabilityAlert = v);
        s.slider("lowDurabilityPercent", "Seuil (% restant)",
            "Une pièce est considérée en danger sous ce pourcentage de durabilité.",
            "Alerte", 1f, 50f, 1f, () -> lowDurabilityAlert,
            () -> lowDurabilityPercent, v -> lowDurabilityPercent = v);
        s.slider("lowDurabilityPoints", "Seuil (points restants)",
            "…ou sous ce nombre de points, quel que soit le pourcentage. Décisif pour les matériaux fragiles : 10% d'une pioche en or ne fait que quelques coups.",
            "Alerte", 0f, 200f, 5f, () -> lowDurabilityAlert,
            () -> lowDurabilityPoints, v -> lowDurabilityPoints = v);
    }

    /** Voir {@code Renderer.rowColor()}. */
    public boolean durabilityColor = true;

    /** Voir {@link #tickLowDurabilityAlert()} — repris du comportement d'uku's Armor HUD. */
    public boolean lowDurabilityAlert = true;
    public float lowDurabilityPercent = 10f;
    public float lowDurabilityPoints = 50f;

    // Défaut = main secondaire (0) : demandé explicitement par l'utilisateur
    // ("à partir des versions où on a une deuxième main, afficher la
    // deuxième main par défaut") — ignoré sur 1.8.9, qui n'a pas de main
    // secondaire (voir currentStacks(), handClass reste null sur ce bracket,
    // repli silencieux sur la main principale).
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
    public int style = 0;

    public ArmorDurabilityModule() {
        super("armor-durability", "Armure/Durabilité", "Durabilité de l'armure et de l'objet en main", false,
            new HudElement("armor-durability", "Armure/Durabilité", HudAnchor.BOTTOM_RIGHT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
        iconUrl = icons8("shield");

        // ⚠️ NE PAS rebrancher HUD_EXTRACT_ARMOR ici (retour utilisateur
        // 2026-08-31 : « notre system d'armure enlève la barre d'armure
        // au-dessus de la vie »). Le câblage précédent (2026-08-25, §19)
        // partait d'un contresens sur ce que supprime ce hook :
        // {@code Gui.extractArmor} ne dessine PAS les pièces d'armure, il
        // dessine la BARRE DE POINTS D'ARMURE (les ~10 icônes de plastron
        // au-dessus des cœurs) — une donnée que ce module n'affiche nulle
        // part, et donc ne duplique pas. L'annuler faisait juste disparaître
        // l'indicateur d'armure du joueur.
        //
        // Vanilla n'a JAMAIS eu d'affichage de durabilité par pièce : il n'y
        // a rien à supprimer, quel que soit le style (« Personnalisé » comme
        // « Vanilla », qui ne fait que rendre nos propres cases à gauche de
        // la hotbar). Le chevauchement signalé à l'époque était un problème
        // de POSITION, pas de doublon.
        //
        // Conséquence voulue : plus aucun HookPoint réclamé, donc
        // HudExtractArmorMixin261 n'est plus tissé du tout (filtre
        // MixinHookPointRegistry) — une injection de moins dans Gui. Le mixin
        // reste en place pour un futur module qui voudrait, lui, remplacer
        // vraiment la barre de points d'armure.
    }

    /**
     * Dernier {@code getDamageValue()} vu par emplacement — {@code -1} = pas
     * encore observé. Voir {@link #tickLowDurabilityAlert()}.
     */
    private final int[] lastDamage = { -1, -1, -1, -1, -1 };

    @Override
    public void onTick() {
        tickLowDurabilityAlert();
    }

    /**
     * Alerte sonore de durabilité basse.
     *
     * <p>Approche reprise d'<b>uku's Armor HUD</b> (MIT, uku3lig/armor-hud),
     * dont la logique de déclenchement est plus fine que le réflexe naturel :
     * le son ne se joue PAS tant qu'une pièce est basse, mais AU MOMENT où
     * elle encaisse un coup en étant déjà sous le seuil. Un joueur immobile
     * avec une armure en fin de vie n'entend donc rien, et il n'y a aucun
     * cooldown à régler — le rythme des dégâts fait le travail.
     *
     * <p>Deux seuils en OU, également repris de là-bas : un POURCENTAGE et un
     * NOMBRE DE POINTS absolu. Le second est celui qui compte sur les
     * matériaux fragiles — 10 % d'une pioche en or, c'est trois coups.
     *
     * <p>Le SON, lui, est vanilla ({@code AMETHYST_BLOCK_CHIME}) : celui
     * d'uku est un asset {@code .ogg} qui leur est propre, et qu'ils tiennent
     * eux-mêmes de Giselbaer's Durability Viewer.
     */
    private void tickLowDurabilityAlert() {
        if (!lowDurabilityAlert) return;
        try {
            // computeStacks() et NON currentStacks() : ce dernier sert un
            // cache que seul naturalSize() rafraîchit, donc UNIQUEMENT quand
            // le HUD est effectivement dessiné. L'alerte, elle, doit sonner
            // même HUD masqué (F1, écran ouvert, module HUD replié) — sinon
            // elle lirait indéfiniment la dernière armure vue avant le
            // masquage. Un recalcul par tick (20/s) au lieu d'un par frame :
            // moins cher que le chemin de rendu, pas plus.
            ItemInfo[] stacks = RENDERER.computeStacks();
            boolean ring = false;
            for (int i = 0; i < lastDamage.length && i < stacks.length; i++) {
                ItemInfo stack = stacks[i];
                if (stack == null || !stack.damageable) { lastDamage[i] = -1; continue; }

                int damage = stack.damage;
                int previous = lastDamage[i];
                lastDamage[i] = damage;
                // Première observation : on mémorise sans sonner, sinon
                // équiper une pièce déjà usée déclencherait l'alerte.
                if (previous < 0 || damage <= previous) continue;
                if (isLowDurability(stack)) ring = true;
            }
            // UN seul son même si plusieurs pièces sont touchées dans la même
            // frame (dégâts de zone) — sinon quatre carillons superposés.
            if (ring) playAlert();
        } catch (Throwable t) {
            if (!alertErrorLogged) {
                alertErrorLogged = true;
                LauncherLog.err("[ArmorDurabilityModule] alerte durabilité: " + t);
            }
        }
    }

    private boolean isLowDurability(ItemInfo stack) {
        int max = stack.maxDamage;
        if (max <= 0) return false;
        int remaining = stack.remaining();
        return (remaining * 100f / max) <= lowDurabilityPercent
            || remaining <= lowDurabilityPoints;
    }

    private static boolean alertErrorLogged;

    private void playAlert() {
        // Carillon net et bref, choisi parce qu'il est directement un
        // SoundEvent des deux côtés (beaucoup d'entrées de SoundEvents sont
        // des Holder$Reference, qui demanderaient une autre surcharge).
        //
        // Volume réduit : une alerte permanente à pleine puissance devient
        // vite pénible en combat, moment où elle se déclenche le plus.
        ClientData.playUiSound("minecraft:block.amethyst_block.chime", 1.0f, 0.5f);
    }

    @Override
    public void onConfigChanged() {
        RENDERER.horizontal = layout == 1;
        RENDERER.mainHand = hand == 1;
        RENDERER.vanillaStyle = style == 1;
        RENDERER.durabilityColor = durabilityColor;
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
        /** Mutable directement par ArmorDurabilityModule.onConfigChanged() — voir rowColor(). */
        volatile boolean durabilityColor = true;

        // AUDIT PERF (demandé explicitement par l'utilisateur) : naturalSize()/
        // hasContent()/draw() appelaient CHACUN currentStacks() séparément —
        // 3 recalculs complets (donc 3x ~6-10 invocations de réflexion) par
        // frame pour la MÊME donnée, alors que HudOverlayRenderer garantit
        // TOUJOURS refreshSize() (→ naturalSize()) juste avant
        // HudPanelRenderer.draw() (→ hasContent() PUIS draw()) — voir leurs
        // javadoc respectives. naturalSize() (premier appel du cycle,
        // TOUJOURS déclenché) rafraîchit ce cache ; hasContent()/draw()
        // réutilisent la MÊME valeur au lieu de recalculer.
        private ItemInfo[] cachedStacks;
        private long cachedStacksAtMs;
        /** Un tick de jeu — voir naturalSize(). */
        private static final long STACKS_REFRESH_MS = 50L;

        ItemInfo[] currentStacks() {
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
            ItemInfo[] stacks = currentStacks();
            int count = vanillaStyle ? VANILLA_SLOT_OFFSETS_GUI.length : stacks.length;
            for (int i = 0; i < count && i < stacks.length; i++) {
                if (stacks[i] != null && !stacks[i].empty) return true;
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
            //
            // AUDIT PERF (2026-08-31) : recalcul plafonné à un TICK de jeu au
            // lieu d'une fois par frame. Ce n'est pas une approximation — la
            // durabilité d'un objet est une valeur entière qui ne bouge qu'au
            // tick ; recalculer à 120 fps produisait six fois la même réponse,
            // en allouant un tableau et, plus loin, une chaîne et une couleur
            // par emplacement à chaque fois.
            long now = System.currentTimeMillis();
            if (cachedStacks == null || now - cachedStacksAtMs >= STACKS_REFRESH_MS) {
                cachedStacks = computeStacks();
                cachedStacksAtMs = now;
            }

            // Largeur = icône + espace + texte le plus large parmi les 5
            // emplacements, RECALCULÉE À CHAQUE FRAME (comme FPS/Ping, voir
            // HudElement.refreshSize()) — une largeur FIXE (120 en dur, choisie
            // au hasard) était soit trop large pour "363/363" (gros espace vide
            // à droite), soit trop étroite pour un objet à plus de 3 chiffres.
            float itemW = itemWidth(currentStacks());
            if (horizontal) return new float[]{ 5 * itemW + 4 * ITEM_GAP, ROW_H };
            return new float[]{ itemW, 5 * ROW_H };
        }

        private float itemWidth(ItemInfo[] stacks) {
            float maxTextW = 0f;
            for (ItemInfo stack : stacks) {
                // rowText et NON durabilityText : depuis que la taille de pile
                // s'affiche aussi (2026-08-31), mesurer la seule durabilité
                // laissait le panneau trop étroit pour un "×64", qui débordait
                // sur le bord.
                String text = rowText(stack);
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
        ItemInfo[] computeStacks() {
            // Plus une seule classe du jeu ici (2026-09-12) : les six
            // emplacements arrivent en ItemInfo par un point d'accès, et ce
            // module n'en garde que cinq — les quatre pièces d'armure plus LA
            // main qu'il affiche (réglage « hand »).
            //
            // Les pièges de mappings sont désormais absorbés par la liaison de
            // chaque tranche, mais restent bons à connaître : getStackInHand()
            // n'est PAS sans argument (il prend un Hand — une recherche sans
            // argument ne le trouve jamais, et la « première main » restait
            // vide) ; Hand→InteractionHand et getStackInHand→getItemInHand en
            // 26.1 ; Hand n'existe pas du tout avant la 1.9 ; l'armure se
            // lisait par getArmorSlot(int) avant que getEquippedStack/
            // getItemBySlot(EquipmentSlot) ne le remplace.
            try {
                ItemInfo[] slots = PlayerData.equipment();
                ItemInfo held = slots[mainHand ? PlayerData.SLOT_MAIN_HAND : PlayerData.SLOT_OFF_HAND];
                return new ItemInfo[]{
                    slots[PlayerData.SLOT_HEAD],
                    slots[PlayerData.SLOT_CHEST],
                    slots[PlayerData.SLOT_LEGS],
                    slots[PlayerData.SLOT_FEET],
                    held,
                };
            } catch (Throwable t) {
                // Journalisé UNE fois : appelé à chaque frame, un log par
                // frame noierait la console — mais un échec silencieux ici
                // vide le HUD sans laisser la moindre trace.
                if (!stacksErrorLogged) {
                    stacksErrorLogged = true;
                    LauncherLog.err("[ArmorDurabilityModule] computeStacks: " + t);
                }
                return EMPTY_STACKS;
            }
        }

        /** Cinq emplacements vides — partagé, {@link ItemInfo} étant immuable. */
        private static final ItemInfo[] EMPTY_STACKS = {
            ItemInfo.EMPTY, ItemInfo.EMPTY, ItemInfo.EMPTY, ItemInfo.EMPTY, ItemInfo.EMPTY
        };

        private static boolean stacksErrorLogged;

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            ItemInfo[] stacks = currentStacks();

            if (vanillaStyle) {
                drawVanillaHotbarRow(renderer, stacks, vpWidth, vpHeight);
                return;
            }

            float icon = ICON * scale;

            if (horizontal) {
                float itemW = itemWidth(stacks) * scale, itemGap = ITEM_GAP * scale;
                float rowY = y + h - icon;
                float colX = x;
                for (ItemInfo stack : stacks) {
                    drawRow(renderer, colX, rowY, icon, stack, scale, vpWidth, vpHeight);
                    colX += itemW + itemGap;
                }
                return;
            }

            float rowH = ROW_H * scale;
            float rowY = y + h - icon;
            for (ItemInfo stack : stacks) {
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
        private void drawVanillaHotbarRow(UiRenderer renderer, ItemInfo[] stacks, int vpWidth, int vpHeight) {
            float guiScale = UiRenderer.guiScale(vpWidth);
            // Dimensions GUI RÉELLES, pas vpWidth / guiScale : l'échelle est
            // désormais l'entier de vanilla (voir AccessPoint.CLIENT_GUI_SCALE,
            // 2026-09-13), et la taille GUI en est l'arrondi AU SUPÉRIEUR. La
            // division redonnait la bonne largeur tant que l'échelle était
            // elle-même reconstruite par division — les deux erreurs s'annulaient.
            int[] gui = ClientData.guiSize();
            int guiW = gui != null ? gui[0] : (int) Math.ceil(vpWidth / guiScale);
            int guiH = gui != null ? gui[1] : (int) Math.ceil(vpHeight / guiScale);

            // Gui.extractItemHotbar (bytecode 26.1.2) : centre = guiWidth / 2 en
            // division ENTIÈRE, hotbar à centre − 91. Une division flottante
            // décalait la rangée d'un demi-pixel GUI sur une largeur impaire.
            float hotbarLeftGui = guiW / 2 - HOTBAR_W_GUI / 2f;
            float offhandReserveGui = vanillaOffhandVisibleOnLeft() ? VANILLA_OFFHAND_RESERVED_GUI : 0f;
            float rowLeftGui = hotbarLeftGui - offhandReserveGui - VANILLA_ROW_GAP_GUI - VANILLA_ROW_W_GUI;
            // Centré verticalement sur la hauteur de la hotbar (22 GUI-px) —
            // sprite 24 GUI-px de haut, donc légèrement "proud" en haut/bas
            // (-1 chacun), exactement comme la vraie case de main secondaire
            // vanilla (même sprite).
            float spriteTopGui = (HOTBAR_H_GUI - VANILLA_SPRITE_H_GUI) / 2f;
            // Haut de l'icône en GUI (origine haut) : la hotbar est à
            // guiHeight − 22 (extractItemHotbar).
            float iconTopGui = (guiH - HOTBAR_H_GUI) + spriteTopGui + VANILLA_ICON_OFFSET_Y_GUI;
            // BUG TROUVÉ (2026-09-13, même cause que SaturationModule) : on
            // supposait la hotbar collée au bas de l'écran, d'où une distance
            // fixe depuis le bas. Faux quand la hauteur de fenêtre n'est pas un
            // multiple de l'échelle : vanilla projette avec l'échelle entière,
            // origine en HAUT, et sa GUI déborde sous l'écran. Conversion exacte
            // vers le repère moteur (Y vers le haut) :
            float iconBottomFb = vpHeight - (iconTopGui + VANILLA_ICON_GUI) * guiScale;

            for (int i = 0; i < stacks.length && i < VANILLA_SLOT_OFFSETS_GUI.length; i++) {
                ItemInfo stack = stacks[i];
                if (stack == null || stack.empty) continue;
                float iconLeftGui = rowLeftGui + VANILLA_SLOT_OFFSETS_GUI[i] + VANILLA_ICON_OFFSET_X_GUI;
                float iconLeftFb = iconLeftGui * guiScale;
                renderer.drawVanillaItemIcon(stack.handle, iconLeftFb, iconBottomFb, VANILLA_ICON_GUI * guiScale, vpWidth, vpHeight, true);
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
            // Deux points d'accès, aucune classe du jeu : l'enum de la main
            // principale ("Arm" en Yarn, "HumanoidArm" en 26.1.2) est rendu en
            // booléen pour cette raison précise.
            if (PlayerData.equipment()[PlayerData.SLOT_OFF_HAND].empty) return false;
            return PlayerData.mainArmRight();
        }

        private void drawRow(UiRenderer renderer, float x, float y, float iconSize, ItemInfo stack, float scale, int vpWidth, int vpHeight) {
            if (stack == null || stack.empty) return;
            // La POIGNÉE, pas le porteur : le rendu d'icône a besoin de la pile
            // réelle du jeu (voir ItemInfo.handle).
            renderer.drawVanillaItemIcon(stack.handle, x, y, iconSize, vpWidth, vpHeight);
            String text = rowText(stack);
            if (text != null) {
                float textScale = TEXT_SCALE * scale;
                renderer.drawText(text, x + iconSize + GAP * scale, y + iconSize * 0.35f, rowColor(stack), textScale, vpWidth, vpHeight);
            }
        }

        /**
         * Couleur du texte d'une ligne — dégradé de durabilité, ou couleur de
         * thème si le réglage est coupé / si la ligne n'affiche pas une
         * durabilité (taille de pile, voir rowText()).
         */
        private UiColor rowColor(ItemInfo stack) {
            if (!durabilityColor || stack == null || !stack.damageable || stack.maxDamage <= 0) {
                return UiTheme.TEXT_PRIMARY;
            }
            return durabilityColor((float) stack.remaining() / stack.maxDamage);
        }

        /**
         * Vert → jaune → rouge selon la durabilité restante — la FORMULE
         * EXACTE de vanilla ({@code ItemStack.getBarColor()} :
         * {@code Mth.hsvToRgb(remaining / 3f, 1f, 1f)}), pas une palette
         * réinventée, pour que le texte et la vraie barre de durabilité
         * s'accordent au pixel de teinte près (même norme que le reste du
         * module, qui utilise déjà les vrais sprites/barres vanilla).
         *
         * <p>Développé à la main plutôt que par un appel à {@code Mth} : avec
         * {@code s = v = 1}, la conversion HSV se réduit à deux segments
         * linéaires, et ça évite un stub de plus.
         *
         * @param remaining fraction restante, 1 = neuf, 0 = sur le point de casser.
         */
        private static UiColor durabilityColor(float remaining) {
            float sector = Math.min(1f, Math.max(0f, remaining)) * 2f; // (h/3) * 6
            int i = (int) sector;
            float f = sector - i;
            int r, g;
            if (i <= 0) {          // 0 → 50 % : rouge vers jaune
                r = 255; g = Math.round(f * 255f);
            } else if (i == 1) {   // 50 → 100 % : jaune vers vert
                r = Math.round((1f - f) * 255f); g = 255;
            } else {               // exactement 100 % (sector == 2, f == 0)
                r = 0; g = 255;
            }
            return new UiColor(r, g, 0, 255);
        }

        /**
         * Texte affiché à droite de l'icône, en style « Personnalisé ».
         *
         * <p>Durabilité pour un objet qui s'use, TAILLE DE PILE sinon (demande
         * du 2026-08-31 : un stack de blocs n'affichait que son image). Les
         * deux ne peuvent pas se disputer la place — un objet endommageable a
         * une pile de 1 par construction dans le jeu.
         *
         * <p>Rien pour une pile de 1 : afficher « ×1 » sur chaque objet à
         * l'unité alourdirait le HUD sans rien apprendre.
         */
        private String rowText(ItemInfo stack) {
            String durability = durabilityText(stack);
            if (durability != null) return durability;
            if (stack == null || stack.empty) return null;
            // "×" (U+00D7) et non "x" : dans la plage 160-255 couverte par
            // UiFont (voir sa javadoc), donc un vrai glyphe et pas le
            // caractère de repli.
            return stack.count > 1 ? "×" + stack.count : null;
        }

        private String durabilityText(ItemInfo stack) {
            // Plus aucune méthode du jeu ici : les trois valeurs viennent du
            // porteur neutre. Renommages absorbés par les liaisons, à connaître
            // quand même pour un portage : isDamageable (Yarn) →
            // isDamageableItem (26.1.2), getDamage → getDamageValue
            // (getMaxDamage et getCount inchangés).
            if (stack == null || !stack.damageable || stack.maxDamage <= 0) return null;
            return stack.remaining() + "/" + stack.maxDamage;
        }
    }
}
