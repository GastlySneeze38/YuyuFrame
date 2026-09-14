package com.yuyuframe.launcheragent.apigraphic.era.gl3.item;

import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemQueue;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaSlotSprite;
import com.yuyuframe.launcheragent.apimixin.mapping.McLookup;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Pont vers les renderers vanilla — ère gl3 (1.17 – 1.21.x), chemin
 * « IMMÉDIAT ».
 *
 * <p>Sur cette ère, {@code GuiRenderer}/{@code GuiRenderState} n'existent pas
 * encore (architecture introduite entre 1.21.4 et 1.21.11) :
 * {@code DrawContext.drawItem} dessine dans un {@code VertexConsumerProvider
 * .Immediate} classique, qui se vide lui-même. Il n'y a donc pas d'état
 * partagé à rejoindre — seulement une instance VIVANTE de {@code DrawContext}
 * à recevoir du point d'accroche.
 *
 * <h2>Pourquoi une file quand même</h2>
 *
 * BUG TROUVÉ ET CORRIGÉ EN PROFONDEUR (builds v559-v563) : trois correctifs
 * successifs (rebind VAO, cache shader {@code RenderSystem} invalidé/restauré)
 * ont fait disparaître toute erreur GL mesurable, mais l'icône restait
 * invisible — signe que le problème n'était PAS une case de GL mal configurée,
 * mais l'APPROCHE : construire notre PROPRE {@code DrawContext} isolé après que
 * tout le rendu vanilla de la frame a déjà soumis ses batches, plutôt que de
 * PARTICIPER au rendu en cours.
 *
 * <p>Aucun mod Fabric « normal » ne fait ça : {@code HudRenderCallback} hooke
 * {@code InGameHud.render(DrawContext, RenderTickCounter)} et reçoit l'instance
 * RÉELLE que vanilla utilise pour tout le HUD de la frame. En participant à
 * CELLE-LÀ (déjà dans le bon état GL/shader/VAO, puisque c'est littéralement
 * celle de vanilla), aucun contournement n'est nécessaire.
 *
 * <p>D'où la file : le module dessine dans la passe DU MOD, et l'icône part
 * réellement au tout début du HUD de la frame suivante. Décalage d'une frame,
 * imperceptible.
 *
 * <p>Extrait tel quel de {@code UiVanillaItemRenderer} : la file, jusqu'ici
 * {@code static} et partagée lexicalement avec l'ère blaze3d, est désormais la
 * sienne (voir {@link VanillaItemQueue}).
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
            List<com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit> blits = queue.drainBlits();
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

    // ── Vidage des icônes, dans le DrawContext vivant du HUD ──────────────

    private static Method drawItemMethodImmediate;
    private static Method drawItemBarMethodImmediate;
    private static Method drawFlushMethodImmediate;
    private static boolean itemResolveFailed;

    /**
     * Appelé avec le VRAI paramètre {@code DrawContext} de
     * {@code InGameHud.render(...)} — l'instance vivante de vanilla, jamais une
     * instance reconstruite. Ni VAO ni cache shader à gérer : on ne fait
     * qu'AJOUTER nos commandes à une instance déjà dans le bon état, vidée par
     * vanilla via SON propre mécanisme.
     */
    public void flushItemIcons(Object realDrawContext) {
        List<VanillaItemIcon> batch = queue.drainIcons();
        if (batch.isEmpty()) return;
        try {
            if (!ensureItemResolved(batch.get(0).itemStack)) {
                warnOnce("flushPendingImmediateItemIcons: résolution réflexion échouée — icônes non dessinées (voir logs diag)");
                return;
            }
            // Case vanilla — best-effort, ne fait jamais échouer le dessin de
            // l'icône. Contrairement au chemin différé, PAS besoin de découper
            // en bandes ici : ce chemin est SYNCHRONE (aucune catégorisation
            // qui imposerait un ordre de dessin fixe), donc l'ordre d'APPEL
            // détermine directement l'ordre de dessin et le sprite COMPLET
            // dessiné AVANT l'icône suffit.
            boolean hasVanillaExtras = false;
            for (VanillaItemIcon icon : batch) if (icon.vanillaExtras) { hasVanillaExtras = true; break; }
            if (hasVanillaExtras) ensureBlitResolved(realDrawContext.getClass());

            for (VanillaItemIcon icon : batch) {
                if (icon.vanillaExtras && drawTextureMethodImmediate != null) {
                    Object mc = McReflect.minecraftClient();
                    Object spriteIdentifier = mc != null
                        ? McLookup.vanillaIdentifier(mc.getClass().getClassLoader(), VanillaSlotSprite.PATH) : null;
                    if (spriteIdentifier != null) {
                        drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxy, spriteIdentifier,
                            icon.guiX - VanillaSlotSprite.ICON_DX, icon.guiY - VanillaSlotSprite.ICON_DY, 0f, 0f,
                            VanillaSlotSprite.W, VanillaSlotSprite.H, VanillaSlotSprite.W, VanillaSlotSprite.H);
                    }
                }
                drawItemMethodImmediate.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                // drawItemBar absent sur 1.20.4 (pas de mapping Yarn pour ce
                // bracket) — résolution à null, ignoré silencieusement : pas de
                // barre, pas d'erreur.
                if (icon.vanillaExtras && drawItemBarMethodImmediate != null) {
                    drawItemBarMethodImmediate.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateItemIcons: " + t);
        }
    }

    private boolean ensureItemResolved(Object itemStack) {
        if (drawItemMethodImmediate != null) return true;
        if (itemResolveFailed) return false;
        try {
            Class<?> drawContextClass = McReflect.yarnClass("net/minecraft/client/gui/DrawContext");
            drawItemMethodImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItem", itemStack.getClass(), int.class, int.class);
            drawFlushMethodImmediate = McReflect.noArgMethod(drawContextClass,
                "net/minecraft/client/gui/DrawContext", "draw");
            // Absente des mappings Yarn 1.20.4 (introduite en 1.21.4) —
            // McReflect.method() ne lève pas dans ce cas, il rend null.
            drawItemBarMethodImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItemBar", itemStack.getClass(), int.class, int.class);

            LauncherLog.info("[UiRenderer] itemIconModernImmediate diag: résolution — drawItem="
                + drawItemMethodImmediate + " draw=" + drawFlushMethodImmediate
                + " drawItemBar=" + drawItemBarMethodImmediate);
            return drawItemMethodImmediate != null && drawFlushMethodImmediate != null;
        } catch (Throwable t) {
            itemResolveFailed = true;
            LauncherLog.err("[UiRenderer] itemIconModernImmediate: résolution échouée : " + t);
            return false;
        }
    }

    // ── Vidage des fonds de conteneur ─────────────────────────────────────

    private static Method drawTextureMethodImmediate;
    private static Object guiTexturedFunctionProxy;
    private static boolean blitResolveFailed;

    /**
     * ⚠️ PLUS AUCUN POINT D'ACCROCHE depuis le 2026-08-31.
     *
     * <p>{@code HandledScreenBlitFlushMixin1214} a été supprimé : il
     * s'injectait dans {@code HandledScreen.render()} à CHAQUE frame, sur tout
     * écran de conteneur, pour vider une file que plus personne ne remplit
     * depuis le retrait de l'aperçu shulker.
     *
     * <p>Conservé tel quel : c'est une vraie capacité du moteur (afficher un
     * fond de fenêtre de conteneur vanilla sur un bracket sans architecture
     * différée) et elle ne coûte rien tant que personne ne l'appelle. La
     * rebrancher demande de recréer ce Mixin — en TAIL de
     * {@code HandledScreen.drawForeground(DrawContext,I,I)V}, APRÈS le
     * fond/les cases/objets mais AVANT les tooltips vanilla, et déclaré
     * DIRECTEMENT sur {@code HandledScreen} et surtout PAS sur la classe
     * {@code Screen} partagée par tous les écrans, nos écrans custom compris
     * (leçon coûteuse de l'ancien aperçu shulker sur ce risque précis).
     */
    public void flushGuiBlits(Object realDrawContext) {
        List<VanillaGuiBlit> batch = queue.drainBlits();
        if (batch.isEmpty()) return;
        try {
            if (!ensureBlitResolved(realDrawContext.getClass())) return;
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            ClassLoader cl = mc.getClass().getClassLoader();
            for (VanillaGuiBlit blit : batch) {
                Object identifier = McLookup.vanillaIdentifier(cl, blit.texturePath);
                if (identifier == null) continue;
                drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxy, identifier,
                    blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                    Math.round(blit.texW), Math.round(blit.texH));
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateGuiBlits: " + t);
        }
    }

    /**
     * {@code DrawContext.drawTexture} prend ici un
     * {@code java.util.function.Function<Identifier,RenderLayer>} en premier
     * paramètre — PAS un {@code RenderPipeline} direct comme sur les brackets
     * différés ({@code RenderPipeline} et {@code RenderLayer} sont deux
     * abstractions DIFFÉRENTES, la seconde antérieure). Vérifié par
     * désassemblage de {@code HandledScreen} (jar 1.21.4 réel, table
     * BootstrapMethods : {@code REF_invokeStatic gmj.H:(Lakv;)Lgmj;}, où
     * {@code gmj}=RenderLayer, confirmé Yarn named {@code getGuiTextured}).
     *
     * <p>Comme {@code Function} est une interface JDK standard (jamais
     * obfusquée), on construit nous-mêmes un {@link java.lang.reflect.Proxy}
     * qui délègue {@code apply()} à cette méthode statique — pas besoin de
     * reproduire le lambda vanilla.
     */
    private boolean ensureBlitResolved(Class<?> drawContextClass) {
        if (drawTextureMethodImmediate != null) return true;
        if (blitResolveFailed) return false;
        try {
            Class<?> identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier", "net.minecraft.resources.Identifier");
            if (identifierClass == null) { blitResolveFailed = true; return false; }

            Class<?> renderLayerClass = McReflect.yarnClass("net/minecraft/client/render/RenderLayer");
            if (renderLayerClass == null) { blitResolveFailed = true; return false; }
            Method getGuiTextured = McReflect.methodOnClass(
                "net/minecraft/client/render/RenderLayer", "getGuiTextured", identifierClass);
            if (getGuiTextured == null) { blitResolveFailed = true; return false; }

            Class<?> functionClass = java.util.function.Function.class;
            guiTexturedFunctionProxy = java.lang.reflect.Proxy.newProxyInstance(
                drawContextClass.getClassLoader(), new Class<?>[]{ functionClass },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "apply": return getGuiTextured.invoke(null, args[0]);
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return "GuiTexturedFunctionProxy";
                    }
                });

            drawTextureMethodImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawTexture", functionClass, identifierClass, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            if (drawTextureMethodImmediate == null) { blitResolveFailed = true; return false; }
            return true;
        } catch (Throwable t) {
            blitResolveFailed = true;
            LauncherLog.err("[UiRenderer] ensureContainerBlitImmediateResolved: " + t);
            return false;
        }
    }
}
