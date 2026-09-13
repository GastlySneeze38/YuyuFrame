package com.yuyuframe.launcheragent.apigraphic.era.gl2;

import com.yuyuframe.launcheragent.apigraphic.era.glsupport.GlBridge;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.lang.reflect.Method;

/**
 * Icône RÉELLE d'un {@code ItemStack} en pipeline FIXE — ère gl2 (≤ 1.16).
 *
 * <p>Seule ère à dessiner l'icône SUR-LE-CHAMP : le pipeline fixe n'a pas de
 * passe de GUI différée à rejoindre, donc pas de file d'attente et pas de point
 * d'accroche de vidage. C'est aussi la seule qui doit manipuler la pile de
 * matrices et sauvegarder l'état GL autour du rendu vanilla.
 *
 * <p>Extrait tel quel de {@code UiVanillaItemRenderer} : aucune ligne de rendu
 * n'a été réécrite, seulement déplacée hors de la portée des deux autres ères.
 *
 * <h2>Conventions</h2>
 *
 * {@code x}/{@code y} en NOTRE convention (coin BAS-gauche de l'icône, origine
 * bas-gauche écran) — convertis en interne vers la convention vanilla (origine
 * HAUT-gauche, Y vers le bas), qu'attend {@code renderInGuiWithOverrides}.
 *
 * <p>{@code size} = taille RÉELLE en pixels physiques.
 * {@code renderInGuiWithOverrides} dessine TOUJOURS un carré de 16 unités :
 * sans notre propre {@code glScalef}, l'icône ressort à 16 pixels PHYSIQUES,
 * minuscule sur un écran moderne (vanilla ne paraît correct que multiplié par
 * son « GUI Scale », que notre pipeline ignore volontairement partout
 * ailleurs).
 *
 * <p>Ortho {@code (0,vpW, vpH,0, 1000,3000)} + {@code translate(0,0,-2000)} :
 * convention de profondeur GUI vanilla historique (LWJGL2) — sans elle, le
 * {@code zLevel} interne du rendu d'item tombe hors de la plage de clipping et
 * l'icône reste invisible malgré un appel « réussi ».
 */
final class Gl2VanillaItemRenderer {

    private final GlBridge gl;

    Gl2VanillaItemRenderer(GlBridge gl) {
        this.gl = gl;
    }

    /** Voir {@code drawVanillaItemIcon} — log de diagnostic une seule fois, pas à chaque frame/icône. */
    private static boolean DIAG_LOGGED = false;
    /** Compteur d'appels, pour limiter le log {@code glGetError()} aux ~2 premières frames. */
    private static int DIAG_CALLS = 0;
    private static final int DIAG_CALL_LIMIT = 12;

    void drawItemIcon(Object itemStack, float x, float y, float size, int vpWidth, int vpHeight) {
        if (itemStack == null) return;
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
            try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " stack=" + itemStack + " entryErr=" + gl.drainGlErrors()); } catch (Throwable ignored) {}
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
            gl.glUseProgram(0);
            // Legacy (1.8.9) — voir captureLegacyGlState()/drawEdgeVignetteLegacy.
            savedGlState = gl.captureLegacyGlState();
            gl.glEnable(0x0DE1); // GL_TEXTURE_2D
            gl.glEnable(0x0B71); // GL_DEPTH_TEST — vanilla s'appuie dessus pour l'ordre icône/overlay
            // Sans ce clear, le depth buffer garde les valeurs laissées par la
            // scène 3D derrière le HUD (ou par l'icône précédente dessinée
            // cette même frame, voir ArmorDurabilityModule qui appelle cette
            // méthode plusieurs fois de suite) — le test de profondeur d'un
            // appel ultérieur pouvait alors échouer au hasard contre ce
            // résidu, rendant certaines icônes invisibles alors que le stack
            // n'était pas null ("seule la première icône s'affiche").
            gl.glClear(0x00000100); // GL_DEPTH_BUFFER_BIT
            gl.glDisable(0x0B44); // GL_CULL_FACE
            // PAS de glDisable(GL_SCISSOR_TEST) — voir UiScrollContainer
            // (javadoc de classe).
            gl.glEnable(0x0BE2);  // GL_BLEND
            gl.glBlendFunc(0x0302, 0x0303); // GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA
            // Unité de texture 0 explicitement — l'overlay (glint d'enchant,
            // barre de durabilité) d'un appel précédent peut avoir laissé une
            // unité de multitexturing non-0 active, faisant échouer le bind
            // de texture du prochain appel (texture "blanche"/non trouvée).
            try { gl.glActiveTexture(0x84C0); } catch (Throwable ignored) {} // GL_TEXTURE0

            gl.matrixMode(0x1701); // GL_PROJECTION
            gl.pushMatrix();
            projPushed = true;
            gl.loadIdentity();
            // Convention GUI vanilla : origine HAUT-gauche, Y vers le bas (INVERSE de la nôtre) — voir javadoc.
            gl.glOrtho(0, vpWidth, vpHeight, 0, 1000, 3000);
            gl.matrixMode(0x1700); // GL_MODELVIEW
            gl.pushMatrix();
            modelPushed = true;
            gl.loadIdentity();
            gl.glTranslatef(0f, 0f, -2000f);

            float zoom = size / 16f;
            gl.glScalef(zoom, zoom, 1f);

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
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " preRenderErr=" + gl.drainGlErrors()); } catch (Throwable ignored) {} }
            try {
                render.invoke(itemRenderer, itemStack, (int) vanillaX, (int) vanillaY);
            } finally {
                if (disableLighting != null) { try { disableLighting.invoke(null); } catch (Throwable ignored) {} }
            }
            if (diag) { try { LauncherLog.info("[UiRenderer] icon#" + diagCall + " postRenderErr=" + gl.drainGlErrors()); } catch (Throwable ignored) {} }
        } catch (Throwable t) {
            LauncherLog.err("[UiRenderer] drawVanillaItemIcon: " + t);
        } finally {
            try { gl.glActiveTexture(0x84C0); gl.glBindTexture(0x0DE1, 0); } catch (Throwable ignored) {} // GL_TEXTURE0, unbind
            try {
                if (modelPushed) { gl.matrixMode(0x1700); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            try {
                if (projPushed) { gl.matrixMode(0x1701); gl.popMatrix(); }
            } catch (Throwable ignored) {}
            gl.restoreLegacyGlState(savedGlState);
        }
    }
}
