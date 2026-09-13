package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaFlushHost;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemQueue;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.List;

/**
 * Pont vers les renderers vanilla — ère Blaze3D (1.21.11 – 26.x), chemin
 * « DIFFÉRÉ ».
 *
 * <h2>Ce qui ne marche PAS sur cette ère</h2>
 *
 * Construire notre propre {@code GuiRenderState}/{@code DrawContext} et
 * appeler un « flush » dessus. Invalidé par désassemblage complet du VRAI
 * {@code GuiRenderState} : c'est une PURE STRUCTURE DE DONNÉES
 * ({@code add*}/{@code forEach*}/{@code traverse}/{@code reset} — aucune
 * méthode de soumission au GPU). Le flush réel exige de participer au
 * {@code GuiRenderState} PARTAGÉ que vanilla envoie lui-même au GPU chaque
 * frame, via {@code GuiRenderer.render(GpuBufferSlice)} appelé depuis
 * {@code GameRenderer.render(...)}.
 *
 * <h2>Le z-order, appris à la dure</h2>
 *
 * Ordre réel dans {@code GameRenderer.render()} :
 * {@code GuiRenderState.clear()} → {@code new DrawContext(...)} →
 * {@code InGameHud.render(...)} → {@code Screen.render(...)} si un écran est
 * ouvert → toasts/sous-titres → {@code GuiRenderer.render(GpuBufferSlice)}.
 *
 * <p>Vider juste après {@code clear()} faisait de nos icônes les TOUT PREMIERS
 * éléments de la liste — donc dessinées DERRIÈRE le HUD et derrière l'écran
 * d'inventaire ouvert. Et en 1.21.11, vider en tête de
 * {@code GuiRenderer.render} arrive APRÈS la préparation de l'atlas d'items :
 * l'icône est ajoutée, le fond et la barre s'affichent, l'icône non (audit
 * v1101). Le vidage réel se fait donc dans la passe du HUD, par l'hôte
 * {@code GUI_STATE}.
 *
 * <h2>Plus de réflexion (2026-09-13)</h2>
 *
 * Ce fichier cherchait {@code DrawContext}/{@code GuiGraphicsExtractor}, son
 * constructeur, {@code drawItem}, {@code drawItemBar}, {@code drawGuiTexture},
 * {@code blit}, {@code GUI_TEXTURED} et les champs de l'état par NOM Yarn,
 * via {@code McLookup}. C'est ce pont qui avait perdu les icônes deux fois : une
 * surcharge mal choisie (v1105), puis des mappings pas encore chargés (v1112).
 *
 * <p>Il ne garde plus que ce qui ne dépend d'aucune version — la file, les
 * diagnostics — et confie l'appel au jeu au {@link VanillaGuiSink} de la
 * version active ({@link VanillaGuiSink#guiState},
 * {@link VanillaGuiSink#drawVanillaItems}), typé des deux côtés.
 */
final class Blaze3DVanillaItemRenderer {

    private final VanillaItemQueue queue = new VanillaItemQueue("differe");

    // ── Mise en file ──────────────────────────────────────────────────────

    void enqueueItemIcon(Object itemStack, float x, float y, float size,
                         boolean vanillaExtras, int vpWidth, int vpHeight) {
        try {
            queue.enqueueIcon(itemStack, x, y, size, vanillaExtras, vpWidth, vpHeight);
        } catch (Throwable t) {
            reportOnce("mise en file d'une icône : " + t);
        }
    }

    void enqueueGuiBlit(String texturePath, float x, float y, float w, float h,
                        float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        try {
            queue.enqueueBlit(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        } catch (Throwable t) {
            reportOnce("mise en file d'un blit : " + t);
        }
    }

    // ── Vidage ────────────────────────────────────────────────────────────

    /**
     * Vide la file dans l'état de GUI vivant derrière {@code hostObject}.
     *
     * <p>Un hôte que la version ne sert pas laisse la file INTACTE : elle sera
     * vidée par l'hôte suivant qui, lui, est servi. On ne vide qu'après avoir
     * obtenu un état — sinon les éléments seraient perdus.
     */
    void flush(VanillaFlushHost host, Object hostObject) {
        if (hostObject == null) return;
        try {
            VanillaGuiSink sink = VanillaGuiSinks.active();
            if (sink == null) return; // VanillaGuiSinks journalise déjà son absence
            Object guiState = sink.guiState(host, hostObject);
            if (guiState == null) return;

            List<VanillaItemIcon> icons = queue.drainIcons();
            List<VanillaGuiBlit> blits = queue.drainBlits();
            if (icons.isEmpty() && blits.isEmpty()) {
                queue.reportEmptyBatch();
                return;
            }
            queue.reportFirstBatch(icons.size(), blits.size());
            reportFirstFlushHost(host, icons.size());
            sink.drawVanillaItems(guiState, icons, blits);
        } catch (Throwable t) {
            reportOnce("vidage par " + host + " : " + t);
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────

    private boolean hostReported;

    /** Quel point d'accroche a vidé les icônes — c'est ce qui a tranché la régression v1110. */
    private void reportFirstFlushHost(VanillaFlushHost host, int iconCount) {
        if (hostReported || iconCount == 0) return;
        hostReported = true;
        LauncherLog.info("[UiRenderer] itemIconModern: premier vidage d'icônes par l'hôte " + host
            + " (" + iconCount + " icône(s))");
    }

    private String lastReport;

    /** Une ligne par raison DISTINCTE — jamais muet, jamais en boucle à chaque frame. */
    private void reportOnce(String message) {
        if (message.equals(lastReport)) return;
        lastReport = message;
        LauncherLog.err("[UiRenderer] itemIconModern: " + message);
    }
}
