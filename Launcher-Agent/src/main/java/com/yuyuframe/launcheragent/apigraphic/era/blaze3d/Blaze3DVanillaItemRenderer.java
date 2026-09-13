package com.yuyuframe.launcheragent.apigraphic.era.blaze3d;

import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaGuiBlit;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemIcon;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaItemQueue;
import com.yuyuframe.launcheragent.apigraphic.draw.item.VanillaSlotSprite;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McLookup;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
 * <p>Chaîne tracée au bytecode (javap 1.21.11 + classdump maison sur 26.1.2,
 * dont la version de fichier de classe est illisible par javap) :
 * {@code GameRenderer.guiRenderer} → {@code GuiRenderer.state} (Yarn 1.21.11)
 * / {@code renderState} (26.1.2) → consommé par
 * {@code GuiRenderer.render(GpuBufferSlice)}.
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
 * d'inventaire ouvert (bug du panneau shulker « enfin visible, mais derrière
 * l'écran »). D'où le point d'accroche en HEAD de
 * {@code GuiRenderer.render(...)}, après que l'écran a fini d'ajouter son
 * contenu et juste avant la soumission GPU.
 *
 * <p>Extrait tel quel de {@code UiVanillaItemRenderer} : aucune ligne de rendu
 * réécrite. La file, jusqu'ici {@code static} et partagée lexicalement avec
 * l'ère gl3, est désormais la sienne (voir {@link VanillaItemQueue}).
 */
final class Blaze3DVanillaItemRenderer {

    private final VanillaItemQueue queue = new VanillaItemQueue("differe");

    // ── Mise en file ──────────────────────────────────────────────────────

    void enqueueItemIcon(Object itemStack, float x, float y, float size,
                         boolean vanillaExtras, int vpWidth, int vpHeight) {
        try {
            queue.enqueueIcon(itemStack, x, y, size, vanillaExtras, vpWidth, vpHeight);
        } catch (Throwable t) {
            warnOnce("drawVanillaItemIconModernDeferred: " + t);
        }
    }

