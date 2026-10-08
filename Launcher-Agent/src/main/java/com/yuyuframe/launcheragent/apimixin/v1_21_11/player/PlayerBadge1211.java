package com.yuyuframe.launcheragent.apimixin.v1_21_11.player;

import com.mojang.authlib.GameProfile;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.util.Optional;

/**
 * Le module « Joueur » sur 1.21.11 : le logo en pixels devant le pseudo des
 * joueurs YuyuFrame, et le nametag de son propre joueur. Les règles sont dans
 * {@link PlayerBadge} ; ici, seulement ce qui nomme des types du jeu — sous
 * leur nom Yarn, traduits au chargement ({@link YarnNamed}).
 *
 * <p>Même rendu en deux temps que la 26.x (état copié, puis commandes de
 * dessin soumises), sous d'autres noms. Une différence de structure : ici
 * c'est {@code PlayerEntityRenderer.renderLabelIfPresent} qui soumet seule
 * l'étiquette d'un joueur, avec le décalage des oreilles de deadmau5 calculé
 * sur place — il n'y a pas de surcharge commune à toutes les entités.
 *
 * <p>Chaque membre du jeu est appelé sur la classe qui le DÉCLARE
 * ({@code visit} sur {@code StringVisitable}, {@code submitCustom} sur
 * {@code RenderCommandQueue}…) : le traducteur de noms cherche là, et pas
 * dans les classes filles.
 */
@YarnNamed
public final class PlayerBadge1211 {

    private PlayerBadge1211() {
    }

    /** La pleine lumière, empaquetée comme le jeu l'attend : le logo ne s'assombrit pas la nuit. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Un cheveu devant le fond et le texte de l'étiquette, dessinés dans le même plan. */
    private static final float FORWARD = 0.1F;

    // ── Étiquette ────────────────────────────────────────────────────────────

    /** À la sortie de {@code PlayerEntityRenderer.updateRenderState} : met la place du logo devant le pseudo. */
    public static void afterExtract(Object entityObject, Object stateObject) {
        EntityRenderState state = (EntityRenderState) stateObject;
        Text name = state.displayName;
        if (name == null || !(entityObject instanceof PlayerEntity)) {
            PlayerBadge.unmark(state);
            return;
        }
        GameProfile profile = ((PlayerEntity) entityObject).getGameProfile();
        boolean self = entityObject == AccessorRegistry.get(AccessPoint.CLIENT_PLAYER);
        if (profile == null || !PlayerBadge.marksLabel(profile.id(), profile.name(), self)) {
            PlayerBadge.unmark(state);
            return;
        }
        Padded padded = pad(name, profile.name());
        state.displayName = padded.text;
        PlayerBadge.mark(state, padded.text, padded.lead);
    }

    /** Un pseudo avec la place du logo, et la largeur de ce qui la précède. */
    private static final class Padded {
        Text text;
        float lead;
    }

