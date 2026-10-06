package com.yuyuframe.launcheragent.apimixin.v1_8_9.text;

import com.yuyuframe.launcheragent.celeritas.RendererSwitch;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.entity.LabelBatch189;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Regroupe le dessin du texte de la 1.8.9 ({@code TextRenderer}, {@code avn}) :
 * un envoi par chaîne au lieu de dix appels OpenGL par caractère. Toute la
 * mécanique est dans {@link TextBatch189}.
 *
 * <p>Les deux méthodes qui dessinent un caractère ({@code drawLayerNormal}
 * pour la police de base, {@code drawLayerUnicode} pour les pages unicode)
 * gardent tout leur code : seuls leurs appels {@code glBegin} /
 * {@code glTexCoord2f} / {@code glVertex3f} / {@code glEnd} sont détournés.
 * La géométrie reste donc celle de vanilla, au flottant près.
 *
 * <p>{@code @Redirect} et non {@code @Inject} en tête : il faudrait sinon
 * réécrire les deux méthodes (largeurs, italique, découpe de l'atlas). La
 * 1.8.9 est vanilla seul, aucun autre mod ne se dispute ces sites d'appel.
 *
 * <p>Le tampon est vidé à chaque endroit où {@code draw(String, boolean)}
 * change l'état OpenGL entre deux caractères — relevés dans le bytecode :
 * {@code GlStateManager.color} (codes § de couleur) et
 * {@code Tessellator.getInstance} (traits barré et souligné, précédés d'un
 * {@code disableTexture}).
 */
@Mixin(targets = "net.minecraft.client.font.TextRenderer")
public abstract class TextRendererBatchMixin189 {

    // ── Bornes d'une chaîne ──────────────────────────────────────────────────

    @Inject(method = "draw(Ljava/lang/String;Z)V", at = @At("HEAD"), require = 0)
    private void la$beginString(String text, boolean shadow, CallbackInfo ci) {
        TextBatch189.beginString();
    }

    @Inject(method = "draw(Ljava/lang/String;Z)V", at = @At("RETURN"), require = 0)
    private void la$endString(String text, boolean shadow, CallbackInfo ci) {
        TextBatch189.flush();
    }

    /**
     * Les changements de couleur du texte : la couleur de départ
     * ({@code drawLayer}) et les codes § ({@code draw}). Détournés et non
     * simplement précédés d'un vidage : quand un texte est capturé pour être
     * redessiné ailleurs (étiquettes de nom), la couleur doit être retenue
     * par caractère et OpenGL ne doit pas être touché.
     */
    @Redirect(method = {"draw(Ljava/lang/String;Z)V", "drawLayer(Ljava/lang/String;FFIZ)I"},
              at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;color(FFFF)V"),
              require = 0)
    private void la$color(float red, float green, float blue, float alpha) {
        if (!TextBatch189.onColor(red, green, blue)) {
            TextGl189.color(red, green, blue, alpha);
        }
    }

    @Inject(method = "draw(Ljava/lang/String;Z)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/Tessellator;getInstance()Lnet/minecraft/client/render/Tessellator;"),
            require = 0)
    private void la$beforeLine(String text, boolean shadow, CallbackInfo ci) {
        TextBatch189.flush();
    }

    // ── Police changée ───────────────────────────────────────────────────────

    /** Ressources rechargées : les textes gardés par le regroupement des étiquettes ne valent plus rien. */
    @Inject(method = "reload(Lnet/minecraft/resource/ResourceManager;)V", at = @At("HEAD"), require = 0)
    private void la$fontReloaded(@Coerce Object resourceManager, CallbackInfo ci) {
        if (RendererSwitch.available()) LabelBatch189.clearCache();
    }

    /** Bascule de la police unicode : les mêmes textes n'ont plus les mêmes caractères. */
    @Inject(method = "setUnicode(Z)V", at = @At("HEAD"), require = 0)
    private void la$unicodeToggled(boolean unicode, CallbackInfo ci) {
        if (RendererSwitch.available()) LabelBatch189.clearCache();
    }

    // ── Changement de texture ────────────────────────────────────────────────

    @Inject(method = "drawLayerNormal(IZ)F", at = @At("HEAD"), require = 0)
    private void la$beforeAsciiGlyph(int character, boolean italic, CallbackInfoReturnable<Float> cir) {
        TextBatch189.beginGlyph(TextBatch189.PAGE_ASCII);
    }

    @Inject(method = "drawLayerUnicode(CZ)F", at = @At("HEAD"), require = 0)
    private void la$beforeUnicodeGlyph(char character, boolean italic, CallbackInfoReturnable<Float> cir) {
        TextBatch189.beginGlyph(character / 256);
    }

    // ── Les quatre appels détournés, dans les deux méthodes ──────────────────

    @Redirect(method = {"drawLayerNormal(IZ)F", "drawLayerUnicode(CZ)F"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glBegin(I)V", remap = false),
              require = 0)
    private void la$glBegin(int mode) {
        TextBatch189.glBegin(mode);
    }

    @Redirect(method = {"drawLayerNormal(IZ)F", "drawLayerUnicode(CZ)F"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glTexCoord2f(FF)V", remap = false),
              require = 0)
    private void la$glTexCoord2f(float u, float v) {
        TextBatch189.glTexCoord2f(u, v);
    }

    @Redirect(method = {"drawLayerNormal(IZ)F", "drawLayerUnicode(CZ)F"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glVertex3f(FFF)V", remap = false),
              require = 0)
    private void la$glVertex3f(float x, float y, float z) {
        TextBatch189.glVertex3f(x, y, z);
    }

    @Redirect(method = {"drawLayerNormal(IZ)F", "drawLayerUnicode(CZ)F"},
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glEnd()V", remap = false),
              require = 0)
    private void la$glEnd() {
        TextBatch189.glEnd();
    }
}
