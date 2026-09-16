package com.yuyuframe.launcheragent.apigraphic.era.gl3.item;

import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemQueue;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.List;

/**
 * Pont vers les renderers vanilla — ère gl3, dont la 1.8.9 est la seule
 * version supportée.
 *
 * <p>Les modules dessinent dans la passe de l'agent : leurs icônes et blits
 * sont mis en file, puis vidés par le hub 1.8.9 juste après ce dessin
 * ({@code GlobalUiRenderMixin189} → {@link #flushLegacyHud}), par le récepteur
 * typé de la version ({@link Gl3VanillaItemSinks}).
 *
 * <p>Le chemin des versions 1.17 – 1.21.x (vidage dans le {@code DrawContext}
 * vivant du HUD, résolu par réflexion) a été supprimé le 2026-09-16 avec
 * l'abandon de ces versions.
 */
public final class Gl3VanillaItemRenderer {

    private final VanillaItemQueue queue = new VanillaItemQueue("immediat");

    // ── Mise en file ──────────────────────────────────────────────────────

    public void enqueueItemIcon(Object itemStack, float x, float y, float size,
                         boolean vanillaExtras, int vpWidth, int vpHeight) {
        try {
            queue.enqueueIcon(itemStack, x, y, size, vanillaExtras, vpWidth, vpHeight);
        } catch (Throwable t) {
            warnOnce("enqueueItemIcon: " + t);
        }
    }

    public void enqueueGuiBlit(String texturePath, float x, float y, float w, float h,
                        float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        try {
            queue.enqueueBlit(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        } catch (Throwable t) {
            warnOnce("enqueueGuiBlit: " + t);
        }
    }

    private boolean warned;

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        LauncherLog.err("[UiRenderer] " + message);
    }

    // ── Vidage 1.8.9 : GUI en pipeline fixe, récepteur typé de la version ──

    /**
     * Hôte {@link com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost#LEGACY_HUD}
     * — même modèle que {@code Blaze3DVanillaItemRenderer.flush} : la file et
     * les diagnostics restent ici, l'appel au jeu part au récepteur de la
     * version ({@link Gl3VanillaItemSinks}), typé et sans réflexion.
     *
     * <p>Sans récepteur, la file n'est PAS vidée : son plafond la borne.
     */
    public void flushLegacyHud() {
        try {
            Gl3VanillaItemSink sink = Gl3VanillaItemSinks.active();
            if (sink == null) return;
            List<VanillaItemIcon> icons = queue.drainIcons();
            List<VanillaGuiBlit> blits = queue.drainBlits();
            if (icons.isEmpty() && blits.isEmpty()) {
                queue.reportEmptyBatch();
                return;
            }
            queue.reportFirstBatch(icons.size(), blits.size());
            sink.drawVanillaItems(icons, blits);
        } catch (Throwable t) {
            warnOnce("vidage 1.8.9 (LEGACY_HUD) : " + t);
        }
    }
}