    /**
     * Met la place du logo juste devant le pseudo du joueur dans le texte
     * affiché — après le grade que beaucoup de serveurs placent avant lui.
     * Même règle que {@code PlayerBadge261.pad} : le texte est refait morceau
     * par morceau, chacun gardant son style, et les espaces que le serveur a
     * mises devant le pseudo sont retirées (l'écart est celui de la réserve).
     */
    private static Padded pad(Text name, String profileName) {
        Padded result = new Padded();
        String full = name.getString();
        int at = profileName == null || profileName.isEmpty() ? -1 : full.indexOf(profileName);
        if (at <= 0) {
            result.text = Text.literal(PlayerBadge.LEAD).append(name);
            result.lead = PlayerBadge.INSET;
            return result;
        }
        int cutAt = at;
        while (cutAt > 0 && full.charAt(cutAt - 1) == ' ') {
            cutAt--;
        }
        final int cut = cutAt;
        MutableText rebuilt = Text.literal("");
        MutableText before = Text.literal("");
        int[] seen = { 0 };
        boolean[] placed = { false };
        StringVisitable parts = name;
        parts.visit((style, text) -> {
            int start = seen[0];
            int end = start + text.length();
            seen[0] = end;
            if (placed[0]) {
                rebuilt.append(Text.literal(text).setStyle(style));
                return Optional.empty();
            }
            // Ce que ce morceau porte avant la coupe : gardé tel quel.
            if (start < cut) {
                String head = text.substring(0, Math.min(end, cut) - start);
                rebuilt.append(Text.literal(head).setStyle(style));
                before.append(Text.literal(head).setStyle(style));
            }
            // Le morceau où commence le pseudo : la réserve, puis la suite. La réserve garde le style par
            // défaut — dans la police d'un pack, une espace peut ne rien mesurer.
            if (at < end) {
                rebuilt.append(Text.literal(PlayerBadge.LEAD));
                rebuilt.append(Text.literal(text.substring(at - start)).setStyle(style));
                placed[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        result.text = rebuilt;
        result.lead = width(before) + PlayerBadge.INSET;
        return result;
    }

    private static float width(Text text) {
        TextRenderer renderer = MinecraftClient.getInstance().textRenderer;
        StringVisitable visitable = text;
        return renderer.getWidth(visitable);
    }

    /**
     * En tête de {@code PlayerEntityRenderer.renderLabelIfPresent} : soumet le
     * logo à l'endroit où vanilla va poser le début de l'étiquette. Mêmes
     * transformations que lui (relevées dans le bytecode) : la ligne de score
     * éventuelle pousse le pseudo d'une ligne, puis ancrage + 0,5, face à la
     * caméra, échelle 0,025 ; le texte commence à moins la moitié de sa largeur.
     */
    public static void beforeLabel(Object stateObject, Object matricesObject, Object queueObject, Object cameraObject) {
        PlayerEntityRenderState player = (PlayerEntityRenderState) stateObject;
        EntityRenderState state = player;
        Text name = state.displayName;
        Vec3d at = state.nameLabelPos;
        float lead = at == null ? -1.0F : PlayerBadge.markedLead(state, name);
        if (lead < 0.0F) {
            return;
        }
        MatrixStack matrices = (MatrixStack) matricesObject;
        RenderCommandQueue queue = (RenderCommandQueue) queueObject;
        float left = -width(name) / 2.0F + lead;
        // Les oreilles de deadmau5 remontent l'étiquette de dix pixels.
        int offsetY = player.extraEars ? -10 : 0;

        matrices.push();
        if (player.playerName != null) {
            matrices.translate(0.0F, 9.0F * 1.15F * 0.025F, 0.0F);
        }
        matrices.translate(at.x, at.y + 0.5, at.z);
        matrices.multiply(((CameraRenderState) cameraObject).orientation);
        matrices.scale(0.025F, -0.025F, 0.025F);
        if (state.sneaking) {
            // Accroupi : comme le pseudo, à demi effacé et jamais à travers les murs.
            queue.submitCustom(matrices, RenderLayers.textBackground(),
                (entry, buffer) -> logo(entry, buffer, left, offsetY, 0x80));
        } else {
            queue.submitCustom(matrices, RenderLayers.textBackgroundSeeThrough(),
                (entry, buffer) -> logo(entry, buffer, left, offsetY, 0x40));
            queue.submitCustom(matrices, RenderLayers.textBackground(),
                (entry, buffer) -> logo(entry, buffer, left, offsetY, 0xFF));
        }
        matrices.pop();
    }

    private static void logo(MatrixStack.Entry entry, VertexConsumer buffer, float left, float top, int alpha) {
        float cell = PlayerBadge.SIZE / PlayerStyle.PIXEL_GRID;
        PlayerStyle.pixelRects((from, row, to, rgb) -> {
            int color = alpha << 24 | rgb;
            float x0 = left + from * cell, x1 = left + to * cell;
            float y0 = top + row * cell, y1 = y0 + cell;
            buffer.vertex(entry, x0, y0, FORWARD).color(color).light(FULL_BRIGHT);
            buffer.vertex(entry, x0, y1, FORWARD).color(color).light(FULL_BRIGHT);
            buffer.vertex(entry, x1, y1, FORWARD).color(color).light(FULL_BRIGHT);
            buffer.vertex(entry, x1, y0, FORWARD).color(color).light(FULL_BRIGHT);
        });
    }

    // ── Nametag ──────────────────────────────────────────────────────────────

    /** En tête de {@code LivingEntityRenderer.hasLabel} : vrai = l'étiquette de notre joueur doit s'afficher. */
    public static boolean ownNametag(Object entity) {
        // La condition que vanilla garde pour toute étiquette : l'entité est visible.
        return PlayerBadge.ownNametag(entity) && !((Entity) entity).isInvisible();
    }

    // ── Liste Tab ────────────────────────────────────────────────────────────

    /**
     * À la sortie de {@code PlayerListHud.getPlayerName}.
     *
     * @return le pseudo précédé de la place du logo, ou {@code null} pour garder celui de vanilla
     */
    public static Object tabName(Object infoObject, Object nameObject) {
        if (!PlayerBadge.inTab() || !(nameObject instanceof Text) || !(infoObject instanceof PlayerListEntry)) {
            return null;
        }
        GameProfile profile = ((PlayerListEntry) infoObject).getProfile();
        if (profile == null) {
            return null;
        }
        boolean own = isOwn(profile);
        if (!PlayerBadge.marksTab(profile.id(), profile.name(), own)) {
            return null;
        }
        Padded padded = pad((Text) nameObject, profile.name());
        PlayerBadge.tabMark(padded.text, padded.lead);
        return padded.text;
    }

    /** Ce profil est-il celui de notre propre joueur ? */
    private static boolean isOwn(GameProfile profile) {
        Object player = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER);
        if (!(player instanceof PlayerEntity) || profile.id() == null) {
            return false;
        }
        GameProfile own = ((PlayerEntity) player).getGameProfile();
        return own != null && profile.id().equals(own.id());
    }

    /** À la place de l'appel de vanilla qui dessine un pseudo de la liste : le même dessin, puis le logo dans la place réservée. */
    public static void drawTabName(Object contextObject, Object rendererObject, Object nameObject, int x, int y, int color) {
        DrawContext context = (DrawContext) contextObject;
        context.drawTextWithShadow((TextRenderer) rendererObject, (Text) nameObject, x, y, color);
        float lead = PlayerBadge.tabLead(nameObject);
        if (lead < 0.0F) {
            return;
        }
        Matrix3x2fStack pose = context.getMatrices();
        pose.pushMatrix();
        pose.translate(x + lead, y);
        float cell = PlayerBadge.SIZE / PlayerStyle.PIXEL_GRID;
        pose.scale(cell, cell);
        PlayerStyle.pixelRects((from, row, to, rgb) -> context.fill(from, row, to, row + 1, 0xFF000000 | rgb));
        pose.popMatrix();
    }
}
