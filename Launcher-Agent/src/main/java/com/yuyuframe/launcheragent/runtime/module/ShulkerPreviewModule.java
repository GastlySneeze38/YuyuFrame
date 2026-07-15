package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Aperçu du contenu d'une shulker box en la survolant (case d'inventaire)
 * tout en maintenant Maj — reprend l'IDÉE du mod ShulkerBoxTooltip
 * (MisterPeModder, GitHub, branche 26.1.x, licence MIT), PAS son code : ce
 * mod s'intègre profondément au pipeline de tooltip vanilla
 * (ClientTooltipComponent, GuiGraphicsExtractor, mixins sur Screen/
 * ItemStack...) — bien plus qu'il n'en faut ici. Réimplémenté avec
 * l'infrastructure déjà existante de ce projet : panneau flottant dessiné à
 * CHAQUE frame près du curseur, en dehors de tout pipeline de tooltip
 * vanilla — voir {@link #renderIfApplicable}, appelé directement depuis
 * GlobalUiPresentMixin261 (branche "écran vanilla ouvert"), aucun Mixin
 * supplémentaire nécessaire.
 *
 * BUG TROUVÉ (retour utilisateur) : un premier essai ajoutait un Mixin
 * {@code @Inject} sur {@code Screen.extractRenderStateWithTooltipAndSubtitles}
 * (pour annuler le tooltip vanilla quand notre panneau se chevauchait avec
 * lui) — ciblant la classe {@code Screen} elle-même (superclasse commune de
 * TOUS les écrans, y compris nos propres écrans custom comme
 * {@code UiMainMenuScreen}), Mixin devait reconstruire la hiérarchie de
 * classes pour chaque sous-type au chargement, et ça CASSAIT le chargement
 * de nos propres écrans (`RuntimeException: Failed to load class file for
 * UiMainMenuScreen`, HUD custom entier inaccessible). Retiré entièrement —
 * le panneau est de toute façon maintenant ancré sur la case survolée (voir
 * drawGrid), pas sur le curseur brut ni sur la fenêtre entière, ce qui
 * réduit déjà fortement le risque de chevauchement sans avoir besoin d'y
 * toucher.
 *
 * FOND DE FENÊTRE : la VRAIE texture vanilla
 * ({@code textures/gui/container/shulker_box.png}, 256x256, région visible
 * 176x166 dans son coin haut-gauche — confirmé par désassemblage bytecode de
 * {@code ShulkerBoxScreen.extractBackground} dans le vrai jar 26.1.2, PAS
 * recréée), demandé explicitement par l'utilisateur (resource-pack-personnalisable,
 * même exigence que le sprite "hud/hotbar_offhand_left" utilisé par
 * ArmorDurabilityModule) — voir {@link UiRenderer#drawVanillaContainerTexture}.
 * Recadrée à la zone de stockage UNIQUEMENT (sans l'inventaire du joueur, non
 * pertinent ici) : u=0,v=0, 176x78 GUI-pixels (bord plat en bas, pas de coins
 * arrondis à cette hauteur — seuls présents tout en bas de l'image complète,
 * après l'inventaire joueur — compromis accepté pour rester compact).
 *
 * Icônes via {@link UiRenderer#drawVanillaItemIcon}, déjà utilisé par
 * {@link ArmorDurabilityModule}. Origine des cases dans l'image (7,17),
 * pas de 18 GUI-px — valeurs standard vanilla pour la famille "coffre" (27
 * emplacements de stockage, chest/barrel/shulker box partagent la même mise
 * en page), PAS mesurées empiriquement ici mais réutilisées telles quelles :
 * cohérent avec l'image réelle blitée, donc les icônes tombent exactement
 * dans les cases dessinées par la texture, sans recalage manuel.
 *
 * SCOPE : bracket 26.1.2 UNIQUEMENT (voir IS_26_1/register dans
 * ModuleRegistry, même gate que NoPumpkinOverlayModule/ClearVisionModule).
 * Le contenu d'une shulker box est stocké via un DataComponent
 * ({@code DataComponents.CONTAINER}, type {@code ItemContainerContents})
 * depuis la refonte "Data Components" (~1.20.5) — sur 1.8.9/1.20.4 (NBT
 * {@code BlockEntityTag}), cette lecture ne s'applique pas du tout. Réflexion
 * à noms RÉELS directs (comme {@code GlobalUiRenderBridge261}, PAS
 * McReflect/MappingsRegistry — ce bracket n'est plus obfusqué) : chaque
 * signature ci-dessous vérifiée par désassemblage bytecode du vrai jar
 * client 26.1.2 (parser maison, {@code javap} ne lit pas le class-file
 * version 69/Java 25 de ce jar) :
 * <pre>
 *   AbstractContainerScreen.hoveredSlot : Slot (protected, hérité par toute
 *     sous-classe d'écran d'inventaire — InventoryScreen, ShulkerBoxScreen
 *     (générique via GenericContainerScreen), etc.)
 *   Slot.getItem() : ItemStack (public)
 *   ItemStack.getItem() : Item ; ItemStack.isEmpty() : boolean (public)
 *   BlockItem.getBlock() : Block (public) ; ShulkerBoxBlock extends Block
 *   DataComponents.CONTAINER : DataComponentType (champ statique public)
 *   ItemStack.get(DataComponentType) : Object (interface DataComponentHolder,
 *     héritée — renvoie l'ItemContainerContents ou null si le composant est absent)
 *   ItemContainerContents.allItemsCopyStream() : Stream (TOUJOURS un élément
 *     par slot, dans l'ordre, ItemStack.EMPTY pour les cases vides — exactement
 *     ce qu'il faut pour reconstituer fidèlement la grille 9x3 d'une shulker box)
 * </pre>
 */
public final class ShulkerPreviewModule extends LauncherModule {

    private static final String BLOCK_ITEM_CLASS = "net.minecraft.world.item.BlockItem";
    private static final String SHULKER_BLOCK_CLASS = "net.minecraft.world.level.block.ShulkerBoxBlock";
    private static final String DATA_COMPONENTS_CLASS = "net.minecraft.core.component.DataComponents";
    private static final String CONTAINER_TEXTURE_PATH = "textures/gui/container/shulker_box.png";

    private static final int COLS = 9, ROWS = 3;
    // Origine/pas RÉELS vanilla pour la famille "coffre" (27 slots de
    // stockage) — voir javadoc de classe : les icônes tombent directement
    // dans les cases peintes par CONTAINER_TEXTURE, pas de recalage manuel.
    private static final float SLOT_ORIGIN_X_GUI = 7f;
    private static final float SLOT_ORIGIN_Y_GUI = 17f;
    private static final float SLOT_PITCH_GUI = 18f;
    private static final float ICON_GUI = 16f;
    private static final float ICON_INSET_GUI = (SLOT_PITCH_GUI - ICON_GUI) / 2f;
    // Texture réelle 256x256 (atlas), région utile 176x166 — recadrée ici à
    // la zone de stockage seule (voir javadoc de classe : bord plat en bas,
    // compromis accepté).
    private static final float TEX_W = 256f, TEX_H = 256f;
    private static final float IMG_W_GUI = 176f;
    private static final float IMG_CROP_H_GUI = 78f;
    /** Écart (GUI-pixels) entre le bord de la case survolée et notre panneau — voir drawGrid, même ordre de grandeur que l'offset du tooltip vanilla. */
    private static final float SLOT_GAP_GUI = 12f;
    /** Taille d'une case vanilla (18x18) — pour placer le panneau juste APRÈS le bord droit de la case survolée. */
    private static final float HOVERED_SLOT_SIZE_GUI = 18f;

    // ── Résolution paresseuse, mise en cache (comme GlobalUiRenderBridge261) ──
    private static volatile Field fHoveredSlot;
    private static volatile Field fLeftPos, fTopPos;
    private static volatile Field fSlotX, fSlotY;
    private static volatile Method mGetItem;
    private static volatile Method mItemStackGetItem;
    private static volatile Method mItemStackIsEmpty;
    private static volatile Method mBlockItemGetBlock;
    private static volatile Method mItemStackGet;
    private static volatile Method mAllItemsCopyStream;
    private static volatile Field fContainerComponent;
    private static volatile Class<?> clsBlockItem;
    private static volatile Class<?> clsShulkerBoxBlock;

    private static volatile boolean diagLogged;

    public ShulkerPreviewModule() {
        super("shulker-preview", "Aperçu shulker (Maj)", "Survole une shulker box dans un inventaire en maintenant Maj pour voir son contenu.", true);
    }

    /**
     * Appelé à CHAQUE frame par GlobalUiPresentMixin261 quand un écran
     * vanilla (pas un des nôtres) est ouvert — voir sa javadoc. Ne fait rien
     * tant que le module est désactivé, qu'aucune touche Maj n'est
     * maintenue, ou que l'écran/la case survolée ne correspond pas à une
     * shulker box non vide.
     */
    public static void renderIfApplicable(UiRenderer renderer, Object currentScreen, UiInputPoller poller, int vpWidth, int vpHeight) {
        try {
            Object slot = hoveredShulkerSlot(currentScreen, poller);
            if (slot == null) return;
            Object stack = slotItem(slot);

            Object[] items = readContents(stack);

            if (!diagLogged) {
                diagLogged = true;
                int nonEmpty = 0;
                if (items != null) for (Object it : items) if (it != null) nonEmpty++;
                LauncherLog.info("[ShulkerPreviewModule] diag: stack=" + stack
                    + " items=" + (items == null ? "null (composant CONTAINER absent)" : items.length + " slots, " + nonEmpty + " non vides"));
            }

            if (items == null) return;
            drawGrid(renderer, currentScreen, slot, items, vpWidth, vpHeight);
        } catch (Throwable ignored) {}
    }

    /** @return la case (Slot) survolée si elle contient une shulker box non vide et que les conditions sont réunies (module actif, Maj maintenu) — sinon {@code null}. */
    private static Object hoveredShulkerSlot(Object screen, UiInputPoller poller) throws Exception {
        LauncherModule self = ModuleRegistry.get("shulker-preview");
        if (self == null || !self.isEnabled()) return null;
        if (screen == null || poller == null || !poller.shiftDown) return null;

        Object hoveredSlot = hoveredSlot(screen);
        if (hoveredSlot == null) return null;
        Object stack = slotItem(hoveredSlot);
        if (stack == null || isEmptyStack(stack)) return null;
        if (!isShulkerBox(stack)) return null;
        return hoveredSlot;
    }

    private static Object hoveredSlot(Object screen) throws Exception {
        if (fHoveredSlot == null) {
            Class<?> c = screen.getClass();
            Field found = null;
            while (c != null && found == null) {
                try {
                    found = c.getDeclaredField("hoveredSlot");
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (found == null) return null;
            found.setAccessible(true);
            fHoveredSlot = found;
        }
        return fHoveredSlot.get(screen);
    }

    /**
     * Position ÉCRAN (GUI-pixels, repère haut-gauche) de la case survolée —
     * {@code leftPos}/{@code topPos} (protected, déclarés sur
     * AbstractContainerScreen) + {@code Slot.x}/{@code Slot.y} (public,
     * déclarés directement sur Slot, RELATIFS à leftPos/topPos — voir
     * javadoc de classe) — tous vérifiés par désassemblage bytecode du vrai
     * jar 26.1.2. Sert à ancrer le panneau juste À CÔTÉ de l'objet survolé
     * (même repère que le tooltip vanilla), PAS sur le curseur ni sur la
     * fenêtre entière — voir drawGrid.
     */
    private static int[] hoveredSlotScreenPosGui(Object screen, Object slot) throws Exception {
        if (fLeftPos == null) {
            fLeftPos = declaredFieldInHierarchy(screen.getClass(), "leftPos");
            fTopPos = declaredFieldInHierarchy(screen.getClass(), "topPos");
            if (fLeftPos == null || fTopPos == null) return null;
        }
        if (fSlotX == null) {
            fSlotX = slot.getClass().getField("x");
            fSlotY = slot.getClass().getField("y");
        }
        int leftPos = fLeftPos.getInt(screen), topPos = fTopPos.getInt(screen);
        return new int[]{ leftPos + fSlotX.getInt(slot), topPos + fSlotY.getInt(slot) };
    }

    private static Field declaredFieldInHierarchy(Class<?> owner, String name) {
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Object slotItem(Object slot) throws Exception {
        if (mGetItem == null) mGetItem = slot.getClass().getMethod("getItem");
        return mGetItem.invoke(slot);
    }

    private static boolean isEmptyStack(Object stack) throws Exception {
        if (mItemStackIsEmpty == null) mItemStackIsEmpty = stack.getClass().getMethod("isEmpty");
        return (boolean) mItemStackIsEmpty.invoke(stack);
    }

    private static boolean isShulkerBox(Object stack) throws Exception {
        if (mItemStackGetItem == null) mItemStackGetItem = stack.getClass().getMethod("getItem");
        Object item = mItemStackGetItem.invoke(stack);
        if (item == null) return false;

        if (clsBlockItem == null) clsBlockItem = Class.forName(BLOCK_ITEM_CLASS, false, item.getClass().getClassLoader());
        if (!clsBlockItem.isInstance(item)) return false;

        if (mBlockItemGetBlock == null) mBlockItemGetBlock = clsBlockItem.getMethod("getBlock");
        Object block = mBlockItemGetBlock.invoke(item);
        if (block == null) return false;

        if (clsShulkerBoxBlock == null) clsShulkerBoxBlock = Class.forName(SHULKER_BLOCK_CLASS, false, block.getClass().getClassLoader());
        return clsShulkerBoxBlock.isInstance(block);
    }

    /** @return les ItemStack des (jusqu'à) 27 slots, EMPTY filtrés en null pour {@link #drawGrid} — null si le composant CONTAINER est absent. */
    private static Object[] readContents(Object stack) throws Exception {
        if (fContainerComponent == null) {
            Class<?> dc = Class.forName(DATA_COMPONENTS_CLASS, true, stack.getClass().getClassLoader());
            fContainerComponent = dc.getField("CONTAINER");
        }
        Object componentType = fContainerComponent.get(null);
        if (componentType == null) return null;

        if (mItemStackGet == null) mItemStackGet = stack.getClass().getMethod("get", fContainerComponent.getType());
        Object contents = mItemStackGet.invoke(stack, componentType);
        if (contents == null) return null;

        if (mAllItemsCopyStream == null) mAllItemsCopyStream = contents.getClass().getMethod("allItemsCopyStream");
        java.util.stream.Stream<?> stream = (java.util.stream.Stream<?>) mAllItemsCopyStream.invoke(contents);
        List<Object> out = new ArrayList<>();
        stream.forEach(entry -> {
            try {
                out.add(isEmptyStack(entry) ? null : entry);
            } catch (Throwable t) {
                out.add(null);
            }
        });
        return out.toArray();
    }

    /**
     * Panneau ancré JUSTE À CÔTÉ de la case survolée (voir
     * {@link #hoveredSlotScreenPosGui}) — même repère que le tooltip vanilla
     * (nom de l'objet), demandé explicitement par l'utilisateur ("à côté de
     * l'item, au même endroit que le texte du nom de l'item"). PAS sur le
     * curseur brut (bougeait avec le moindre tremblement de souris à
     * l'intérieur de la case) NI sur la fenêtre de conteneur entière (un
     * premier essai recouvrait l'inventaire) : ancré sur la case ELLE-MÊME,
     * donc stable tant que la souris reste dans la même case, et se
     * repositionne proprement d'une case à l'autre en survolant plusieurs
     * shulker box Maj maintenu. Par défaut à DROITE de la case ; repli à
     * GAUCHE si ça déborderait de l'écran.
     *
     * Fond = VRAIE texture vanilla recadrée (voir javadoc de classe), grille
     * 9x3 (taille fixe d'une shulker box vanilla), cases vides simplement
     * omises. Coordonnées en "GUI-pixels" multipliées par
     * {@link UiRenderer#guiScale} avant tout appel de dessin — même
     * convention que ArmorDurabilityModule.drawVanillaHotbarRow.
     */
    private static void drawGrid(UiRenderer renderer, Object currentScreen, Object slot, Object[] items, int vpWidth, int vpHeight) throws Exception {
        int[] slotPos = hoveredSlotScreenPosGui(currentScreen, slot);
        if (slotPos == null) return;
        int slotLeftGui = slotPos[0], slotTopGui = slotPos[1];

        float guiScale = UiRenderer.guiScale(vpWidth);
        float guiWidth = vpWidth / guiScale;

        float panelWGui = IMG_W_GUI;
        float panelHGui = IMG_CROP_H_GUI;

        float leftGui = slotLeftGui + HOVERED_SLOT_SIZE_GUI + SLOT_GAP_GUI;
        if (leftGui + panelWGui > guiWidth) leftGui = slotLeftGui - panelWGui - SLOT_GAP_GUI;
        if (leftGui < 0) leftGui = 0;
        // Aligné verticalement sur le HAUT de la case survolée (même ancre que le tooltip vanilla).
        float guiHeight = vpHeight / guiScale;
        float topGui = slotTopGui;
        if (topGui + panelHGui > guiHeight) topGui = guiHeight - panelHGui;
        if (topGui < 0) topGui = 0;

        float left = leftGui * guiScale;
        // Conversion vers notre repère bas-gauche (framebuffer) — voir
        // drawVanillaItemIcon/drawVanillaContainerTexture, qui attendent le
        // bord BAS en y (topGui = origine haut vanilla, guiHeight - topGui -
        // hauteur = bord bas en GUI-space, puis mise à l'échelle framebuffer).
        float bottom = (guiHeight - topGui - panelHGui) * guiScale;
        float top = bottom + panelHGui * guiScale;

        renderer.drawVanillaContainerTexture(CONTAINER_TEXTURE_PATH, left, bottom, panelWGui * guiScale, panelHGui * guiScale,
            0f, 0f, TEX_W, TEX_H, vpWidth, vpHeight);

        for (int i = 0; i < items.length && i < COLS * ROWS; i++) {
            Object stack = items[i];
            if (stack == null) continue;
            int col = i % COLS, row = i / COLS;
            float cellLeftGui = SLOT_ORIGIN_X_GUI + col * SLOT_PITCH_GUI + ICON_INSET_GUI;
            float cellTopGui = SLOT_ORIGIN_Y_GUI + row * SLOT_PITCH_GUI + ICON_INSET_GUI;
            float iconLeftFb = left + cellLeftGui * guiScale;
            // top = origine HAUT (image), notre repère est bas-gauche (framebuffer) — bord BAS de l'icône = top du panneau moins (cellTopGui+ICON_GUI) converti.
            float iconBottomFb = top - (cellTopGui + ICON_GUI) * guiScale;
            renderer.drawVanillaItemIcon(stack, iconLeftFb, iconBottomFb, ICON_GUI * guiScale, vpWidth, vpHeight);
        }
    }
}
