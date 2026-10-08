package com.yuyuframe.launcheragent.apimixin.v26_1_2.player;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.runtime.presence.PlayerBadge;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

/**
 * Le module « Joueur » sur 26.1.2 : le logo en pixels devant le pseudo des
 * joueurs YuyuFrame, et le nametag de son propre joueur. Les règles sont dans
 * {@link PlayerBadge} ; ici, seulement ce qui nomme des types du jeu.
 *
 * <p>Le rendu d'une entité se fait en deux temps sur cette version : son état
 * est copié ({@code extractRenderState}), puis des commandes de dessin sont
 * soumises ({@code submitNameDisplay}). On réserve la place du logo au premier
 * temps, on le dessine au second.
 */
public final class PlayerBadge261 {

    private PlayerBadge261() {
    }

    /** La pleine lumière, empaquetée comme le jeu l'attend : le logo ne s'assombrit pas la nuit. */
    private static final int FULL_BRIGHT = 0xF000F0;
    /** Un cheveu devant le fond et le texte de l'étiquette, dessinés dans le même plan. */
    private static final float FORWARD = 0.1F;

    // ── Étiquette ────────────────────────────────────────────────────────────

    /** À la sortie de {@code AvatarRenderer.extractRenderState} : met la place du logo devant le pseudo. */
    public static void afterExtract(Object entityObject, Object stateObject) {
        EntityRenderState state = (EntityRenderState) stateObject;
        Component name = state.nameTag;
        if (name == null || !(entityObject instanceof Player)) {
            PlayerBadge.unmark(state);
            return;
        }
        GameProfile profile = ((Player) entityObject).getGameProfile();
        boolean self = entityObject == AccessorRegistry.get(AccessPoint.CLIENT_PLAYER);
        if (profile == null || !PlayerBadge.marksLabel(profile.id(), profile.name(), self)) {
            PlayerBadge.unmark(state);
            return;
        }
        Component padded = Component.literal(PlayerBadge.LEAD).append(name);
        state.nameTag = padded;
        PlayerBadge.mark(state, padded);
    }

    /**
     * En tête de {@code EntityRenderer.submitNameDisplay} : soumet le logo à
     * l'endroit où vanilla va poser le début de l'étiquette. Mêmes
     * transformations que lui ({@code NameTagFeatureRenderer.Storage.add},
     * relevées dans le bytecode) : ancrage + 0,5, face à la caméra, échelle
     * 0,025 ; le texte commence à moins la moitié de sa largeur.
     */
    public static void beforeNameDisplay(Object stateObject, Object poseObject, Object collectorObject,
                                         Object cameraObject, int offsetY) {
        EntityRenderState state = (EntityRenderState) stateObject;
        Component name = state.nameTag;
        Vec3 at = state.nameTagAttachment;
        if (at == null || !PlayerBadge.isMarked(state, name)) {
            return;
        }
        PoseStack pose = (PoseStack) poseObject;
        SubmitNodeCollector collector = (SubmitNodeCollector) collectorObject;
        float left = -Minecraft.getInstance().font.width(name) / 2.0F;

        pose.pushPose();
        // La ligne de score, quand il y en a une, prend la place du pseudo et le pousse d'une ligne vers le haut.
        if (state.scoreText != null) {
            pose.translate(0.0F, 9.0F * 1.15F * 0.025F, 0.0F);
        }
        pose.translate(at.x, at.y + 0.5, at.z);
        pose.mulPose(((CameraRenderState) cameraObject).orientation);
        pose.scale(0.025F, -0.025F, 0.025F);
        if (state.isDiscrete) {
            // Accroupi : comme le pseudo, à demi effacé et jamais à travers les murs.
            collector.submitCustomGeometry(pose, RenderTypes.textBackground(),
                (p, buffer) -> logo(p, buffer, left, offsetY, 0x80));
        } else {
            collector.submitCustomGeometry(pose, RenderTypes.textBackgroundSeeThrough(),
                (p, buffer) -> logo(p, buffer, left, offsetY, 0x40));
            collector.submitCustomGeometry(pose, RenderTypes.textBackground(),
                (p, buffer) -> logo(p, buffer, left, offsetY, 0xFF));
        }
        pose.popPose();
    }

    private static void logo(PoseStack.Pose pose, VertexConsumer buffer, float left, float top, int alpha) {
        float cell = PlayerBadge.SIZE / PlayerStyle.PIXEL_GRID;
        PlayerStyle.pixelRects((from, row, to, rgb) -> {
            int color = alpha << 24 | rgb;
            float x0 = left + from * cell, x1 = left + to * cell;
            float y0 = top + row * cell, y1 = y0 + cell;
            buffer.addVertex(pose, x0, y0, FORWARD).setColor(color).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, x0, y1, FORWARD).setColor(color).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, x1, y1, FORWARD).setColor(color).setLight(FULL_BRIGHT);
            buffer.addVertex(pose, x1, y0, FORWARD).setColor(color).setLight(FULL_BRIGHT);
        });
    }

    // ── Nametag ──────────────────────────────────────────────────────────────

    /** En tête de {@code LivingEntityRenderer.shouldShowName} : vrai = l'étiquette de notre joueur doit s'afficher. */
    public static boolean ownNametag(Object entity) {
        // Les deux conditions que vanilla garde pour toute étiquette : interface affichée (F1), entité visible.
        return PlayerBadge.ownNametag(entity) && Minecraft.renderNames() && !((Entity) entity).isInvisible();
    }

    // ── Liste Tab ────────────────────────────────────────────────────────────

    /**
     * À la sortie de {@code PlayerTabOverlay.getNameForDisplay}.
     *
     * @return le pseudo précédé de la place du logo, ou {@code null} pour garder celui de vanilla
     */
    public static Component tabName(Object infoObject, Component name) {
        if (!PlayerBadge.inTab() || name == null || !(infoObject instanceof PlayerInfo)) {
            return null;
        }
        GameProfile profile = ((PlayerInfo) infoObject).getProfile();
        if (profile == null || !PlayerBadge.marksTab(profile.id(), profile.name())) {
            return null;
        }
        Component padded = Component.literal(PlayerBadge.LEAD).append(name);
        PlayerBadge.tabMark(padded);
        return padded;
    }

    /** Juste après que vanilla a dessiné un pseudo de la liste : le logo, dans la place réservée. */
    public static void tabLogo(GuiGraphicsExtractor graphics, Component name, int x, int y) {
        if (!PlayerBadge.tabMarked(name)) {
            return;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        float cell = PlayerBadge.SIZE / PlayerStyle.PIXEL_GRID;
        pose.scale(cell, cell);
        PlayerStyle.pixelRects((from, row, to, rgb) -> graphics.fill(from, row, to, row + 1, 0xFF000000 | rgb));
        pose.popMatrix();
    }
}
