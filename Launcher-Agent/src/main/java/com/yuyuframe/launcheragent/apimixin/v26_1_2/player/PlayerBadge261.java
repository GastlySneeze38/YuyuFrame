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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.Optional;

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
        Padded padded = pad(name, profile.name());
        state.nameTag = padded.text;
        PlayerBadge.mark(state, padded.text, padded.lead);
    }

    /** Un pseudo avec la place du logo, et la largeur de ce qui la précède. */
    private static final class Padded {
        Component text;
        float lead;
    }

    /**
     * Met la place du logo juste devant le pseudo du joueur dans le texte
     * affiché. Ce texte est souvent « grade + pseudo », le grade étant un
     * caractère que le pack de ressources du serveur dessine en image : le logo
     * doit venir après lui, collé au pseudo.
     *
     * <p>Le texte est refait morceau par morceau, chacun gardant son style
     * (couleur, police du pack…) ; le morceau qui contient le début du pseudo
     * est coupé en deux autour de la réserve. Quand le pseudo n'y figure pas
     * (joueur renommé par le serveur) ou qu'il est déjà en tête, la réserve va
     * simplement devant tout.
     */
    private static Padded pad(Component name, String profileName) {
        Padded result = new Padded();
        String full = name.getString();
        int at = profileName == null || profileName.isEmpty() ? -1 : full.indexOf(profileName);
        if (at <= 0) {
            result.text = Component.literal(PlayerBadge.LEAD).append(name);
            result.lead = PlayerBadge.INSET;
            return result;
        }
        // Les espaces que le serveur a mises entre le grade et le pseudo sont
        // retirées : leur nombre et leur largeur varient d'un serveur à
        // l'autre. L'écart autour du logo est celui de la réserve, le même
        // partout — une demi-espace de chaque côté (réglé avec l'utilisateur,
        // 2026-10-08).
        int cutAt = at;
        while (cutAt > 0 && full.charAt(cutAt - 1) == ' ') {
            cutAt--;
        }
        final int cut = cutAt;
        MutableComponent rebuilt = Component.literal("");
        MutableComponent before = Component.literal("");
        int[] seen = { 0 };
        boolean[] placed = { false };
        name.visit((style, text) -> {
            int start = seen[0];
            int end = start + text.length();
            seen[0] = end;
            if (placed[0]) {
                rebuilt.append(Component.literal(text).setStyle(style));
                return Optional.empty();
            }
            // Ce que ce morceau porte avant la coupe : gardé tel quel.
            if (start < cut) {
                String head = text.substring(0, Math.min(end, cut) - start);
                rebuilt.append(Component.literal(head).setStyle(style));
                before.append(Component.literal(head).setStyle(style));
            }
            // Le morceau où commence le pseudo : la réserve, puis la suite. La réserve garde le style par
            // défaut — dans la police d'un pack, une espace peut ne rien mesurer.
            if (at < end) {
                rebuilt.append(Component.literal(PlayerBadge.LEAD));
                rebuilt.append(Component.literal(text.substring(at - start)).setStyle(style));
                placed[0] = true;
            }
            return Optional.empty();
        }, Style.EMPTY);
        result.text = rebuilt;
        result.lead = Minecraft.getInstance().font.width(before) + PlayerBadge.INSET;
        return result;
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
        float lead = at == null ? -1.0F : PlayerBadge.markedLead(state, name);
        if (lead < 0.0F) {
            return;
        }
        PoseStack pose = (PoseStack) poseObject;
        SubmitNodeCollector collector = (SubmitNodeCollector) collectorObject;
        float left = -Minecraft.getInstance().font.width(name) / 2.0F + lead;

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
        if (profile == null) {
            return null;
        }
        boolean own = isOwn(profile);
        PlayerBadge.tabLine(profile.id(), own);
        if (!PlayerBadge.marksTab(profile.id(), profile.name(), own)) {
            return null;
        }
        Padded padded = pad(name, profile.name());
        PlayerBadge.tabMark(padded.text, padded.lead);
        return padded.text;
    }

    /** Ce profil est-il celui de notre propre joueur ? */
    private static boolean isOwn(GameProfile profile) {
        Object player = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER);
        if (!(player instanceof Player) || profile.id() == null) {
            return false;
        }
        GameProfile own = ((Player) player).getGameProfile();
        return own != null && profile.id().equals(own.id());
    }

    /** Juste après que vanilla a dessiné un pseudo de la liste : le logo, dans la place réservée. */
    public static void tabLogo(GuiGraphicsExtractor graphics, Component name, int x, int y) {
        float lead = PlayerBadge.tabLead(name);
        if (lead < 0.0F) {
            return;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + lead, y);
        float cell = PlayerBadge.SIZE / PlayerStyle.PIXEL_GRID;
        pose.scale(cell, cell);
        PlayerStyle.pixelRects((from, row, to, rgb) -> graphics.fill(from, row, to, row + 1, 0xFF000000 | rgb));
        pose.popMatrix();
    }
}
