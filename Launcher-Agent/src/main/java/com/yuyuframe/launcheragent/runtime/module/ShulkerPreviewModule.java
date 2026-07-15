package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

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
 * supplémentaire nécessaire. Icônes via {@link UiRenderer#drawVanillaItemIcon},
 * déjà utilisé par {@link ArmorDurabilityModule}.
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

    private static final String SCREEN_CLASS = "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen";
    private static final String BLOCK_ITEM_CLASS = "net.minecraft.world.item.BlockItem";
    private static final String SHULKER_BLOCK_CLASS = "net.minecraft.world.level.block.ShulkerBoxBlock";
    private static final String DATA_COMPONENTS_CLASS = "net.minecraft.core.component.DataComponents";

    private static final int COLS = 9, ROWS = 3;
    private static final float SLOT_GUI = 18f;
    private static final float ICON_GUI = 16f;
    private static final float PADDING_GUI = 6f;
    private static final float CURSOR_GAP_GUI = 12f;

    // ── Résolution paresseuse, mise en cache (comme GlobalUiRenderBridge261) ──
    private static volatile Field fHoveredSlot;
    private static volatile Method mGetItem;
    private static volatile Method mItemStackGetItem;
    private static volatile Method mItemStackIsEmpty;
    private static volatile Method mBlockItemGetBlock;
    private static volatile Method mItemStackGet;
    private static volatile Method mAllItemsCopyStream;
    private static volatile Field fContainerComponent;
    private static volatile Class<?> clsBlockItem;
    private static volatile Class<?> clsShulkerBoxBlock;

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
            LauncherModule self = ModuleRegistry.get("shulker-preview");
            if (self == null || !self.isEnabled()) return;
            if (currentScreen == null || poller == null || !poller.shiftDown) return;

            Object hoveredSlot = hoveredSlot(currentScreen);
            if (hoveredSlot == null) return;
            Object stack = slotItem(hoveredSlot);
            if (stack == null || isEmptyStack(stack)) return;
            if (!isShulkerBox(stack)) return;

            Object[] items = readContents(stack);
            if (items == null) return;

            drawGrid(renderer, items, poller, vpWidth, vpHeight);
        } catch (Throwable ignored) {}
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
     * Panneau flottant (fond sombre arrondi, style OneConfig — voir UiTheme)
     * ancré en haut-à-droite du curseur, grille 9x3 (taille fixe d'une
     * shulker box vanilla), cases vides simplement omises. Coordonnées en
     * "GUI-pixels" multipliées par {@link UiRenderer#guiScale} avant tout
     * appel de dessin — même convention que ArmorDurabilityModule.drawVanillaHotbarRow.
     * {@code poller.mouseX/mouseY} sont déjà en pixels framebuffer origine
     * bas-gauche (voir UiInputPoller), donc directement utilisables tels quels.
     */
    private static void drawGrid(UiRenderer renderer, Object[] items, UiInputPoller poller, int vpWidth, int vpHeight) {
        float guiScale = UiRenderer.guiScale(vpWidth);

        float panelWGui = COLS * SLOT_GUI + 2 * PADDING_GUI;
        float panelHGui = ROWS * SLOT_GUI + 2 * PADDING_GUI;
        float panelW = panelWGui * guiScale;
        float panelH = panelHGui * guiScale;

        float gap = CURSOR_GAP_GUI * guiScale;
        float left = (float) poller.mouseX + gap;
        float top = (float) poller.mouseY + gap;

        // Recale dans l'écran si le panneau déborderait (coin haut-droit du curseur par défaut).
        if (left + panelW > vpWidth) left = vpWidth - panelW;
        if (left < 0) left = 0;
        if (top > vpHeight) top = vpHeight;
        float bottom = top - panelH;
        if (bottom < 0) { bottom = 0; top = panelH; }

        renderer.drawRoundedRect(left, bottom, left + panelW, top, UiTheme.RADIUS_MD, UiTheme.PANEL_BG, vpWidth, vpHeight);

        float paddingPx = PADDING_GUI * guiScale;
        float slotPx = SLOT_GUI * guiScale;
        float iconPx = ICON_GUI * guiScale;
        float iconInset = (SLOT_GUI - ICON_GUI) / 2f * guiScale;

        for (int i = 0; i < items.length && i < COLS * ROWS; i++) {
            Object stack = items[i];
            if (stack == null) continue;
            int col = i % COLS, row = i / COLS;
            float cellTop = top - paddingPx - row * slotPx;
            float cellLeft = left + paddingPx + col * slotPx;
            renderer.drawVanillaItemIcon(stack, cellLeft + iconInset, cellTop - slotPx + iconInset, iconPx, vpWidth, vpHeight);
        }
    }
}