    void enqueueGuiBlit(String texturePath, float x, float y, float w, float h,
                        float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        try {
            queue.enqueueBlit(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        } catch (Throwable t) {
            warnOnce("drawVanillaContainerTexture: " + t);
        }
    }

    private boolean warned;

    private void warnOnce(String message) {
        if (warned) return;
        warned = true;
        LauncherLog.err("[UiRenderer] " + message);
    }

    // ── Les trois façons d'obtenir l'état de GUI vivant ───────────────────

    private static Field guiRendererField;
    private static Field guiStateField;
    private static boolean resolveFailed;
    private static boolean guiStateFieldMissingReported;

    /**
     * Depuis un {@code GameRenderer} vivant (26.1.2, {@code GuiFlushMixin261}).
     *
     * <p>BUG TROUVÉ (test utilisateur, 26.1.2) : résoudre {@code GameRenderer}
     * par NOM échouait silencieusement alors que {@code GuiRenderer}, résolu
     * par le MÊME patron juste après, réussissait — le thread de rendu n'a pas
     * de façon fiable Knot comme classloader de contexte à ce point précis
     * (contrairement au chemin interne de {@code MappingsRegistry}, qui
     * retombe sur le classloader de l'APPELANT, ce qui expliquait pourquoi
     * {@code GuiRenderer} « marchait par coïncidence »). Fix : on tient déjà
     * l'instance VIVANTE, on résout ses champs sur SA classe réelle — zéro
     * ambiguïté de classloader.
     */
    void flushFromGameRenderer(Object gameRenderer) {
        if (gameRenderer == null) return;
        try {
            if (guiRendererField == null) {
                guiRendererField = McLookup.fieldInHierarchy(gameRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/render/GameRenderer", "guiRenderer"),
                    "guiRenderer");
                if (guiRendererField == null) {
                    resolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: champ guiRenderer introuvable sur "
                        + gameRenderer.getClass());
                    return;
                }
            }
            Object guiRenderer = guiRendererField.get(gameRenderer);
            if (guiRenderer == null) return;
            flushFromGuiRenderer(guiRenderer); // étiquette GUI_RENDERER : la 26.1.2 y remonte
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    /**
     * Depuis un {@code GuiRenderer} vivant (1.21.11, {@code GuiFlushMixin1211},
     * en HEAD de {@code render(GpuBufferSlice)}).
     *
     * <p>Le champ s'appelle {@code state} en Yarn 1.21.11 et {@code renderState}
     * en vrai nom Mojang 26.1.2 (confirmé par javap) — d'où les deux candidats.
     */
    void flushFromGuiRenderer(Object guiRenderer) {
        if (guiRenderer == null) return;
        try {
            if (guiStateField == null) {
                guiStateField = McLookup.fieldInHierarchy(guiRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/gui/render/GuiRenderer", "state"),
                    "state", "renderState");
                if (guiStateField == null) {
                    // Journalisé une fois, sans drapeau définitif : ce chemin
                    // n'est qu'un filet de sécurité en 1.21.11 (le vidage réel
                    // passe par GUI_STATE), son échec ne doit pas bloquer
                    // l'autre. N'arrive plus depuis que MappingsRegistry
                    // charge ses mappings au premier isLoaded() (v1113) — s'il
                    // réapparaît, c'est une vraie erreur, pas un délai.
                    if (!guiStateFieldMissingReported) {
                        guiStateFieldMissingReported = true;
                        LauncherLog.err("[UiRenderer] itemIconModern: champ state/renderState introuvable sur "
                            + guiRenderer.getClass() + " (mappings chargés=" + MappingsRegistry.isLoaded() + ")");
                    }
                    return;
                }
            }
            Object guiState = guiStateField.get(guiRenderer);
            if (guiState == null) return;
            flushInto(guiState, guiState.getClass().getClassLoader(), "GUI_RENDERER");
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIconsFromGuiRenderer: " + t);
        }
    }

    /** Depuis un {@code GuiRenderState} vivant, déjà en main — chemin de {@code VanillaGuiSink1211}. */
    void flushFromGuiState(Object guiState) {
        if (guiState == null) return;
        flushInto(guiState, guiState.getClass().getClassLoader(), "GUI_STATE");
    }

    // ── Le vidage lui-même ────────────────────────────────────────────────

    private static Constructor<?> drawContextCtor;
    private static Method drawItemMethod;
    /** Best-effort : reste {@code null} pour de bon si introuvable, la barre n'est alors pas dessinée. */
    private static Method drawItemBarMethod;
    private static boolean flushResolveFailedReported;

    /**
     * Soumet chaque icône/blit dans le VRAI {@code DrawContext}/
     * {@code GuiGraphicsExtractor} enveloppant l'état PARTAGÉ — condition
     * nécessaire pour que le flush EXISTANT de vanilla, plus loin dans la
     * frame, inclue nos éléments.
     */
    private void flushInto(Object guiState, ClassLoader cl, String host) {
        List<VanillaItemIcon> batch = queue.drainIcons();
        List<VanillaGuiBlit> batchBlits = queue.drainBlits();
        if (batch.isEmpty() && batchBlits.isEmpty()) {
            queue.reportEmptyBatch();
            return;
        }
        queue.reportFirstBatch(batch.size(), batchBlits.size());
        reportFirstFlushHost(host, batch.size());
        if (resolveFailed) {
            if (!flushResolveFailedReported) {
                flushResolveFailedReported = true;
                LauncherLog.err("[UiRenderer] itemIconModern: vidage ABANDONNÉ —"
                    + " une résolution a échoué plus tôt (voir l'erreur précédente)");
            }
            return;
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;

            if (drawContextCtor == null) {
                // DrawContext (Yarn 1.21.11) == GuiGraphicsExtractor (vrai nom
                // Mojang 26.1.2, confirmé par javap — PAS "GuiGraphics" : la
                // classe a été repositionnée en « extracteur » d'état vers
                // GuiRenderState dans l'architecture de rendu différé).
                Class<?> drawContextClass = McLookup.classByLoader(cl,
                    MappingsRegistry.getObfClassDot("net/minecraft/client/gui/DrawContext"),
                    "net.minecraft.client.gui.GuiGraphicsExtractor");
                if (drawContextClass == null) {
                    resolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: classe DrawContext/GuiGraphicsExtractor introuvable");
                    return;
                }
                // guiState.getClass() (type RUNTIME concret) plutôt qu'un type
                // de champ mis en cache : le chemin 1.21.11 n'a jamais résolu
                // guiStateField (il reçoit l'état directement), et ce type
                // reste correct pour le chemin 26.1.2.
                drawContextCtor = drawContextClass.getDeclaredConstructor(
                    mc.getClass(), guiState.getClass(), int.class, int.class);
                drawContextCtor.setAccessible(true);
            }
            Class<?> drawContextClass = drawContextCtor.getDeclaringClass();

            // "drawItem"/"drawItemBar" ont besoin d'une classe ItemStack
            // CONCRÈTE pour se résoudre — reportés ici, gardés par batch non
            // vide, plutôt qu'avec le constructeur ci-dessus : un fond de
            // conteneur peut arriver SEUL, sans la moindre icône, sur une
            // shulker box entièrement vide, et resterait bloqué pour toujours
            // si cette résolution dépendait de batch.get(0).
            if (!batch.isEmpty() && drawItemMethod == null) {
                // "drawItem" (Yarn 1.21.11) == "item" (vrai nom Mojang 26.1.2).
                drawItemMethod = McLookup.methodInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext",
                    "(Lnet/minecraft/item/ItemStack;II)V", "drawItem", "item");
                if (drawItemMethod == null) {
                    resolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: méthode drawItem/item introuvable sur " + drawContextClass);
                    return;
                }
                // "drawItemBar" == "itemBar" — best-effort, jamais fatal.
                drawItemBarMethod = McLookup.methodInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext",
                    "(Lnet/minecraft/item/ItemStack;II)V", "drawItemBar", "itemBar");

                // Fond de case vanilla — best-effort, ne fait jamais échouer le
                // reste (icône/barre continuent même si CETTE partie échoue).
                resolveSlotSprite(cl, drawContextClass);
            }

            Object drawContext = drawContextCtor.newInstance(mc, guiState, 0, 0);
            reportResolution(batch);

            // Fond de fenêtre de conteneur — dessiné AVANT les icônes, sinon il
            // les recouvrirait.
            if (!batchBlits.isEmpty()) {
                resolveSlotSprite(cl, drawContextClass); // pipeline GUI_TEXTURED partagée
                resolveContainerBlit(cl, drawContextClass);
                if (blitMethod != null && renderPipelineGuiTextured != null) {
                    for (VanillaGuiBlit blit : batchBlits) {
                        Object identifier = McLookup.vanillaIdentifier(cl, blit.texturePath);
                        if (identifier == null) continue;
                        blitMethod.invoke(drawContext, renderPipelineGuiTextured, identifier,
                            blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                            Math.round(blit.texW), Math.round(blit.texH));
                    }
                }
            }

            // Taille NATIVE (16x16 GUI-pixels, comme vanilla) — pas de mise à
            // l'échelle ici (pas de manipulation du Matrix3x2fStack de
            // DrawContext), contrairement au glScalef de l'ère gl2 : les icônes
            // sortent à leur taille vanilla plutôt qu'au "size" demandé.
            for (VanillaItemIcon icon : batch) {
                if (icon.vanillaExtras && drawGuiTextureMethod != null) {
                    // Sprite 29x24, icône 16x16 à l'offset (3,4) en son sein —
                    // AVANT l'icône, sinon il la recouvrirait.
                    drawGuiTextureMethod.invoke(drawContext, renderPipelineGuiTextured,
                        slotSpriteIdentifier, icon.guiX - VanillaSlotSprite.ICON_DX,
                        icon.guiY - VanillaSlotSprite.ICON_DY,
                        VanillaSlotSprite.W, VanillaSlotSprite.H);
                }
                drawItemMethod.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                if (icon.vanillaExtras && drawItemBarMethod != null) {
                    drawItemBarMethod.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    // ── Diagnostics one-shot (icônes absentes en 1.21.11, v1110) ──────────
    //
    // Symptôme : fond de case et barre de durabilité visibles, icône absente.
    // Ils encadrent drawItem dans la boucle, donc drawItem est APPELÉ sans
    // exception — l'icône est ajoutée à l'état puis ignorée au rendu. Deux
    // causes possibles, que ces lignes départagent :
    //  - le mauvais point de vidage passe en premier (GUI_RENDERER, après la
    //    préparation de l'atlas d'items en 1.21.11 — cf. audit v1101) ;
    //  - la mauvaise surcharge de drawItem est résolue (cf. v1105).

    private static boolean hostReported, resolutionReported;

    private static void reportFirstFlushHost(String host, int iconCount) {
        if (hostReported || iconCount == 0) return;
        hostReported = true;
        LauncherLog.info("[UiRenderer] itemIconModern: premier vidage d'icônes par l'hôte " + host
            + " (" + iconCount + " icône(s))");
    }

    private static void reportResolution(List<VanillaItemIcon> batch) {
        if (resolutionReported || batch.isEmpty()) return;
        resolutionReported = true;
        VanillaItemIcon first = batch.get(0);
        LauncherLog.info("[UiRenderer] itemIconModern: drawItem=" + drawItemMethod
            + " | drawItemBar=" + drawItemBarMethod + " | drawGuiTexture=" + drawGuiTextureMethod
            + " | 1re icône=" + first.itemStack + " @" + first.guiX + "," + first.guiY);
    }

    // ── Résolutions best-effort ───────────────────────────────────────────

    private static Method blitMethod;
    private static boolean blitResolveFailed;

    /**
     * {@code GuiGraphicsExtractor.blit(RenderPipeline,Identifier,I,I,F,F,I,I,I,I)V}
     * — vérifié par désassemblage de {@code ShulkerBoxScreen.extractBackground}
     * (jar 26.1.2 réel) : appel BRUT, pas via l'atlas de sprites, utilisé par
     * TOUS les fonds de fenêtre de conteneur vanilla.
     *
     * <p>DEUX BUGS successifs sur le seul nom de cette méthode : « blit »
     * n'était pas traduit du tout, puis, une fois traduit, il s'est avéré que
     * « blit » n'est PAS son nom Yarn 1.21.11 — aucune entrée de ce nom
     * n'existe dans les mappings. Le nom Yarn y est {@code drawTexture}
     * (4 surcharges ; celle-ci est {@code method_25291}). Mojang l'a renommée
     * « blit » plus tard, entre 1.21.11 et 26.1.2. L'hypothèse « même nom des
     * deux côtés » n'avait jamais été vérifiée pour CE nom précis.
     */
    private static void resolveContainerBlit(ClassLoader cl, Class<?> drawContextClass) {
        if (blitMethod != null || blitResolveFailed) return;
        try {
            Class<?> identifierClass = McLookup.classByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) { blitResolveFailed = true; return; }
            String blitRuntimeName = MappingsRegistry.getObfMethodName("net/minecraft/client/gui/DrawContext", "drawTexture");
            for (Method m : drawContextClass.getDeclaredMethods()) {
                if (!m.getName().equals(blitRuntimeName) && !m.getName().equals("blit")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 10) continue;
                if (!identifierClass.isAssignableFrom(p[1])) continue;
                if (p[2] != int.class || p[3] != int.class) continue;
                if (p[4] != float.class || p[5] != float.class) continue;
                if (p[6] != int.class || p[7] != int.class || p[8] != int.class || p[9] != int.class) continue;
                m.setAccessible(true);
                blitMethod = m;
                break;
            }
            if (blitMethod == null) {
                blitResolveFailed = true;
                LauncherLog.err("[UiRenderer] containerBlitModern: aucune méthode 10-arg trouvée sur " + drawContextClass
                    + " (blitRuntimeName=" + blitRuntimeName + ", " + drawContextClass.getDeclaredMethods().length + " méthodes déclarées)");
            }
        } catch (Throwable t) {
            blitResolveFailed = true;
            LauncherLog.warn("[UiRenderer] containerBlitModern: résolution échouée : " + t);
        }
    }

    private static Method drawGuiTextureMethod;
    private static Object renderPipelineGuiTextured;
    private static Object slotSpriteIdentifier;
    private static boolean slotSpriteResolveFailed;

    /**
     * Résout {@code drawGuiTexture}/{@code blitSprite} + le pipeline
     * {@code GUI_TEXTURED} + l'{@code Identifier} du sprite de case —
     * best-effort, ne lève jamais.
     *
     * <p>BUG TROUVÉ ET CORRIGÉ (même famille que {@code drawItem}) :
     * {@code GUI_TEXTURED} est un nom de CHAMP Yarn, jamais traduit avant —
     * obfusqué en une lettre sur 1.21.11, tout comme les noms de méthode.
     *
     * <p>{@code drawGuiTexture} (Yarn, {@code method_52706}) ==
     * {@code blitSprite} (vrai nom Mojang 26.1.2) — descripteur
     * {@code (RenderPipeline,Identifier,I,I,I,I)V} vérifié IDENTIQUE sur les
     * deux seuls brackets de cette ère.
     */
    private static void resolveSlotSprite(ClassLoader cl, Class<?> drawContextClass) {
        if (drawGuiTextureMethod != null || slotSpriteResolveFailed) return;
        try {
            Class<?> renderPipelinesClass = McLookup.classByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/client/gl/RenderPipelines"),
                "net.minecraft.client.renderer.RenderPipelines");
            Class<?> identifierClass = McLookup.classByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (renderPipelinesClass == null || identifierClass == null) {
                slotSpriteResolveFailed = true;
                return;
            }

            Field guiTexturedField = McLookup.fieldInHierarchy(renderPipelinesClass,
                MappingsRegistry.getObfFieldName("net/minecraft/client/gl/RenderPipelines", "GUI_TEXTURED"),
                "GUI_TEXTURED");
            if (guiTexturedField == null) { slotSpriteResolveFailed = true; return; }
            renderPipelineGuiTextured = guiTexturedField.get(null);

            slotSpriteIdentifier = McLookup.vanillaIdentifier(cl, VanillaSlotSprite.SPRITE);
            if (slotSpriteIdentifier == null) { slotSpriteResolveFailed = true; return; }

            Class<?> renderPipelineType = guiTexturedField.getType();
            String drawGuiTextureRuntimeName = MappingsRegistry.getObfMethodName(
                "net/minecraft/client/gui/DrawContext", "drawGuiTexture");
            for (Method m : drawContextClass.getDeclaredMethods()) {
                if ((m.getName().equals(drawGuiTextureRuntimeName) || m.getName().equals("blitSprite"))
                        && m.getParameterCount() == 6
                        && renderPipelineType.isAssignableFrom(m.getParameterTypes()[0])
                        && identifierClass.isAssignableFrom(m.getParameterTypes()[1])
                        && m.getParameterTypes()[2] == int.class && m.getParameterTypes()[3] == int.class
                        && m.getParameterTypes()[4] == int.class && m.getParameterTypes()[5] == int.class) {
                    m.setAccessible(true);
                    drawGuiTextureMethod = m;
                    break;
                }
            }
            if (drawGuiTextureMethod == null) slotSpriteResolveFailed = true;
        } catch (Throwable t) {
            slotSpriteResolveFailed = true;
            LauncherLog.warn("[UiRenderer] itemIconModern: fond de case vanilla indisponible : " + t);
        }
    }
}
