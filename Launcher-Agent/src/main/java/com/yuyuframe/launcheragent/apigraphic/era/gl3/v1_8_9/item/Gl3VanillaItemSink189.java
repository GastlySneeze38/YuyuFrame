package com.yuyuframe.launcheragent.apigraphic.era.gl3.v1_8_9.item;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.era.gl3.item.Gl3VanillaItemSink;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Icônes d'item vanilla et blits de texture GUI sur 1.8.9 — pendant de
 * {@code VanillaGuiSink1211.drawVanillaItems}, même rendu.
 *
 * <h2>Où et comment</h2>
 *
 * Appelé en fin de {@code GameRenderer.render}, juste après le dessin de
 * l'agent ({@code GlobalUiRenderMixin189}) : la projection GUI de vanilla
 * (mise à l'échelle) est encore en place, donc on dessine directement en
 * pixels GUI — PAR-DESSUS les panneaux de l'agent. Vidées plus tôt dans
 * {@code InGameHud.render} (2026-09-16), elles passaient dessous.
 *
 * <p>Séquence reprise de la hotbar vanilla, vérifiée au javap sur
 * {@code InGameHud.render} : {@code GlStateManager.enableRescaleNormal} →
 * {@code DiffuseLighting.enable} (éclairage GUI des items) → pour chaque item
 * {@code renderInGuiWithOverrides} puis {@code renderGuiItemOverlay} →
 * {@code DiffuseLighting.disable} → {@code disableRescaleNormal}. L'état est
 * donc rendu tel que vanilla le laisse après sa propre hotbar.
 *
 * <h2>Fond de case</h2>
 *
 * Le sprite {@code hud/hotbar_offhand_left} des versions récentes n'existe pas
 * en 1.8.9 (pas de main secondaire). Équivalent vanilla le plus proche : UNE
 * case de la hotbar de {@code textures/gui/widgets.png}, lue sur le PNG réel —
 * cadre 22×22 en (0,0), intérieur 16×16 à l'offset (3,3), exactement la
 * position où la hotbar vanilla pose ses items. Suit donc aussi les resource
 * packs qui redessinent la hotbar.
 *
 * <p>Typé contre les stubs Yarn legacy, traduit au chargement
 * ({@link YarnNamed}) : aucune réflexion.
 */
@YarnNamed
public final class Gl3VanillaItemSink189 implements Gl3VanillaItemSink {

    private static final String WIDGETS = "textures/gui/widgets.png";
    private static final int SLOT_SIZE = 22;
    private static final int ICON_OFFSET = 3;
    private static final float WIDGETS_SIZE = 256f;

    private Identifier widgets;
    private String lastReport;

    @Override
    public String id() {
        return "1.8.9";
    }

    @Override
    public void drawVanillaItems(List<VanillaItemIcon> icons, List<VanillaGuiBlit> blits) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        try {
            TextureManager textures = client.getTextureManager();
            if (!blits.isEmpty()) drawBlits(textures, blits);
            if (!icons.isEmpty()) drawIcons(client, textures, icons);
        } catch (Throwable t) {
            reportOnce("dessin des items : " + t);
        }
    }

    private void drawBlits(TextureManager textures, List<VanillaGuiBlit> blits) {
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
        for (VanillaGuiBlit blit : blits) {
            textures.bindTexture(new Identifier("minecraft", blit.texturePath));
            DrawableHelper.drawTexture(blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH, blit.texW, blit.texH);
        }
    }

    private void drawIcons(MinecraftClient client, TextureManager textures, List<VanillaItemIcon> icons) {
        // Fonds de case d'abord, en une seule liaison de texture.
        boolean backgroundsBound = false;
        for (VanillaItemIcon icon : icons) {
            if (!icon.vanillaExtras) continue;
            if (!backgroundsBound) {
                if (widgets == null) widgets = new Identifier("minecraft", WIDGETS);
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, 1f);
                textures.bindTexture(widgets);
                backgroundsBound = true;
            }
            DrawableHelper.drawTexture(icon.guiX - ICON_OFFSET, icon.guiY - ICON_OFFSET, 0f, 0f,
                SLOT_SIZE, SLOT_SIZE, WIDGETS_SIZE, WIDGETS_SIZE);
        }

        ItemRenderer items = client.getItemRenderer();
        TextRenderer text = client.textRenderer;
        GlStateManager.enableRescaleNormal();
        DiffuseLighting.enable();
        try {
            for (VanillaItemIcon icon : icons) {
                if (!(icon.itemStack instanceof ItemStack)) continue;
                ItemStack stack = (ItemStack) icon.itemStack;
                items.renderInGuiWithOverrides(stack, icon.guiX, icon.guiY);
                if (icon.vanillaExtras) items.renderGuiItemOverlay(text, stack, icon.guiX, icon.guiY);
            }
        } finally {
            DiffuseLighting.disable();
            GlStateManager.disableRescaleNormal();
        }
    }

    /** Une ligne par raison DISTINCTE — jamais muet, jamais à chaque frame. */
    private void reportOnce(String message) {
        if (message.equals(lastReport)) return;
        lastReport = message;
        LauncherLog.err("[Gl3VanillaItemSink189] " + message);
    }
}
