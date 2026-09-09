package com.yuyuframe.launcheragent.apigraphic.render;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.mojang.blaze3d.platform.Window;

import java.lang.reflect.Method;

/**
 * Icône RÉELLE d'un {@code ItemStack} (modèle vanilla, {@code ItemRenderer}/
 * {@code DrawContext}) + fond de fenêtre de conteneur vanilla brut (ex:
 * shulker box) — extrait de UiRenderer (voir sa javadoc de classe pour
 * l'architecture générale à 2 pipelines). PREMIÈRE utilisation de ce pont
 * vers le pipeline vanilla dans le projet, voir ArmorDurabilityModule pour
 * le premier appelant.
 */
public final class UiVanillaItemRenderer {

    private final UiRenderer owner;
    private final GlBridge gl;

    public UiVanillaItemRenderer(UiRenderer owner, GlBridge gl) {
        this.owner = owner;
        this.gl = gl;
    }

    // ── Forwarders GL (voir GlBridge) — gardent les corps de méthode ci-dessous identiques à l'original ──
    private void glUseProgram(int program) throws Exception { gl.glUseProgram(program); }
    private void glEnable(int cap) throws Exception { gl.glEnable(cap); }
    private void glClear(int mask) throws Exception { gl.glClear(mask); }
    private void glDisable(int cap) throws Exception { gl.glDisable(cap); }
    private void glBlendFunc(int sfactor, int dfactor) throws Exception { gl.glBlendFunc(sfactor, dfactor); }
    private void glActiveTexture(int texture) throws Exception { gl.glActiveTexture(texture); }
    private void glBindTexture(int target, int texture) throws Exception { gl.glBindTexture(target, texture); }
    private void matrixMode(int mode) throws Exception { gl.matrixMode(mode); }
    private void pushMatrix() throws Exception { gl.pushMatrix(); }
    private void popMatrix() throws Exception { gl.popMatrix(); }
    private void loadIdentity() throws Exception { gl.loadIdentity(); }
    private void glOrtho(double left, double right, double bottom, double top, double near, double far) throws Exception { gl.glOrtho(left, right, bottom, top, near, far); }
    private void glTranslatef(float x, float y, float z) throws Exception { gl.glTranslatef(x, y, z); }
    private void glScalef(float x, float y, float z) throws Exception { gl.glScalef(x, y, z); }
    private int drainGlErrors() throws Exception { return gl.drainGlErrors(); }
    private GlBridge.LegacyGlState captureLegacyGlState() throws Exception { return gl.captureLegacyGlState(); }
    private void restoreLegacyGlState(GlBridge.LegacyGlState state) { gl.restoreLegacyGlState(state); }

    // ── Icône d'objet vanilla (ItemRenderer, immediate-mode/fixed-function) ──

    /**
     * Dessine l'icône RÉELLE d'un ItemStack (modèle vanilla, pas un rectangle
     * de substitution) via {@code ItemRenderer.renderInGuiWithOverrides}, en
     * pontant vers le pipeline fixed-function de vanilla depuis notre propre
     * pipeline shader — PREMIÈRE utilisation de ce pont dans le projet, voir
     * ArmorDurabilityModule pour le premier appelant.
     *
     * {@code x}/{@code y} en NOTRE convention (coin BAS-gauche de l'icône,
     * origine bas-gauche écran, comme drawRoundedRect/drawText) — convertis en
     * interne vers la convention vanilla (origine HAUT-gauche, Y vers le bas)
     * car {@code renderInGuiWithOverrides} attend ses coordonnées ainsi.
     *
     * {@code size} — taille RÉELLE souhaitée en pixels physiques (mêmes
     * unités que le reste de notre UI). {@code renderInGuiWithOverrides}
     * dessine TOUJOURS un carré de 16 unités, point — sans notre propre mise à
     * l'échelle ({@code glScalef}), l'icône ressortait à 16 pixels PHYSIQUES
     * bruts, minuscule sur un écran moderne (vanilla ne paraît correct que
     * multiplié par son "GUI Scale", que notre pipeline ignore volontairement
     * partout ailleurs — d'où la nécessité de compenser ici spécifiquement).
     *
     * Ortho (0,vpW, vpH,0, 1000,3000) + translate(0,0,-2000) : convention de
     * profondeur GUI vanilla historique (LWJGL2) — sans elle, le zLevel
     * interne du rendu d'item (petit, proche de 0) tomberait hors de la plage
     * de clipping et l'icône resterait invisible malgré un appel "réussi".
     */
    private static boolean modernItemIconWarned = false;

    // AUDIT PERF (demandé explicitement par l'utilisateur, "gratter des fps
    // 26.1.2") : guiScale() fait 2 invocations de réflexion (getWindow +
    // getScaledWidth) — appelé plusieurs fois PAR FRAME (ArmorDurabilityModule
    // style "Vanilla" : une fois pour drawVanillaHotbarRow, puis une fois DE
    // PLUS par icône dans drawVanillaItemIconModern*, jusqu'à 5x/frame pour ce
    // seul module). La valeur ne dépend QUE de vpWidth ET du réglage "GUI
    // Scale" vanilla — quasi-constante frame à frame (change seulement au
    // resize de fenêtre ou changement du réglage, pas en continu) : cache
    // court (200ms) plutôt qu'un cache infini keyed sur vpWidth seul — un
    // changement du réglage "GUI Scale" SANS resize de fenêtre (vpWidth
    // inchangé) doit rester détecté, juste avec un délai borné au lieu
    // d'être invisible indéfiniment.
    private static float cachedGuiScale = 1f;
    private static int cachedGuiScaleVpWidth = -1;
    private static long cachedGuiScaleAtNanos;
    private static final long GUI_SCALE_CACHE_NANOS = 200_000_000L; // 200 ms

