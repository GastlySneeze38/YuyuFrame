package com.yuyuframe.launcheragent.mixin.client.v1_8.optimodule;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.module.optimodule.LabelRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cible : net.minecraft.client.render.entity.EntityRenderer (official biv),
 * méthode renderLabelIfPresent(T,String,DDDI)V (officiel "a", method_6917).
 *
 * CORRECTIF IMPORTANT (trouvé en test réel, pas en théorie) : la toute
 * première version de ce fichier capturait le 1er paramètre (l'entité,
 * générique T effacé en {@code pk}) en {@code Object}, en suivant ce qui
 * semblait être le pattern établi ailleurs dans ce projet
 * (EntityCullingMixin). En conditions réelles, log Mixin à l'appui, CE
 * PATTERN NE MARCHE PAS DANS CE BOOTSTRAP CUSTOM dès qu'il y a des
 * PRIMITIVES après le paramètre référence dans la signature capturée :
 * {@code InvalidInjectionException: Expected (Lpk;Ljava/lang/String;DDDI...)V
 * but found (Ljava/lang/Object;Ljava/lang/String;DDDI...)V} — Mixin rejette
 * TOUT le Mixin (silencieusement, juste un WARN dans les logs, le jeu
 * continue sans planter) dès qu'UN SEUL de ses handlers a un descripteur
 * invalide. C'est très probablement pour ça qu'EntityCullingMixin
 * (préexistant, pas ajouté cette session) et MixinTileEntityRenderDistance189
 * n'ont eux non plus jamais réellement appliqué leur culling — même
 * diagnostic exact retrouvé dans les logs pour les trois.
 *
 * SOLUTION : ne JAMAIS capturer le paramètre entité (ni aucun paramètre de la
 * cible d'ailleurs) via @Inject/@Redirect. À la place, x/y/z sont capturés
 * par @ModifyVariable en ciblant directement le SLOT de variable locale
 * (index=3/5/7, doubles = 2 slots chacun ; entité=slot1, text=slot2, x=slots
 * 3-4, y=slots5-6, z=slots7-8, maxDistance=slot9 — vérifié par désassemblage
 * bytecode réel des dload_3/dload 5/dload 7/iload 9) — @ModifyVariable ne
 * capture QUE la variable ciblée, jamais besoin de déclarer les paramètres
 * précédents. Tous les autres handlers (cull, rotate, cache largeur, passes
 * de texte, carré de fond) lisent ensuite ces trois champs statiques au lieu
 * de capturer quoi que ce soit de la méthode cible — plus aucun risque de
 * ce type d'erreur.
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderer")
public abstract class MixinLabelRenderDistance189 {

    // ── Capture x/y/z par slot de variable locale (jamais le paramètre entité) ──

    private static double la$curX;
    private static double la$curY;
    private static double la$curZ;

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 3)
    private double la$captureX(double x) { la$curX = x; return x; }

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 5)
    private double la$captureY(double y) { la$curY = y; return y; }

    @ModifyVariable(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), index = 7)
    private double la$captureZ(double z) { la$curZ = z; return z; }

    // ── Coupure totale (au-delà de maxDistance) ─────────────────────────────

    @Inject(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At("HEAD"), cancellable = true)
    private void la$cullDistantLabels(CallbackInfo ci) {
        try {
            LauncherModule module = ModuleRegistry.get("label-render-distance");
            if (module == null || !module.isEnabled() || !(module instanceof LabelRenderDistanceModule)) return;
            float maxDist = ((LabelRenderDistanceModule) module).maxDistance;
            if (la$dist2() > (double) (maxDist * maxDist)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 cull: " + t);
        }
    }

    // ── Item 1 : pas de rotation "billboard" face caméra au-delà de la distance de simplification ──
    // Cible : GlStateManager.rotate(FFFF)V (official bfl.b), appelée 2 fois
    // (rotation Y puis X) pour orienter le label vers la caméra à chaque
    // frame. Au-delà du seuil, on saute l'appel réel : le label garde
    // l'orientation déjà en cours, au lieu de la recalculer chaque frame.

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfl;b(FFFF)V"))
    private void la$skipBillboardRotate(float angle, float rx, float ry, float rz) {
        try {
            if (la$isBeyondSimplifyDistance()) return;
            Method rotate = la$rotateMethod();
            if (rotate != null) rotate.invoke(null, angle, rx, ry, rz);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 rotate: " + t);
        }
    }

    // ── Item 2 : cache de TextRenderer.getStringWidth(String) ──────────────
    // Appelée 3 FOIS dans cette seule méthode avec LE MÊME texte — cache
    // clé=texte, invalidé automatiquement dès que le texte change vraiment,
    // dans TOUS les cas (pas limité par distance).

    private static final Map<String, Integer> la$widthCache = new ConcurrentHashMap<>();

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lavn;a(Ljava/lang/String;)I"))
    private int la$cachedStringWidth(Object fontRenderer, String text) {
        try {
            Integer cached = la$widthCache.get(text);
            if (cached != null) return cached;
            Method getWidth = la$getStringWidthMethod();
            int width = getWidth != null ? (int) getWidth.invoke(fontRenderer, text) : 0;
            la$widthCache.put(text, width);
            return width;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 width: " + t);
            return 0;
        }
    }

    // ── Item 4 : pas de passe "à travers les murs" au-delà de la distance de simplification ──
    // La PREMIÈRE des 2 passes de dessin (ordinal=0, profondeur ignorée,
    // couleur sombre) garde le texte lisible à travers un mur proche ; la
    // seconde (normale) reste toujours dessinée.

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lavn;a(Ljava/lang/String;III)I", ordinal = 0))
    private int la$skipThroughWallsPass(Object fontRenderer, String drawText, int drawX, int drawY, int drawColor) {
        try {
            if (la$isBeyondSimplifyDistance()) return 0;
            Method draw = la$drawStringMethod();
            return draw != null ? (int) draw.invoke(fontRenderer, drawText, drawX, drawY, drawColor) : 0;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 draw: " + t);
            return 0;
        }
    }

    // ── Item 3 : pas de carré de fond semi-transparent au-delà de la distance de simplification ──
    // begin/pos/color/endVertex/draw du Tessellator — quand on saute, begin/
    // endVertex/draw ne font RIEN et pos/color renvoient juste la référence
    // reçue SANS appeler la vraie méthode (aucune donnée écrite dans le
    // buffer) : le Tessellator n'est JAMAIS démarré ni terminé dans ce cas,
    // donc jamais dans un état à moitié fait. Les 5 handlers évaluent
    // indépendamment la même distance (fonction pure, déterministe) : soit
    // TOUS sautent ensemble, soit AUCUN ne saute.

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfd;a(ILbmu;)V"))
    private void la$skipBoxBegin(Object receiver, int drawMode, Object format) {
        try {
            if (la$isBeyondSimplifyDistance()) return;
            Method begin = la$boxBeginMethod();
            if (begin != null) begin.invoke(receiver, drawMode, format);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 boxBegin: " + t);
        }
    }

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfd;b(DDD)Lbfd;"))
    private Object la$skipBoxPos(Object receiver, double px, double py, double pz) {
        try {
            if (la$isBeyondSimplifyDistance()) return receiver;
            Method pos = la$boxPosMethod();
            return pos != null ? pos.invoke(receiver, px, py, pz) : receiver;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 boxPos: " + t);
            return receiver;
        }
    }

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfd;a(FFFF)Lbfd;"))
    private Object la$skipBoxColor(Object receiver, float red, float green, float blue, float alpha) {
        try {
            if (la$isBeyondSimplifyDistance()) return receiver;
            Method color = la$boxColorMethod();
            return color != null ? color.invoke(receiver, red, green, blue, alpha) : receiver;
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 boxColor: " + t);
            return receiver;
        }
    }

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfd;d()V"))
    private void la$skipBoxEndVertex(Object receiver) {
        try {
            if (la$isBeyondSimplifyDistance()) return;
            Method endVertex = la$boxEndVertexMethod();
            if (endVertex != null) endVertex.invoke(receiver);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 boxEndVertex: " + t);
        }
    }

    @Redirect(method = "a(Lpk;Ljava/lang/String;DDDI)V", at = @At(value = "INVOKE", target = "Lbfx;b()V"))
    private void la$skipBoxDraw(Object receiver) {
        try {
            if (la$isBeyondSimplifyDistance()) return;
            Method draw = la$boxDrawMethod();
            if (draw != null) draw.invoke(receiver);
        } catch (Throwable t) {
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 boxDraw: " + t);
        }
    }

    // ── Distance partagée (calculée depuis les champs capturés par @ModifyVariable) ──

    private static double la$dist2() {
        return la$curX * la$curX + la$curY * la$curY + la$curZ * la$curZ;
    }

    private static boolean la$isBeyondSimplifyDistance() {
        LauncherModule module = ModuleRegistry.get("label-render-distance");
        if (module == null || !module.isEnabled() || !(module instanceof LabelRenderDistanceModule)) return false;
        float simplifyDist = ((LabelRenderDistanceModule) module).simplifyDistance;
        return la$dist2() > (double) (simplifyDist * simplifyDist);
    }

    // ── Résolution réflexive partagée ────────────────────────────────────────

    private static Method la$rotate;
    private static Method la$getStringWidth;
    private static Method la$drawString;
    private static Method la$boxBegin;
    private static Method la$boxPos;
    private static Method la$boxColor;
    private static Method la$boxEndVertex;
    private static Method la$boxDraw;
    private static boolean la$resolveFailed;

    private static Method la$rotateMethod() { la$resolveGl(); return la$rotate; }
    private static Method la$getStringWidthMethod() { la$resolveGl(); return la$getStringWidth; }
    private static Method la$drawStringMethod() { la$resolveGl(); return la$drawString; }
    private static Method la$boxBeginMethod() { la$resolveGl(); return la$boxBegin; }
    private static Method la$boxPosMethod() { la$resolveGl(); return la$boxPos; }
    private static Method la$boxColorMethod() { la$resolveGl(); return la$boxColor; }
    private static Method la$boxEndVertexMethod() { la$resolveGl(); return la$boxEndVertex; }
    private static Method la$boxDrawMethod() { la$resolveGl(); return la$boxDraw; }

    private static void la$resolveGl() {
        if (la$rotate != null || la$resolveFailed) return;
        try {
            Class<?> glStateManager = McReflect.yarnClass("com/mojang/blaze3d/platform/GlStateManager");
            Class<?> textRenderer = McReflect.yarnClass("net/minecraft/client/font/TextRenderer");
            Class<?> bufferBuilder = McReflect.yarnClass("net/minecraft/client/render/BufferBuilder");
            Class<?> vertexFormat = McReflect.yarnClass("net/minecraft/client/render/VertexFormat");
            Class<?> tessellator = McReflect.yarnClass("net/minecraft/client/render/Tessellator");
            if (glStateManager == null || textRenderer == null || bufferBuilder == null || vertexFormat == null || tessellator == null) {
                la$resolveFailed = true;
                return;
            }
            la$rotate = McReflect.method(glStateManager, "com/mojang/blaze3d/platform/GlStateManager", "rotate",
                float.class, float.class, float.class, float.class);
            la$getStringWidth = McReflect.method(textRenderer, "net/minecraft/client/font/TextRenderer", "getStringWidth", String.class);
            la$drawString = McReflect.method(textRenderer, "net/minecraft/client/font/TextRenderer", "draw", String.class, int.class, int.class, int.class);
            la$boxBegin = McReflect.method(bufferBuilder, "net/minecraft/client/render/BufferBuilder", "begin", int.class, vertexFormat);
            la$boxPos = McReflect.method(bufferBuilder, "net/minecraft/client/render/BufferBuilder", "vertex", double.class, double.class, double.class);
            la$boxColor = McReflect.method(bufferBuilder, "net/minecraft/client/render/BufferBuilder", "color", float.class, float.class, float.class, float.class);
            la$boxEndVertex = McReflect.method(bufferBuilder, "net/minecraft/client/render/BufferBuilder", "next");
            la$boxDraw = McReflect.method(tessellator, "net/minecraft/client/render/Tessellator", "draw");
            if (la$rotate == null || la$getStringWidth == null || la$drawString == null
                || la$boxBegin == null || la$boxPos == null || la$boxColor == null || la$boxEndVertex == null || la$boxDraw == null) {
                la$resolveFailed = true;
            }
        } catch (Throwable t) {
            la$resolveFailed = true;
            LauncherLog.err("[LauncherAgent] MixinLabelRenderDistance189 resolveGl: " + t);
        }
    }
}
