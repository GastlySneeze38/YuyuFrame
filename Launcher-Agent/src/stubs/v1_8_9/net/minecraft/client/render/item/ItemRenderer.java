package net.minecraft.client.render.item;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.item.ItemStack;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code bjh}).
 *
 * <p>{@code renderInGuiWithOverrides} : modèle de l'item à 16×16 unités GUI,
 * reflet d'enchantement compris. {@code renderGuiItemOverlay} : barre de
 * durabilité et nombre. Ce sont les deux appels de la hotbar vanilla.
 */
public class ItemRenderer {

    private ItemRenderer() {
    }

    /** Modèle rendu en volume (bloc) plutôt qu'en sprite plat ({@code bjh.a(Lzx;)Z}). */
    public boolean hasDepth(ItemStack stack) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void renderInGuiWithOverrides(ItemStack stack, int x, int y) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public void renderGuiItemOverlay(TextRenderer textRenderer, ItemStack stack, int x, int y) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