    /**
     * Ratio pixels FRAMEBUFFER / pixels GUI-SCALED de vanilla (voir "GUI
     * Scale" dans les options vidéo) — même calcul que celui dupliqué en
     * interne par drawVanillaItemIconModern* (voir plus bas), exposé ici pour
     * les modules qui doivent positionner du contenu en coordonnées relatives
     * au HUD vanilla (ex: ArmorDurabilityModule, style "Vanilla" — aligné sur
     * la vraie hotbar, 182x22 GUI-pixels, largeur/hauteur bien connues et
     * stables depuis toujours). {@code 1f} si non résolvable (aucune fenêtre
     * trouvée) — mieux qu'une exception pour un appelant qui ferait juste
     * {@code framebufferPx / guiScale(vpWidth)}.
     */
    public static float guiScale(int vpWidth) {
        // 26.1.2 : accessor Mixin, ZÉRO réflexion — et pas de cache non plus.
        //
        // AUDIT (2026-08-31) : c'était le dernier accès réflexif réellement
        // emprunté à chaque frame sur ce bracket (ArmorDurabilityModule et
        // SaturationModule positionnent tout leur rendu dessus). Le cache de
        // 200 ms qui l'entourait n'avait de sens que pour amortir cette
        // réflexion ; deux appels de méthode ne le justifient plus, et le
        // retirer supprime au passage un défaut discret — pendant un
        // redimensionnement de fenêtre, l'échelle restait périmée jusqu'à 200 ms,
        // donc le HUD se posait brièvement au mauvais endroit.
        try {
            Window window = ClientData.window();
            if (window != null) {
                int scaled = window.getGuiScaledWidth();
                if (scaled > 0) return (float) vpWidth / scaled;
            }
        } catch (Throwable ignored) {
            // NoClassDefFoundError attendu hors 26.1.2 (Window/accessor
            // inexistants sur un bracket obfusqué) — le repli réflexif
            // ci-dessous prend le relais, inutile de journaliser par frame.
        }

        // Autres brackets : chemin réflexif inchangé, cache compris.
        long now = System.nanoTime();
        if (vpWidth == cachedGuiScaleVpWidth && (now - cachedGuiScaleAtNanos) < GUI_SCALE_CACHE_NANOS) {
            return cachedGuiScale;
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return 1f;
            Object window = McReflect.method(mc.getClass(), "net/minecraft/client/MinecraftClient", "getWindow", "getWindow").invoke(mc);
            int scaledW = (int) McReflect.method(window.getClass(), "net/minecraft/client/util/Window", "getScaledWidth", "getGuiScaledWidth").invoke(window);
            cachedGuiScale = scaledW > 0 ? (float) vpWidth / scaledW : 1f;
            cachedGuiScaleVpWidth = vpWidth;
            cachedGuiScaleAtNanos = now;
            return cachedGuiScale;
        } catch (Throwable t) {
            return 1f;
        }
    }

    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        drawVanillaItemIcon(itemStack, x, y, size, vpWidth, vpHeight, false);
    }

    /**
     * Blit BRUT (pas via l'atlas de sprites, contrairement au fond de case
     * "hud/hotbar_offhand_left" utilisé par {@link #drawVanillaItemIcon}
     * withDurabilityBar) d'une texture GUI vanilla ARBITRAIRE — ex: fond de
     * fenêtre de conteneur ({@code "textures/gui/container/shulker_box.png"},
     * 256x256, région visible 176x166 dans son coin haut-gauche) — pour
     * {@code ShulkerPreviewModule}, qui veut le VRAI fond vanilla (donc
     * personnalisable par resource pack) plutôt qu'un panneau recréé.
     *
     * @param texturePath chemin RELATIF (sans "assets/minecraft/", avec
     *     l'extension ".png") — ex: {@code "textures/gui/container/shulker_box.png"}.
     * @param x,y,w,h position/taille d'affichage à l'ÉCRAN, en pixels
     *     framebuffer (repère bas-gauche de ce projet — voir javadoc de
     *     classe), PAS en GUI-pixels — même convention que drawVanillaItemIcon.
     * @param u,v,texW,texH région source dans la texture ET dimensions
     *     RÉELLES du fichier PNG (256x256 pour shulker_box.png, PAS
     *     176x166 — c'est un atlas, voir javadoc de ShulkerPreviewModule).
     *
     * Bracket 26.1.2/1.21.11 (chemin "Deferred", voir
     * modernUsesDeferredGuiRenderer) — mise en file, flush différé (voir
     * GuiFlushMixin). 1.20.4/1.21.4 (chemin "Immediate") : voir {@link
     * #drawVanillaContainerTextureModernImmediate} — dessin synchrone, pas de
     * file d'attente. 1.8.9 : no-op silencieux, aucun appelant actuel ne le cible.
     */
    public void drawVanillaContainerTexture(String texturePath, float x, float y, float w, float h,
                                             float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        if (!owner.isModern()) return;
        if (modernUsesDeferredGuiRenderer()) {
            try {
                float guiScale = guiScale(vpWidth);
                int guiX = Math.round(x / guiScale);
                int guiY = Math.round((vpHeight - y - h) / guiScale);
                int guiW = Math.round(w / guiScale);
                int guiH = Math.round(h / guiScale);
                synchronized (pendingModernGuiBlits) {
                    pendingModernGuiBlits.add(new PendingGuiBlit(texturePath, guiX, guiY, guiW, guiH, u, v, texW, texH));
                }
            } catch (Throwable ignored) {}
        } else {
            drawVanillaContainerTextureModernImmediate(texturePath, x, y, w, h, u, v, texW, texH, vpWidth, vpHeight);
        }
    }

    private static Method drawTextureMethodImmediate;
    private static Method getGuiTexturedMethodImmediate;
    private static Object guiTexturedFunctionProxyImmediate;
    private static boolean containerBlitImmediateResolveFailed;

    /**
     * Bracket 1.20.4/1.21.4 (pipeline "Immediate", pas de {@code
     * RenderPipeline}/{@code GuiRenderState} — voir {@link
     * #drawVanillaItemIconModernImmediate}) — {@code DrawContext.drawTexture}
     * y prend un {@code java.util.function.Function<Identifier,RenderLayer>}
     * en premier paramètre (PAS un {@code RenderPipeline} direct comme sur
     * 1.21.11/26.1.2 — {@code RenderPipeline}/{@code RenderLayer} sont deux
     * abstractions DIFFÉRENTES, la seconde antérieure à la première, voir
     * l'audit modules pour le contexte), résolu à la compilation vanilla via
     * une référence de méthode statique ({@code RenderLayer::getGuiTextured})
     * — vérifié par désassemblage bytecode de {@code HandledScreen} (jar
     * 1.21.4 réel, table BootstrapMethods : {@code REF_invokeStatic
     * gmj.H:(Lakv;)Lgmj;}, où {@code gmj}=RenderLayer, confirmé Yarn named
     * "getGuiTextured"). Comme {@code java.util.function.Function} est une
     * interface JDK standard (jamais obfusquée), on peut construire nous-
     * mêmes un {@link java.lang.reflect.Proxy} qui délègue {@code apply()} à
     * cette méthode statique — pas besoin de reproduire le lambda vanilla.
     */
    /**
     * BUG TROUVÉ ET CORRIGÉ (audit modules, "TODO 1.21.4" point 3) : cette
     * méthode construisait sa PROPRE instance {@code DrawContext} isolée
     * (comme {@code drawVanillaItemIconModernImmediate} avant son propre fix,
     * voir bug "mauvaise APPROCHE" dans module-bracket-audit.md) — même
     * classe de problème structurel (état GL imprévisible hors du flux de
     * rendu vivant), jamais porté sur le correctif "file d'attente + point
     * d'accroche vivant" qui a débloqué l'armure, car ce fond de fenêtre a
     * une contrainte de z-order DIFFÉRENTE : doit apparaître PAR-DESSUS
     * l'écran d'inventaire ouvert, donc se dessiner APRÈS
     * {@code Screen.render()} — {@code InGameHud.render()} (point d'accroche
     * de l'armure) s'exécute AVANT l'écran, inutilisable ici tel quel.
     *
     * Correctif : mise en FILE D'ATTENTE ({@code pendingModernGuiBlits},
     * partagée avec le chemin "Deferred") au lieu d'un dessin synchrone
     * isolé — vidée par {@link #flushPendingImmediateGuiBlits(Object)},
     * appelé depuis un point d'accroche Mixin en TAIL de {@code
     * HandledScreen.drawForeground(DrawContext,I,I)V} — mixin
     * {@code HandledScreenBlitFlushMixin1214}, SUPPRIMÉ le 2026-08-31 avec le
     * dernier consommateur de cette file (voir
     * {@link #flushPendingImmediateGuiBlits}). Il était déclaré DIRECTEMENT
     * sur {@code HandledScreen} (PAS la classe {@code Screen} partagée par
     * tous les écrans, y compris nos écrans custom — leçon coûteuse de
     * l'ancien aperçu shulker sur ce risque précis), et appelé APRÈS le
     * fond/les cases/objets du conteneur mais AVANT les tooltips vanilla —
     * exactement où doit apparaître notre panneau. Décalage d'une frame
     * comme pour les icônes (imperceptible tant que Maj reste maintenue).
     */
    private void drawVanillaContainerTextureModernImmediate(String texturePath, float x, float y, float w, float h,
                                                              float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        try {
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - h) / guiScale);
            int guiW = Math.round(w / guiScale);
            int guiH = Math.round(h / guiScale);
            synchronized (pendingModernGuiBlits) {
                pendingModernGuiBlits.add(new PendingGuiBlit(texturePath, guiX, guiY, guiW, guiH, u, v, texW, texH));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * ⚠️ PLUS AUCUN APPELANT depuis le 2026-08-31.
     *
     * <p>Son unique point d'accroche, {@code HandledScreenBlitFlushMixin1214},
     * a été supprimé : il s'injectait dans {@code HandledScreen.render()} à
     * CHAQUE frame, sur tout écran de conteneur, pour vider une file que plus
     * personne ne remplit depuis le retrait de l'aperçu shulker. Le seul
     * producteur était {@link #drawVanillaContainerTextureModernImmediate},
     * lui-même sans appelant.
     *
     * <p>La méthode et sa file sont conservées telles quelles : elles portent
     * une vraie capacité du moteur (afficher un fond de fenêtre de conteneur
     * vanilla sur le bracket 1.21.4, qui n'a pas d'architecture différée) et
     * ne coûtent plus rien tant que personne ne les appelle. Les rebrancher
     * demande de recréer le mixin — voir l'historique juste au-dessus pour le
     * point d'accroche exact et pourquoi il vise {@code HandledScreen} et non
     * {@code Screen}.
     */
    public void flushPendingImmediateGuiBlits(Object realDrawContext) {
        java.util.List<PendingGuiBlit> batchBlits;
        synchronized (pendingModernGuiBlits) {
            if (pendingModernGuiBlits.isEmpty()) return;
            batchBlits = new java.util.ArrayList<>(pendingModernGuiBlits);
            pendingModernGuiBlits.clear();
        }
        try {
            if (!ensureContainerBlitImmediateResolved(realDrawContext.getClass())) return;
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            ClassLoader cl = mc.getClass().getClassLoader();
            for (PendingGuiBlit blit : batchBlits) {
                Object identifier = resolveTextureIdentifier(cl, blit.texturePath);
                if (identifier == null) continue;
                drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxyImmediate, identifier,
                    blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                    Math.round(blit.texW), Math.round(blit.texH));
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateGuiBlits: " + t);
        }
    }

    private boolean ensureContainerBlitImmediateResolved(Class<?> drawContextClass) {
        if (drawTextureMethodImmediate != null) return true;
        if (containerBlitImmediateResolveFailed) return false;
        try {
            Class<?> identifierClass = McReflect.yarnClass("net/minecraft/util/Identifier", "net.minecraft.resources.Identifier");
            if (identifierClass == null) { containerBlitImmediateResolveFailed = true; return false; }

            Class<?> renderLayerClass = McReflect.yarnClass("net/minecraft/client/render/RenderLayer");
            if (renderLayerClass == null) { containerBlitImmediateResolveFailed = true; return false; }
            getGuiTexturedMethodImmediate = McReflect.methodOnClass(
                "net/minecraft/client/render/RenderLayer", "getGuiTextured", identifierClass);
            if (getGuiTexturedMethodImmediate == null) { containerBlitImmediateResolveFailed = true; return false; }

            Class<?> functionClass = java.util.function.Function.class;
            Method finalMethod = getGuiTexturedMethodImmediate;
            guiTexturedFunctionProxyImmediate = java.lang.reflect.Proxy.newProxyInstance(
                drawContextClass.getClassLoader(), new Class<?>[]{ functionClass },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "apply": return finalMethod.invoke(null, args[0]);
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default: return "GuiTexturedFunctionProxy";
                    }
                });

            drawTextureMethodImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawTexture", functionClass, identifierClass, int.class, int.class,
                float.class, float.class, int.class, int.class, int.class, int.class);
            if (drawTextureMethodImmediate == null) { containerBlitImmediateResolveFailed = true; return false; }
            return true;
        } catch (Throwable t) {
            containerBlitImmediateResolveFailed = true;
            LauncherLog.err("[UiRenderer] ensureContainerBlitImmediateResolved: " + t);
            return false;
        }
    }

    /**
     * @param withDurabilityBar en plus de l'icône, dessine la VRAIE barre de
     *     durabilité vanilla (DrawContext.drawItemBar / GuiGraphicsExtractor.itemBar,
     *     vérifié par mappings Yarn 1.21.4/1.21.11 et javap 26.1.2 réel) — pour
     *     le style "Vanilla" d'ArmorDurabilityModule, voir sa javadoc. PAS
     *     supporté sur le pipeline legacy (1.8.9, ignoré silencieusement) ni
     *     sur le bracket 1.20.4 (pas de drawItemBar dans ses mappings Yarn —
     *     résolution échoue proprement, aucune barre dessinée, pas d'erreur).
     */
    public void drawVanillaItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        if (itemStack == null) return;
        if (owner.isModern()) {
            drawVanillaItemIconModern(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
            return;
        }
        GlBridge.LegacyGlState savedGlState = null;
        boolean projPushed = false, modelPushed = false;
        // Diagnostic glGetError() limité aux DIAG_CALL_LIMIT premiers appels
        // (sinon spam à chaque frame) — glGetError() ne lève PAS d'exception
        // Java, une corruption silencieuse de l'état GL (GL_INVALID_OPERATION
        // etc.) est donc invisible dans les logs habituels malgré aucune
        // exception observée jusqu'ici.
        int diagCall = ++DIAG_CALLS;
        boolean diag = diagCall <= DIAG_CALL_LIMIT;
        if (diag) {
            try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " stack=" + itemStack + " entryErr=" + drainGlErrors()); } catch (Throwable ignored) {}
        }
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object itemRenderer = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getItemRenderer").invoke(mc);
            if (itemRenderer == null) return;
            Method render = McReflect.method(itemRenderer.getClass(), "net/minecraft/client/render/item/ItemRenderer",
                "renderInGuiWithOverrides", itemStack.getClass(), int.class, int.class);
            if (render == null) return;

            // DiffuseLighting = renommage Yarn de RenderHelper (MCP) — voir
            // PvP-Mod/ArmorDurabilityHud.java (référence Forge 1.8.9, seule
            // autre source du repo appelant ce rendu d'item plusieurs fois par
            // frame) : elle pose RenderHelper.enableGUIStandardItemLighting()
            // avant CHAQUE appel et disableStandardItemLighting() après —
            // jamais fait ici avant ce correctif. Sans ces 2 lumières GL
            // positionnées, le modèle 3D de l'item rend avec un éclairage
            // résiduel non garanti d'un appel à l'autre : la 1ère icône profite
            // par chance de l'état laissé par le jeu juste avant le hook HUD,
            // les suivantes héritent de l'état CONSOMMÉ par le rendu précédent
            // — d'où les icônes 2+ affichées comme des formes blanches
            // fragmentées (éclairage/texture incorrects) au lieu du vrai item.
            Class<?> diffuseLighting = McReflect.yarnClass("net/minecraft/client/render/DiffuseLighting");
            Method enableLighting = diffuseLighting != null ? McReflect.method(diffuseLighting, "net/minecraft/client/render/DiffuseLighting", "enable") : null;
            Method disableLighting = diffuseLighting != null ? McReflect.method(diffuseLighting, "net/minecraft/client/render/DiffuseLighting", "disable") : null;
            // Diagnostic UNE SEULE FOIS (voir DIAG_LOGGED) : vérifier que la
            // résolution par réflexion réussit vraiment plutôt que d'échouer
            // silencieusement (yarnClass()/method() avalent leurs exceptions
            // et renvoient null sans logguer) — sinon ce correctif pourrait
            // n'avoir aucun effet sans qu'on le sache.
            if (!DIAG_LOGGED) {
                DIAG_LOGGED = true;
                LauncherLog.info("[UiRenderer] drawVanillaItemIcon diag: diffuseLighting=" + diffuseLighting
                    + " enableLighting=" + enableLighting + " disableLighting=" + disableLighting);
            }

            // Notre shader SDF custom (drawText) OU celui de drawRoundedRect
            // peut être encore actif si une icône PRÉCÉDENTE de cette même
            // boucle a échoué avant d'atteindre son propre glUseProgram(0) de
            // nettoyage (ex: exception avalée) — vanilla rend cette icône en
            // pipeline FIXE (glBegin/glEnd, pas de shader) et interprèterait
            // alors les données de texture RGBA normales de l'item À TRAVERS
            // notre shader SDF (qui les lit comme un champ de distance signée
            // dans le canal alpha) : exactement le genre de rendu "cassé"
            // observé (formes fragmentées au lieu de la vraie icône).
            glUseProgram(0);
            // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
            savedGlState = captureLegacyGlState();
            glEnable(0x0DE1); // GL_TEXTURE_2D
            glEnable(0x0B71); // GL_DEPTH_TEST — vanilla s'appuie dessus pour l'ordre icône/overlay
            // Sans ce clear, le depth buffer garde les valeurs laissées par la
            // scène 3D derrière le HUD (ou par l'icône précédente dessinée
            // cette même frame, voir ArmorDurabilityModule qui appelle cette
            // méthode plusieurs fois de suite) — le test de profondeur d'un
            // appel ultérieur pouvait alors échouer au hasard contre ce
            // résidu, rendant certaines icônes invisibles alors que le stack
            // n'était pas null ("seule la première icône s'affiche").
            glClear(0x00000100); // GL_DEPTH_BUFFER_BIT
            glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe).
            glEnable(0x0BE2);  // GL_BLEND
            glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            // Unité de texture 0 explicitement — l'overlay (glint d'enchant,
            // barre de durabilité) d'un appel précédent peut avoir laissé une
            // unité de multitexturing non-0 active, faisant échouer le bind
            // de texture du prochain appel (texture "blanche"/non trouvée).
            try { glActiveTexture(0x84C0); } catch (Throwable ignored) {} // GL_TEXTURE0

            matrixMode(0x1701); // GL_PROJECTION
            pushMatrix();
            projPushed = true;
            loadIdentity();
            // Convention GUI vanilla : origine HAUT-gauche, Y vers le bas (INVERSE de la nôtre) — voir javadoc.
            glOrtho(0, vpWidth, vpHeight, 0, 1000, 3000);
            matrixMode(0x1700); // GL_MODELVIEW
            pushMatrix();
            modelPushed = true;
            loadIdentity();
            glTranslatef(0f, 0f, -2000f);

            float zoom = size / 16f;
            glScalef(zoom, zoom, 1f);

            // Position calculée en pixels PHYSIQUES (convention vanilla, coin
            // haut-gauche), puis divisée par zoom car glScalef s'applique à
            // TOUT ce qui suit — y compris les coordonnées passées à
            // render.invoke ci-dessous, qui doivent donc être exprimées dans
            // l'espace NON zoomé pour retomber au bon endroit une fois zoomées.
            float vanillaXPhysical = x;
            float vanillaYPhysical = vpHeight - y - size;
            float vanillaX = vanillaXPhysical / zoom;
            float vanillaY = vanillaYPhysical / zoom;

            // Posée/déposée à CHAQUE appel (pas seulement au début/fin du lot
            // de 5 icônes) — exactement le pattern PvP-Mod/ArmorDurabilityHud.
            if (enableLighting != null) { try { enableLighting.invoke(null); } catch (Throwable ignored) {} }
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " preRenderErr=" + drainGlErrors()); } catch (Throwable ignored) {} }
            try {
                render.invoke(itemRenderer, itemStack, (int) vanillaX, (int) vanillaY);
            } finally {
                if (disableLighting != null) { try { disableLighting.invoke(null); } catch (Throwable ignored) {} }
            }
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " postRenderErr=" + drainGlErrors()); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawVanillaItemIcon: " + t);
        } finally {
            try { glActiveTexture(0x84C0); glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {} // GL_TEXTURE0, unbind
            try {
                if (modelPushed) { matrixMode(0x1700); popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { matrixMode(0x1701); popMatrix(); }
            } catch (Throwable ignored) {}
            restoreLegacyGlState(savedGlState);
        }
    }

    /** Voir drawVanillaItemIcon — log de diagnostic une seule fois, pas à chaque frame/icône. */
    private static boolean DIAG_LOGGED = false;
    /** Voir drawVanillaItemIcon — compteur d'appels pour limiter le log glGetError() aux ~2 premières frames seulement. */
    private static int DIAG_CALLS = 0;
    private static final int DIAG_CALL_LIMIT = 12;

    // ── Icône d'objet vanilla — pipeline MODERNE (1.21.11+, 26.1+) ───────────
    //
    // ANCIENNE APPROCHE ABANDONNÉE (voir historique de session) : construire
    // notre PROPRE instance indépendante de GuiRenderState/DrawContext et
    // appeler une méthode "flush" dessus. Invalidée par désassemblage complet
    // du VRAI GuiRenderState (classe non obfusquée en 26.1.2, javap direct) :
    // c'est une PURE STRUCTURE DE DONNÉES (add*/forEach*/traverse/reset — pas
    // de méthode "flush vers le GPU"). Le flush réel exige de participer au
    // GuiRenderState PARTAGÉ que vanilla envoie lui-même au GPU chaque frame
    // via GuiRenderer.render(GpuBufferSlice), appelé DEPUIS
    // GameRenderer.render(...) — tracé bytecode complet (javap 1.21.11 +
    // classdump maison sur 26.1.2, class file version 69 illisible par javap)
    // confirmant la chaîne : GameRenderer.guiRenderer (champ, type
    // GuiRenderer) → GuiRenderer.state/renderState (champ, type
    // GuiRenderState, "state" en Yarn 1.21.11 / "renderState" en 26.1.2 réel)
    // → GuiRenderer.render(GpuBufferSlice) consomme CET état précis.
    //
    // Conséquence : dessiner dans NOTRE PROPRE instance ne sert à rien (jamais
    // consommée par aucun flush) — il faut ajouter nos commandes DANS l'état
    // VIVANT que GameRenderer.guiRenderer va lui-même vider ce frame-ci.
    //
    // Fenêtre de timing (confirmée par trace bytecode complète de
    // GameRenderer.render(DeltaTracker,boolean) réel en 26.1.2, aucun appel
    // reset()/clear() sur guiRenderState nulle part dans cette méthode —
    // l'extraction/peuplement du HUD vanilla dans l'état se fait dans une
    // passe "extract" SÉPARÉE, appelée AVANT que GameRenderer.render() ne
    // soit invoqué) : n'importe quel point de cette méthode AVANT l'appel à
    // guiRenderer.render(GpuBufferSlice) convient — HEAD est le plus simple
    // et le plus sûr (pas de dépendance à un point d'injection au milieu
    // d'une méthode). NON REVÉRIFIÉ bytecode par bytecode pour 1.21.11 lui-
    // même (javap, pas le classdump maison) mais même famille d'architecture
    // (GuiRenderer/GuiRenderState identiques dans les deux versions) — voir
    // GuiFlushMixin (1.21.11) / GuiFlushMixin261 (26.1.2), point d'accroche
    // qui appelle {@link #flushPendingModernItemIcons}.
    //
    // D'où la FILE D'ATTENTE ci-dessous : ArmorDurabilityModule (et tout
    // futur appelant) tourne dans la passe de dessin DU MOD (GlobalUiPresentMixin,
    // APRÈS le blit — voir sa javadoc), donc APRÈS que GameRenderer.render()
    // ait déjà fini d'envoyer l'état au GPU pour CE frame-ci. drawVanillaItemIconModern
    // ne fait donc que METTRE EN FILE la demande (ItemStack + position déjà
    // convertie en coordonnées GUI-scaled) ; elle n'est réellement soumise à
    // l'état vivant qu'au TOUT DÉBUT du frame SUIVANT, par flushPendingModernItemIcons
    // — décalage d'une frame (~8-16ms), imperceptible, technique standard pour
    // participer à une passe de rendu qui s'est déjà terminée pour ce frame.
    //
    // NON VÉRIFIÉ EN JEU (pas d'accès à un client Minecraft depuis cet
    // environnement) : voir les logs "[UiRenderer] itemIconModern" en cas
    // d'icône toujours invisible.
    private static final class PendingItemIcon {
        final Object itemStack; final int guiX; final int guiY; final boolean vanillaExtras;
        PendingItemIcon(Object itemStack, int guiX, int guiY, boolean vanillaExtras) {
            this.itemStack = itemStack; this.guiX = guiX; this.guiY = guiY; this.vanillaExtras = vanillaExtras;
        }
    }

    private static final java.util.List<PendingItemIcon> pendingModernItemIcons = new java.util.ArrayList<>();

    /**
     * File d'attente jumelle de {@link PendingItemIcon} mais pour un blit de
     * texture vanilla BRUTE (pas via l'atlas de sprites — voir
     * {@link #drawVanillaContainerTexture}) : fond de fenêtre de conteneur
     * (ex: {@code textures/gui/container/shulker_box.png}). Même décalage
     * d'une frame, même point de vidage ({@link #flushPendingModernItemIcons}).
     */
    private static final class PendingGuiBlit {
        final String texturePath; final int guiX, guiY, guiW, guiH; final float u, v, texW, texH;
        PendingGuiBlit(String texturePath, int guiX, int guiY, int guiW, int guiH, float u, float v, float texW, float texH) {
            this.texturePath = texturePath; this.guiX = guiX; this.guiY = guiY; this.guiW = guiW; this.guiH = guiH;
            this.u = u; this.v = v; this.texW = texW; this.texH = texH;
        }
    }

    private static final java.util.List<PendingGuiBlit> pendingModernGuiBlits = new java.util.ArrayList<>();
    private static Method blitMethodModern;
    private static boolean blitResolveFailed = false;
    private static final java.util.Map<String, Object> containerTextureIdentifierCache = new java.util.HashMap<>();

    private static java.lang.reflect.Field guiRendererFieldModern;
    private static java.lang.reflect.Field guiStateFieldModern;
    private static java.lang.reflect.Constructor<?> drawContextCtorModern;
    private static Method drawItemMethodModern;
    /** Résolu UNE FOIS dans le même bloc que drawContextCtorModern (voir plus bas) — reste {@code null} pour de bon si introuvable (1.20.4 : pas de drawItemBar dans ses mappings Yarn), voir drawVanillaItemIcon(withDurabilityBar). */
    private static Method drawItemBarMethodModern;
    // Fond de case VANILLA — sprite réel "hud/hotbar_offhand_left.png"
    // (29x24, confirmé présent tel quel dans le jar 26.1.2 réel), PAS une
    // case recréée. Recherché sur GitHub à la demande explicite de
    // l'utilisateur (mods de référence consultés : aucun n'expose de fond de
    // case isolé réutilisable en Java). PREMIER essai avec
    // "container/slot.png" (18x18) — ABANDONNÉ : présent dans le jar et
    // référencé par AbstractContainerScreen (donc pas un fichier mort), mais
    // test utilisateur avec un vrai resource pack (custom UI) confirmé sans
    // effet visuel — la plupart des resource packs de type "clean UI" ne
    // personnalisent QUE les sprites de la famille "hud/hotbar_*" (déjà
    // utilisés nativement pour LA VRAIE case de main secondaire à côté de la
    // hotbar, exactement le même contexte visuel que nos cases d'armure), pas
    // "container/slot.png" (utilisé seulement dans certains écrans
    // d'inventaire spécifiques). Décodage manuel du PNG réel (RGBA8, pas de
    // lib PIL disponible dans l'environnement — parseur zlib+filtres de
    // scanline écrit à la main) : case visible = coin arrondi occupant grosso
    // modo x=[0,22] y=[1,23] du canvas 29x24 (le reste, x>22, est
    // transparent — laissé tel quel, PAS recadré, pour rester au plus près
    // du sprite vanilla réel), zone intérieure (alpha faible/dégradé,
    // destinée à l'icône) = EXACTEMENT 16x16 à l'offset (3,4) depuis le
    // coin haut-gauche du sprite — coïncide pile avec la taille native
    // 16x16 de nos icônes, aucune supposition nécessaire.
    //
    // Dessiné via DrawContext.drawGuiTexture(RenderPipeline,Identifier,x,y,w,h)V
    // (Yarn 1.21.11, method_52706) / GuiGraphicsExtractor.blitSprite (vrai
    // nom 26.1.2, même descripteur) — DISPONIBLE UNIQUEMENT sur les brackets
    // "Deferred" (1.21.11/26.1.2, voir modernUsesDeferredGuiRenderer) : sur
    // 1.20.4/1.21.4 (chemin Immediate), method_52706 existe MAIS avec un
    // descripteur DIFFÉRENT par bracket (1.20.4 : pas de RenderPipeline du
    // tout ; 1.21.4 : un Function<Identifier,RenderPipeline> au lieu d'un
    // RenderPipeline direct — 3 signatures différentes pour le même ID
    // intermediary selon la version, vérifié dans les 3 jeux de mappings
    // Yarn correspondants) — non implémenté pour ces deux brackets (barre de
    // durabilité seule pour 1.21.4, ni case ni barre pour 1.20.4).
    private static final int SLOT_SPRITE_W = 29;
    private static final int SLOT_SPRITE_H = 24;
    /** Décalage (icône 16x16 depuis le coin haut-gauche du sprite 29x24) — voir javadoc ci-dessus. */
    private static final int SLOT_SPRITE_ICON_DX = 3;
    private static final int SLOT_SPRITE_ICON_DY = 4;
    /** Fichier PNG BRUT du sprite (PAS son nom d'atlas "hud/hotbar_offhand_left") — voir bug z-order dans flushIntoGuiState : nécessaire pour un blit "texture brute" avec u/v explicites (crop du cadre uniquement), l'atlas de sprites ne permettant aucun contrôle de région. */
    /** Fichier PNG BRUT du sprite (PAS son nom d'atlas "hud/hotbar_offhand_left") — utilisé par le chemin Immediate (1.21.4, voir flushPendingImmediateItemIcons), qui a besoin d'un chemin de texture direct (pas d'accès à l'atlas de sprites côté résolution "Immediate"). */
    private static final String VANILLA_SLOT_SPRITE_PATH = "textures/gui/sprites/hud/hotbar_offhand_left.png";
    private static Method drawGuiTextureMethodModern;
    private static Object renderPipelineGuiTexturedModern;
    private static Object slotSpriteIdentifierModern;
    private static boolean slotSpriteResolveFailed = false;
    private static boolean modernItemIconResolveFailed = false;

    // GuiRenderer/GuiRenderState (voir ci-dessus) N'EXISTENT PAS en 1.20.4 ni
    // 1.21.4 (confirmé absent des deux mappings Yarn correspondants,
    // grep -c ==0 sur les deux) — architecture introduite entre la 1.21.4 et
    // la 1.21.11. Sur ces deux brackets, DrawContext.drawItem dessine dans un
    // VertexConsumerProvider.Immediate CLASSIQUE, avec sa PROPRE méthode
    // draw()V (method_51452 en 1.20.4, method_51452 aussi en 1.21.4 — même ID
    // intermediary stable) qui flush IMMÉDIATEMENT, en autonomie — pas besoin
    // de participer à un état partagé ni d'un second point d'accroche Mixin.
    // Détection automatique (essai de résolution de GuiRenderer) plutôt que
    // par bracket en dur : suffisant et se généralise tout seul si une future
    // version régresse ou avance cette bascule d'architecture.
    private static Boolean modernUsesDeferredGuiRenderer;

    private static boolean modernUsesDeferredGuiRenderer() {
        if (modernUsesDeferredGuiRenderer == null) {
            modernUsesDeferredGuiRenderer = McReflect.yarnClass(
                "net/minecraft/client/gui/render/GuiRenderer",
                "net.minecraft.client.gui.render.GuiRenderer") != null;
        }
        return modernUsesDeferredGuiRenderer;
    }

    private void drawVanillaItemIconModern(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        if (modernUsesDeferredGuiRenderer()) {
            drawVanillaItemIconModernDeferred(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
        } else {
            drawVanillaItemIconModernImmediate(itemStack, x, y, size, vpWidth, vpHeight, withDurabilityBar);
        }
    }

    /**
     * BUG TROUVÉ (test utilisateur : icônes dans le "mauvais ordre" par
     * rapport au texte de durabilité) : {@code guiY = y / guiScale} traitait
     * {@code y} (convention de CE projet, origine BAS-gauche — voir javadoc
     * de classe et drawIcon) comme si c'était DÉJÀ une coordonnée GUI vanilla
     * (origine HAUT-gauche) — un simple ratio, JAMAIS de flip. Le pipeline
     * LEGACY (1.8.9, drawVanillaItemIcon ci-dessus, ligne ~1649) fait ce flip
     * correctement depuis le début : {@code vpHeight - y - size}, oublié ici
     * lors de l'écriture initiale du chemin moderne. Sans lui, la position
     * verticale de CHAQUE icône est mal calculée de façon cohérente dans la
     * MÊME direction — visuellement, la pile entière d'icônes apparaît dans
     * l'ordre inverse par rapport au texte (qui, lui, utilise le pipeline de
     * texte custom de ce projet, PAS DrawContext, donc jamais concerné).
     */
    private void drawVanillaItemIconModernDeferred(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        try {
            // DrawContext/GuiGraphicsExtractor attend des coordonnées
            // GUI-SCALED (comme tout le rendu vanilla), PAS les pixels
            // framebuffer bruts que le reste de notre pipeline utilise
            // partout ailleurs — conversion via le ratio framebuffer/
            // scaledWidth de la fenêtre courante, ET flip d'axe Y (voir javadoc).
            // guiScale() met en cache ce ratio en interne (audit perf — voir
            // sa javadoc) : plus besoin de résoudre mc/window ici nous-mêmes.
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - size) / guiScale);

            synchronized (pendingModernItemIcons) {
                pendingModernItemIcons.add(new PendingItemIcon(itemStack, guiX, guiY, withDurabilityBar));
            }
        } catch (Throwable t) {
            if (!modernItemIconWarned) {
                modernItemIconWarned = true;
                LauncherLog.err("[UiRenderer] drawVanillaItemIconModernDeferred: " + t);
            }
        }
    }

    private static java.lang.reflect.Constructor<?> drawContextImmediateCtorModern;
    private static Method getBufferBuildersMethodModern;
    private static Method getEntityVertexConsumersMethodModern;
    private static Method drawItemMethodImmediateModern;
    private static Method drawFlushMethodImmediateModern;
    private static boolean modernImmediateResolveFailed = false;

    /**
     * Bracket 1.20.4 / 1.21.4 (voir détection ci-dessus) : DrawContext gère
     * son propre buffer immédiat, auto-suffisant — construit avec le
     * VertexConsumerProvider.Immediate PARTAGÉ de vanilla
     * (MinecraftClient.getBufferBuilders().getEntityVertexConsumers(), déjà
     * lié au bon contexte GL/render target à cet instant) plutôt qu'une
     * instance isolée, puis vidé immédiatement via son propre draw()V — pas
     * de file d'attente ni de second point d'accroche Mixin nécessaires ici
     * (contrairement au bracket 1.21.11+/26.1+, voir drawVanillaItemIconModernDeferred).
     */
    /**
     * BUG TROUVÉ ET CORRIGÉ EN PROFONDEUR (test utilisateur, builds v559-v563,
     * après recherche externe — "regarde comment d'autres bibliothèques
     * open-source font") : trois correctifs successifs (rebind VAO, cache
     * shader RenderSystem invalidé/restauré) ont fait disparaître toute
     * erreur GL mesurable, mais l'icône restait invisible malgré tout — signe
     * que le problème n'était PAS une case de GL mal configurée en particulier,
     * mais l'APPROCHE ELLE-MÊME : construire notre PROPRE {@code DrawContext}
     * isolé (avec son propre {@code VertexConsumerProvider.Immediate}) APRÈS
     * que tout le rendu vanilla de la frame (monde, HUD, écran) ait déjà eu
     * lieu et potentiellement déjà fermé/soumis ses propres batches, plutôt
     * que de PARTICIPER au rendu vanilla EN COURS.
     *
     * Aucun mod Fabric "normal" ne fait ça — {@code HudRenderCallback}
     * (l'équivalent standard pour dessiner par-dessus le HUD) hooke
     * directement {@code InGameHud.render(DrawContext, RenderTickCounter)}
     * et reçoit en paramètre l'instance RÉELLE et VIVANTE de
     * {@code DrawContext} que vanilla lui-même utilise pour TOUT le HUD de
     * cette frame — jamais une instance reconstruite à la main après coup.
     * En participant à CETTE instance (déjà dans le bon état GL/shader/VAO,
     * puisque c'est litéralement celle que vanilla utilise), aucun des
     * contournements ci-dessus (VAO, cache shader) n'est nécessaire : c'est
     * vanilla lui-même qui la construit, la configure ET la vide.
     *
     * Correctif : mise en FILE D'ATTENTE (comme le pipeline "Deferred"
     * 1.21.11/26.1.2, {@link #drawVanillaItemIconModernDeferred}/{@link
     * #pendingModernItemIcons}) au lieu d'un dessin synchrone isolé — vidée
     * par {@link #flushPendingImmediateItemIcons(Object)}, appelé depuis un
     * NOUVEAU point d'accroche Mixin en TAIL de {@code InGameHud.render(...)}
     * (voir {@code HudItemFlushMixin1214}), avec le VRAI paramètre
     * {@code DrawContext} de cet appel. Décalage d'une frame comme le
     * pipeline Deferred (imperceptible, ~8-16ms) : l'icône demandée dans
     * cette frame-ci est effectivement dessinée au tout début du rendu HUD de
     * la frame SUIVANTE.
     */
    private void drawVanillaItemIconModernImmediate(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight, boolean withDurabilityBar) {
        try {
            float guiScale = guiScale(vpWidth);
            int guiX = Math.round(x / guiScale);
            int guiY = Math.round((vpHeight - y - size) / guiScale);

            synchronized (pendingModernItemIcons) {
                pendingModernItemIcons.add(new PendingItemIcon(itemStack, guiX, guiY, withDurabilityBar));
            }
        } catch (Throwable t) {
            if (!modernItemIconWarned) {
                modernItemIconWarned = true;
                LauncherLog.err("[UiRenderer] drawVanillaItemIconModernImmediate: " + t);
            }
        }
    }

    /**
     * Appelé depuis {@code HudItemFlushMixin1214}, en TAIL de
     * {@code InGameHud.render(DrawContext, RenderTickCounter)}, avec le VRAI
     * paramètre {@code DrawContext} de cet appel (l'instance vivante que
     * vanilla utilise pour tout le HUD de cette frame — jamais une instance
     * reconstruite). Voir la javadoc de {@link #drawVanillaItemIconModernImmediate}
     * pour le pourquoi complet. Ni VAO ni cache shader à gérer ici : cette
     * instance est déjà dans l'état GL correct puisque c'est celle de vanilla
     * lui-même — on ne fait qu'y AJOUTER nos propres commandes de dessin,
     * flushées par vanilla via SON PROPRE mécanisme, pas le nôtre.
     */
    public void flushPendingImmediateItemIcons(Object realDrawContext) {
        java.util.List<PendingItemIcon> batch;
        synchronized (pendingModernItemIcons) {
            if (pendingModernItemIcons.isEmpty()) return;
            batch = new java.util.ArrayList<>(pendingModernItemIcons);
            pendingModernItemIcons.clear();
        }
        try {
            if (!ensureModernImmediateResolved(batch.get(0).itemStack)) {
                if (!modernItemIconWarned) {
                    modernItemIconWarned = true;
                    LauncherLog.warn("[UiRenderer] flushPendingImmediateItemIcons: résolution réflexion échouée — icônes non dessinées (voir logs diag)");
                }
                return;
            }
            // Case vanilla ("TODO 1.21.4" point 1, jamais implémentée sur ce
            // bracket avant cette session) — best-effort, ne fait jamais
            // échouer le dessin de l'icône même en cas d'échec ici. Contrairement
            // au chemin "Deferred" (1.21.11/26.1.2, voir flushIntoGuiState), PAS
            // besoin de découper en 4 bandes ici : ce chemin est SYNCHRONE
            // (aucune catégorisation GuiRenderState qui imposerait un ordre de
            // dessin fixe) — l'ordre d'APPEL détermine directement l'ordre de
            // dessin, donc le sprite COMPLET dessiné AVANT l'icône suffit.
            boolean hasVanillaExtras = false;
            for (PendingItemIcon icon : batch) if (icon.vanillaExtras) { hasVanillaExtras = true; break; }
            if (hasVanillaExtras) ensureContainerBlitImmediateResolved(realDrawContext.getClass());

            for (PendingItemIcon icon : batch) {
                if (icon.vanillaExtras && drawTextureMethodImmediate != null) {
                    Object mc = McReflect.minecraftClient();
                    Object spriteIdentifier = mc != null
                        ? resolveTextureIdentifier(mc.getClass().getClassLoader(), VANILLA_SLOT_SPRITE_PATH) : null;
                    if (spriteIdentifier != null) {
                        drawTextureMethodImmediate.invoke(realDrawContext, guiTexturedFunctionProxyImmediate, spriteIdentifier,
                            icon.guiX - SLOT_SPRITE_ICON_DX, icon.guiY - SLOT_SPRITE_ICON_DY, 0f, 0f,
                            SLOT_SPRITE_W, SLOT_SPRITE_H, SLOT_SPRITE_W, SLOT_SPRITE_H);
                    }
                }
                drawItemMethodImmediateModern.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                // drawItemBar absent sur 1.20.4 (pas de mapping Yarn pour ce
                // bracket, voir ensureModernImmediateResolved) — résolution à
                // null, ignoré silencieusement (pas de barre, pas d'erreur).
                if (icon.vanillaExtras && drawItemBarMethodModernImmediate != null) {
                    drawItemBarMethodModernImmediate.invoke(realDrawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingImmediateItemIcons: " + t);
        }
    }

    private static Method drawItemBarMethodModernImmediate;

    private boolean ensureModernImmediateResolved(Object itemStack) {
        if (drawItemMethodImmediateModern != null) return true;
        if (modernImmediateResolveFailed) return false;
        try {
            Class<?> mcClass = McReflect.yarnClass("net/minecraft/client/MinecraftClient");
            getBufferBuildersMethodModern = McReflect.noArgMethod(mcClass,
                "net/minecraft/client/MinecraftClient", "getBufferBuilders");

            Class<?> bufferBuilderStorageClass = McReflect.yarnClass("net/minecraft/client/render/BufferBuilderStorage");
            getEntityVertexConsumersMethodModern = McReflect.noArgMethod(bufferBuilderStorageClass,
                "net/minecraft/client/render/BufferBuilderStorage", "getEntityVertexConsumers");

            Class<?> drawContextClass = McReflect.yarnClass("net/minecraft/client/gui/DrawContext");
            Class<?> vcpImmediateClass = getEntityVertexConsumersMethodModern.getReturnType();
            drawContextImmediateCtorModern = drawContextClass.getDeclaredConstructor(mcClass, vcpImmediateClass);
            drawContextImmediateCtorModern.setAccessible(true);

            drawItemMethodImmediateModern = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItem", itemStack.getClass(), int.class, int.class);
            drawFlushMethodImmediateModern = McReflect.noArgMethod(drawContextClass,
                "net/minecraft/client/gui/DrawContext", "draw");
            // Absent des mappings Yarn 1.20.4 (introduit en 1.21.4) —
            // McReflect.method() ne lève pas d'exception dans ce cas, retourne
            // simplement null (voir javadoc drawVanillaItemIconModernImmediate).
            drawItemBarMethodModernImmediate = McReflect.method(drawContextClass, "net/minecraft/client/gui/DrawContext",
                "drawItemBar", itemStack.getClass(), int.class, int.class);

            LauncherLog.info("[UiRenderer] itemIconModernImmediate diag: résolution OK — drawContextCtor="
                + drawContextImmediateCtorModern + " drawItem=" + drawItemMethodImmediateModern
                + " draw=" + drawFlushMethodImmediateModern + " drawItemBar=" + drawItemBarMethodModernImmediate);
            return drawItemMethodImmediateModern != null && drawFlushMethodImmediateModern != null;
        } catch (Throwable t) {
            modernImmediateResolveFailed = true;
            LauncherLog.err("[UiRenderer] itemIconModernImmediate: résolution échouée : " + t);
            return false;
        }
    }

    /**
     * Appelé depuis GuiFlushMixin261 (26.1.2 — voir sa javadoc) avec {@code
     * gameRenderer == this} (l'instance VIVANTE, fusionnée par Mixin). Résout
     * la chaîne {@code gameRenderer.guiRenderer.state} par réflexion puis
     * délègue à {@link #flushIntoGuiState}.
     *
     * Bracket 1.21.11 : voir {@link #flushPendingModernItemIconsFromState},
     * appelé directement avec le GuiRenderState — PAS ce chemin-ci (voir
     * javadoc de GuiFlushMixin pour le pourquoi : remonter depuis
     * GameRenderer.render() en HEAD ajoutait nos icônes AVANT que
     * GuiRenderState.clear() ne les efface).
     */
    public static void flushPendingModernItemIcons(Object gameRenderer) {
        try {
            // BUG TROUVÉ (test utilisateur, 26.1.2) : résoudre GameRenderer
            // par NOM (McReflect.yarnClass/Class.forName + classloader du
            // thread courant) échouait silencieusement (guiRendererFieldModern
            // restait null) alors que GuiRenderer, résolu par le MÊME patron
            // de code juste après, réussissait — le thread de rendu n'a
            // apparemment pas de façon fiable Knot comme
            // Thread.currentThread().getContextClassLoader() à ce point
            // précis de l'exécution (contrairement au chemin interne de
            // MappingsRegistry.loadClass, qui retombe sur le classloader de
            // l'APPELANT — Knot, puisque MappingsRegistry est une de NOS
            // classes — quand le premier essai échoue, ce qui explique
            // pourquoi GuiRenderer "marchait par coïncidence"). Fix : on a
            // déjà l'instance VIVANTE de GameRenderer ici (paramètre) — on
            // résout ses champs directement sur SA classe réelle
            // (gameRenderer.getClass()), zéro ambiguïté de classloader
            // possible, plutôt que de deviner un nom qualifié + un
            // classloader séparément.
            if (guiRendererFieldModern == null) {
                guiRendererFieldModern = findFieldByNameInHierarchy(gameRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/render/GameRenderer", "guiRenderer"),
                    "guiRenderer");
                if (guiRendererFieldModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: champ guiRenderer introuvable sur "
                        + gameRenderer.getClass());
                    return;
                }
            }

            Object guiRenderer = guiRendererFieldModern.get(gameRenderer);
            if (guiRenderer == null) return;

            // Même logique : champ GuiRenderState résolu sur la classe réelle
            // de l'instance guiRenderer VIVANTE qu'on vient d'obtenir — nommé
            // "state" par Yarn (1.21.11) mais "renderState" en vrai nom
            // Mojang (26.1.2, confirmé par javap).
            if (guiStateFieldModern == null) {
                guiStateFieldModern = findFieldByNameInHierarchy(guiRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/gui/render/GuiRenderer", "state"),
                    "state", "renderState");
                if (guiStateFieldModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: champ state/renderState introuvable sur "
                        + guiRenderer.getClass());
                    return;
                }
            }

            Object guiState = guiStateFieldModern.get(guiRenderer);
            if (guiState == null) return;
            flushIntoGuiState(guiState, guiRenderer.getClass().getClassLoader());
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    /**
     * Bracket 1.21.11 — appelé depuis GuiFlushMixin, en TAIL de
     * {@code GuiRenderState.clear()} (Yarn {@code net/minecraft/client/gui/render/state/GuiRenderState},
     * official {@code gqg}, méthode official {@code e()V} == named {@code
     * clear()V}, vérifié directement dans {@code mappings/mappings.tiny}),
     * avec {@code guiState == this} (l'instance VIVANTE fusionnée par Mixin).
     *
     * BUG TROUVÉ (audit modules, cette session) — remplace l'ancien hook HEAD
     * sur {@code GameRenderer.render()} : trace bytecode d'une session
     * précédente (voir historique) avait déjà repéré que {@code
     * GuiRenderState.clear()} (alors identifié seulement par son nom obfusqué
     * "gqg.e()V", sans entrée Yarn named connue à l'époque) est appelé DANS
     * {@code render()}, APRÈS le point HEAD — toute icône mise en file par
     * HEAD était donc effacée avant le flush GPU réel. Le nom named "clear"
     * EXISTE bien dans les mappings (juste sous un chemin différent de celui
     * cherché à l'époque, {@code .../gui/render/state/...} et pas
     * {@code .../gui/render/...}) — permet de cibler {@code clear()V}
     * directement via {@code @Inject(method=...)}, traduit pour Fabric par le
     * refmap comme n'importe quelle autre entrée de REFMAP_ENTRIES (voir
     * LauncherMixinService), plutôt que d'improviser un {@code @At(INVOKE,
     * target=...)} vers un nom obfusqué brut qui aurait cassé sous Fabric
     * (intermediary) et risqué de faire échouer TOUT le tissage de
     * GameRenderer en cascade (voir leçon ClearOverlaysMixin dans
     * module-bracket-audit.md).
     *
     * Injecter directement sur GuiRenderState.clear() plutôt que d'imiter le
     * hook 26.1.2 (INVOKE après Lighting.setupFor dans GameRenderer.render())
     * évite aussi d'avoir à retrouver le nom obfusqué de l'appel imbriqué
     * ({@code this.k.i.t().a(...)}) sur ce bracket — on obtient directement
     * l'instance GuiRenderState vivante en {@code this}, plus besoin de
     * remonter depuis GameRenderer.guiRenderer.state comme dans
     * {@link #flushPendingModernItemIcons}.
     *
     * NON VÉRIFIÉ EN JEU (pas d'accès à un client 1.21.11 depuis cet
     * environnement) — voir les logs "[UiRenderer] itemIconModern" en cas
     * d'icône toujours invisible malgré ce correctif.
     */
    public static void flushPendingModernItemIconsFromState(Object guiState) {
        if (guiState == null) return;
        flushIntoGuiState(guiState, guiState.getClass().getClassLoader());
    }

    /**
     * BUG TROUVÉ (test utilisateur, 1.21.11) : le panneau shulker s'affichait
     * enfin (fix `drawTexture` ci-dessus) mais DERRIÈRE l'écran d'inventaire
     * — mauvais z-order. Cause : {@code GuiFlushMixin} (voir sa javadoc)
     * accrochait le flush en TAIL de {@code GuiRenderState.clear()V} —
     * vérifié par désassemblage de {@code GameRenderer.render()V} (jar
     * 1.21.11 réel, javap) que {@code clear()} est appelé TRÈS TÔT dans la
     * méthode (juste après la config lumière), AVANT {@code
     * InGameHud.render(...)V} ET AVANT {@code Screen.render(...)V} (l'écran
     * d'inventaire lui-même). Nos icônes/fond ajoutés juste après clear()
     * étaient donc les TOUT PREMIERS éléments de la liste de dessin de
     * GuiRenderState pour cette frame — dessinés EN PREMIER, donc DERRIÈRE
     * tout ce qui est ajouté après (HUD, puis l'écran).
     *
     * Trace bytecode complète de {@code GameRenderer.render()V} : {@code
     * clear()V} → {@code new DrawContext(...)} → {@code InGameHud.render(DrawContext,RenderTickCounter)V}
     * → {@code Screen.render(DrawContext,I,I,F)V} (SI un écran est ouvert) →
     * toasts/subtitles → {@code GuiRenderer.render(GpuBufferSlice)V} (soumission
     * GPU réelle, TOUT le contenu accumulé de la frame y compris l'écran est
     * déjà dans l'état à ce point) → {@code GuiRenderer.incrementFrame()V}.
     *
     * Fix : flush déplacé en HEAD de {@code GuiRenderer.render(GpuBufferSlice)V}
     * (voir {@code GuiFlushMixin}, retargeté) — APRÈS que Screen.render() ait
     * fini d'ajouter tout le contenu de l'écran ouvert, JUSTE AVANT la
     * soumission GPU : nos icônes/fond, ajoutés en DERNIER, se retrouvent
     * dessinés PAR-DESSUS tout le reste, z-order correct. {@code this} dans
     * le nouveau hook est directement l'instance {@code GuiRenderer} (pas
     * {@code GuiRenderState}) — cette méthode résout le champ {@code state}
     * dessus (même résolution que {@link #flushPendingModernItemIcons},
     * jamais exercée sur ce bracket jusqu'ici puisque 1.21.11 utilisait
     * {@link #flushPendingModernItemIconsFromState} directement) avant de
     * déléguer à {@link #flushIntoGuiState}.
     */
    public static void flushPendingModernItemIconsFromGuiRenderer(Object guiRenderer) {
        if (guiRenderer == null) return;
        try {
            if (guiStateFieldModern == null) {
                guiStateFieldModern = findFieldByNameInHierarchy(guiRenderer.getClass(),
                    MappingsRegistry.getObfFieldName("net/minecraft/client/gui/render/GuiRenderer", "state"),
                    "state", "renderState");
                if (guiStateFieldModern == null) {
                    LauncherLog.err("[UiRenderer] itemIconModern: champ state/renderState introuvable sur "
                        + guiRenderer.getClass());
                    return;
                }
            }
            Object guiState = guiStateFieldModern.get(guiRenderer);
            if (guiState == null) return;
            flushIntoGuiState(guiState, guiRenderer.getClass().getClassLoader());
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIconsFromGuiRenderer: " + t);
        }
    }

    /**
     * Vide la file et soumet chaque icône/blit dans le VRAI
     * DrawContext/GuiGraphicsExtractor wrappant l'état PARTAGÉ {@code
     * guiState} (pas une instance isolée) — condition nécessaire pour que le
     * flush EXISTANT de vanilla, plus loin dans le frame, inclue nos icônes.
     * Partagé par les deux brackets modernes (26.1.2 via {@link
     * #flushPendingModernItemIcons}, 1.21.11 via {@link
     * #flushPendingModernItemIconsFromState}) — seule la façon d'OBTENIR
     * {@code guiState} diffère entre les deux.
     */
    private static void flushIntoGuiState(Object guiState, ClassLoader cl) {
        java.util.List<PendingItemIcon> batch;
        synchronized (pendingModernItemIcons) {
            batch = pendingModernItemIcons.isEmpty() ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(pendingModernItemIcons);
            pendingModernItemIcons.clear();
        }
        java.util.List<PendingGuiBlit> batchBlits;
        synchronized (pendingModernGuiBlits) {
            batchBlits = pendingModernGuiBlits.isEmpty() ? java.util.Collections.emptyList()
                : new java.util.ArrayList<>(pendingModernGuiBlits);
            pendingModernGuiBlits.clear();
        }
        if (batch.isEmpty() && batchBlits.isEmpty()) return;
        if (modernItemIconResolveFailed) return;
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;

            if (drawContextCtorModern == null) {
                // DrawContext (Yarn 1.21.11) == GuiGraphicsExtractor (vrai
                // nom Mojang 26.1.2, confirmé par javap — PAS "GuiGraphics" :
                // la classe a été repositionnée en "extracteur" d'état vers
                // GuiRenderState dans la nouvelle architecture de rendu différé).
                Class<?> drawContextClass = resolveClassByLoader(cl,
                    MappingsRegistry.getObfClassDot("net/minecraft/client/gui/DrawContext"),
                    "net.minecraft.client.gui.GuiGraphicsExtractor");
                if (drawContextClass == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: classe DrawContext/GuiGraphicsExtractor introuvable");
                    return;
                }
                // guiState.getClass() (type RUNTIME concret) plutôt qu'un
                // type de champ mis en cache : évite de dépendre de
                // guiStateFieldModern (jamais résolu sur le chemin 1.21.11,
                // voir flushPendingModernItemIconsFromState) tout en restant
                // correct pour le chemin 26.1.2 (guiState y est de toute façon
                // déjà une instance concrète, jamais une sous-classe).
                drawContextCtorModern = drawContextClass.getDeclaredConstructor(
                    mc.getClass(), guiState.getClass(), int.class, int.class);
                drawContextCtorModern.setAccessible(true);
            }
            Class<?> drawContextClass = drawContextCtorModern.getDeclaringClass();

            // "drawItem"/"drawItemBar" (Yarn) nécessitent une classe
            // ItemStack CONCRÈTE pour se résoudre (voir findMethodByNameInHierarchy) —
            // reportés ici, guardés par batch non-vide, plutôt que dans le
            // bloc drawContextCtorModern==null ci-dessus : un fond de
            // conteneur (batchBlits) peut arriver SEUL, sans la moindre
            // icône, sur une shulker box entièrement vide (voir
            // ShulkerPreviewModule) — resterait bloqué pour toujours si cette
            // résolution dépendait de batch.get(0).
            if (!batch.isEmpty() && drawItemMethodModern == null) {
                // "drawItem" (Yarn 1.21.11) == "item" (vrai nom Mojang
                // 26.1.2, confirmé par javap).
                drawItemMethodModern = findMethodByNameInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext", "drawItem", "item");
                if (drawItemMethodModern == null) {
                    modernItemIconResolveFailed = true;
                    LauncherLog.err("[UiRenderer] itemIconModern: méthode drawItem/item introuvable sur " + drawContextClass);
                    return;
                }
                // "drawItemBar" (Yarn 1.21.11) == "itemBar" (vrai nom Mojang
                // 26.1.2, confirmé par javap) — best-effort, jamais fatal si
                // introuvable (reste null, barre juste pas dessinée).
                drawItemBarMethodModern = findMethodByNameInHierarchy(drawContextClass,
                    batch.get(0).itemStack.getClass(), "net/minecraft/client/gui/DrawContext", "drawItemBar", "itemBar");

                // Fond de case vanilla (voir javadoc du champ) — best-effort,
                // ne fait jamais échouer la résolution du reste (icône/barre
                // continuent de fonctionner même si CETTE partie échoue).
                resolveSlotSpriteModern(cl, drawContextClass);
            }

            Object drawContext = drawContextCtorModern.newInstance(mc, guiState, 0, 0);

            // Fond de fenêtre de conteneur (ShulkerPreviewModule) — dessiné
            // AVANT les icônes (sinon il les recouvrirait), voir
            // drawVanillaContainerTexture.
            if (!batchBlits.isEmpty()) {
                resolveSlotSpriteModern(cl, drawContextClass); // pipeline GUI_TEXTURED partagée (voir javadoc du champ)
                resolveContainerBlitModern(cl, drawContextClass);
                if (blitMethodModern != null && renderPipelineGuiTexturedModern != null) {
                    for (PendingGuiBlit blit : batchBlits) {
                        Object identifier = resolveTextureIdentifier(cl, blit.texturePath);
                        if (identifier == null) continue;
                        blitMethodModern.invoke(drawContext, renderPipelineGuiTexturedModern, identifier,
                            blit.guiX, blit.guiY, blit.u, blit.v, blit.guiW, blit.guiH,
                            Math.round(blit.texW), Math.round(blit.texH));
                    }
                }
            }

            // Taille NATIVE (16x16 GUI-pixels, comme vanilla) — pas de mise à
            // l'échelle ici (pas de manipulation du Matrix3x2fStack de
            // DrawContext pour l'instant, contrairement au glScalef legacy) :
            // simplification volontaire pour cette première passe, voir
            // ArmorDurabilityModule pour l'effet (icônes affichées à leur
            // taille vanilla plutôt qu'au "size" demandé).
            for (PendingItemIcon icon : batch) {
                if (icon.vanillaExtras && drawGuiTextureMethodModern != null) {
                    // Sprite 29x24, icône 16x16 à l'offset (3,4) en son sein
                    // (voir javadoc du champ) — AVANT l'icône, sinon elle la
                    // recouvrirait.
                    drawGuiTextureMethodModern.invoke(drawContext, renderPipelineGuiTexturedModern,
                        slotSpriteIdentifierModern, icon.guiX - SLOT_SPRITE_ICON_DX, icon.guiY - SLOT_SPRITE_ICON_DY,
                        SLOT_SPRITE_W, SLOT_SPRITE_H);
                }
                drawItemMethodModern.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                if (icon.vanillaExtras && drawItemBarMethodModern != null) {
                    drawItemBarMethodModern.invoke(drawContext, icon.itemStack, icon.guiX, icon.guiY);
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] flushPendingModernItemIcons: " + t);
        }
    }

    /**
     * Résout {@code GuiGraphicsExtractor.blit(RenderPipeline,Identifier,I,I,F,F,I,I,I,I)V}
     * (vérifié par désassemblage bytecode de {@code ShulkerBoxScreen.extractBackground}
     * dans le vrai jar 26.1.2 — appel BRUT, pas via l'atlas de sprites,
     * utilisé par TOUS les fonds de fenêtre de conteneur vanilla) — best-effort,
     * ne fait jamais échouer la résolution des icônes même en cas d'échec ici.
     */
    private static void resolveContainerBlitModern(ClassLoader cl, Class<?> drawContextClass) {
        if (blitMethodModern != null || blitResolveFailed) return;
        try {
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) { blitResolveFailed = true; return; }
            // BUG TROUVÉ #1 (test utilisateur, 1.21.11) : "blit" comparé ICI
            // tel quel, jamais traduit via MappingsRegistry — corrigé une
            // première fois en traduisant "blit" via getObfMethodName.
            //
            // BUG TROUVÉ #2 (test utilisateur suivant, log :
            // "containerBlitModern: aucune méthode 10-arg trouvée... 96
            // méthodes déclarées", blitRuntimeName=blit — la traduction avait
            // ÉCHOUÉ et renvoyé le nom Yarn tel quel, silencieusement) :
            // "blit" n'est PAS le nom Yarn 1.21.11 de cette méthode — vérifié
            // directement dans mappings/mappings.tiny (section DrawContext,
            // classe "gir") : AUCUNE entrée "blit" n'existe. Le nom Yarn
            // 1.21.11 est en réalité {@code drawTexture} (4 surcharges,
            // method_25290/91/02/93 — la 10-arg exacte recherchée ici est
            // method_25291, {@code (RenderPipeline,Identifier,I,I,F,F,I,I,I,I)V}).
            // Mojang a renommé cette méthode en "blit" seulement PLUS TARD,
            // entre 1.21.11 et 26.1.2 (confirmé par javap sur 26.1.2, où
            // "blit" est bien le nom réel) — l'hypothèse "même nom des deux
            // côtés" n'avait jamais été vérifiée pour CE nom précis,
            // contrairement à drawItem/drawGuiTexture juste au-dessus.
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
                blitMethodModern = m;
                break;
            }
            if (blitMethodModern == null) {
                blitResolveFailed = true;
                LauncherLog.err("[UiRenderer] containerBlitModern: aucune méthode 10-arg trouvée sur " + drawContextClass
                    + " (blitRuntimeName=" + blitRuntimeName + ", " + drawContextClass.getDeclaredMethods().length + " méthodes déclarées)");
            }
        } catch (Throwable t) {
            blitResolveFailed = true;
            LauncherLog.warn("[UiRenderer] containerBlitModern: résolution échouée : " + t);
        }
    }

    /** {@code Identifier.withDefaultNamespace(path)}, mis en cache par chemin — voir resolveSlotSpriteModern pour "ofVanilla"/"withDefaultNamespace". */
    private static Object resolveTextureIdentifier(ClassLoader cl, String path) {
        Object cached = containerTextureIdentifierCache.get(path);
        if (cached != null) return cached;
        try {
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (identifierClass == null) return null;
            Method ofVanilla = findStaticStringMethod(identifierClass, "net/minecraft/util/Identifier", "ofVanilla", "withDefaultNamespace");
            if (ofVanilla == null) return null;
            Object id = ofVanilla.invoke(null, path);
            containerTextureIdentifierCache.put(path, id);
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Résout drawGuiTexture/blitSprite + le pipeline GUI_TEXTURED + l'Identifier
     * du sprite "hud/hotbar_offhand_left" (29x24, vrai sprite vanilla — voir
     * javadoc du champ) — best-effort, ne lève jamais (appelant continue
     * avec le reste même en cas d'échec ici, juste pas de fond de case).
     */
    private static void resolveSlotSpriteModern(ClassLoader cl, Class<?> drawContextClass) {
        if (drawGuiTextureMethodModern != null || slotSpriteResolveFailed) return;
        try {
            Class<?> renderPipelinesClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/client/gl/RenderPipelines"),
                "net.minecraft.client.renderer.RenderPipelines");
            Class<?> identifierClass = resolveClassByLoader(cl,
                MappingsRegistry.getObfClassDot("net/minecraft/util/Identifier"),
                "net.minecraft.resources.Identifier");
            if (renderPipelinesClass == null || identifierClass == null) {
                slotSpriteResolveFailed = true;
                return;
            }

            // BUG TROUVÉ ET CORRIGÉ (même cause que drawItem/item, voir la
            // javadoc de findMethodByNameInHierarchy) : "GUI_TEXTURED" est un
            // nom de CHAMP Yarn, jamais traduit ici avant cette session —
            // obfusqué en une lettre courte sur 1.21.11 tout comme les noms
            // de méthode. Pré-traduit ici via MappingsRegistry.getObfFieldName,
            // même pattern que guiRendererFieldModern/guiStateFieldModern
            // plus haut dans ce fichier (candidat obfusqué en premier, nom
            // Yarn en repli — no-op sûr si la traduction échoue).
            java.lang.reflect.Field guiTexturedField = findFieldByNameInHierarchy(renderPipelinesClass,
                MappingsRegistry.getObfFieldName("net/minecraft/client/gl/RenderPipelines", "GUI_TEXTURED"),
                "GUI_TEXTURED");
            if (guiTexturedField == null) { slotSpriteResolveFailed = true; return; }
            renderPipelineGuiTexturedModern = guiTexturedField.get(null);

            // "ofVanilla" (Yarn 1.21.11) == "withDefaultNamespace" (vrai nom
            // Mojang 26.1.2, confirmé par javap) — les deux prennent juste le
            // chemin, namespace "minecraft" implicite.
            java.lang.reflect.Method ofVanilla = findStaticStringMethod(identifierClass, "net/minecraft/util/Identifier", "ofVanilla", "withDefaultNamespace");
            if (ofVanilla == null) { slotSpriteResolveFailed = true; return; }
            slotSpriteIdentifierModern = ofVanilla.invoke(null, "hud/hotbar_offhand_left");

            // "drawGuiTexture" (Yarn 1.21.11/1.21.4/1.20.4, method_52706) ==
            // "blitSprite" (vrai nom Mojang 26.1.2, confirmé par désassemblage
            // GameRenderer/Gui réels) — descripteur (RenderPipeline,Identifier,I,I,I,I)V
            // vérifié IDENTIQUE sur 1.21.11 et 26.1.2 (les deux seuls brackets
            // couverts par le chemin "Deferred", voir javadoc du champ — 1.20.4/
            // 1.21.4 ont un descripteur différent, non géré ici). Nom traduit
            // via MappingsRegistry (même correctif que ci-dessus) — "drawGuiTexture"
            // littéral ne matchait jamais rien sur 1.21.11 (obfusqué).
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
                    drawGuiTextureMethodModern = m;
                    break;
                }
            }
            if (drawGuiTextureMethodModern == null) slotSpriteResolveFailed = true;
        } catch (Throwable t) {
            slotSpriteResolveFailed = true;
            LauncherLog.warn("[UiRenderer] itemIconModern: fond de case vanilla indisponible : " + t);
        }
    }

    /** {@code candidateNames} = noms YARN, traduits via MappingsRegistry avant comparaison — même correctif que {@link #findMethodByNameInHierarchy}. */
    private static java.lang.reflect.Method findStaticStringMethod(Class<?> owner, String yarnClass, String... candidateNames) {
        for (String name : candidateNames) {
            String runtimeName = MappingsRegistry.getObfMethodName(yarnClass, name);
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(runtimeName) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    /** Cherche {@code candidateNames} (dans l'ordre) comme nom de champ déclaré, en remontant la hiérarchie de {@code owner}. */
    private static java.lang.reflect.Field findFieldByNameInHierarchy(Class<?> owner, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            Class<?> c = owner;
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        }
        return null;
    }

    /**
     * Cherche {@code candidateNames} (dans l'ordre, noms YARN — ex: "drawItem")
     * comme méthode déclarée à 1 argument (assignable depuis {@code argType}) +
     * (int,int), en remontant la hiérarchie de {@code owner}.
     *
     * BUG TROUVÉ ET CORRIGÉ (test utilisateur, 1.21.11, "icônes toujours
     * invisibles" même après le fix GuiFlushMixin) : cette méthode comparait
     * {@code m.getName()} DIRECTEMENT au nom Yarn littéral ("drawItem"/
     * "item"), sans AUCUNE traduction — fonctionne par coïncidence sur 26.1.2
     * (noms Mojang réels, non obfusqués) mais jamais sur un bracket obfusqué
     * comme 1.21.11, où le nom RUNTIME de `DrawContext.drawItem` est une
     * simple lettre obfusquée ("a", confirmé dans mappings/mappings.tiny —
     * 4 surcharges de "drawItem" partagent d'ailleurs TOUTES le même nom
     * officiel "a", désambiguïsées ensuite par le filtre de type ci-dessous).
     * Log réel : `[UiRenderer] itemIconModern: méthode drawItem/item
     * introuvable sur class gir` en boucle, sur CHAQUE frame, alors même que
     * `drawContextClass` (gir) était correctement résolu — la classe était
     * bonne, seule la recherche du NOM DE MÉTHODE dedans ne traduisait rien.
     *
     * Correctif : {@code yarnClass} ajouté, chaque candidat traduit via
     * {@link MappingsRegistry#getObfMethodName(String, String)} avant
     * comparaison — no-op sûr pour les candidats qui ne sont PAS un nom Yarn
     * connu (ex: "item", repli 26.1.2 : aucune entrée Yarn ne matche, la
     * traduction renvoie le nom inchangé). Même pattern que
     * {@code McReflect.method()}, qui fait déjà ça correctement ailleurs dans
     * ce projet — cette copie locale avait simplement été écrite sans cette
     * étape.
     */
    private static Method findMethodByNameInHierarchy(Class<?> owner, Class<?> argType, String yarnClass, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            String runtimeName = MappingsRegistry.getObfMethodName(yarnClass, name);
            Class<?> c = owner;
            while (c != null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(runtimeName) || m.getParameterCount() != 3) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p[0].isAssignableFrom(argType) && p[1] == int.class && p[2] == int.class) {
                        m.setAccessible(true);
                        return m;
                    }
                }
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Essaie chaque nom de classe (dans l'ordre) via {@code Class.forName} avec le classloader EXPLICITE donné — jamais le classloader ambiant du thread courant, voir flushPendingModernItemIcons pour le pourquoi. */
    private static Class<?> resolveClassByLoader(ClassLoader cl, String... candidateNames) {
        for (String name : candidateNames) {
            if (name == null) continue;
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
