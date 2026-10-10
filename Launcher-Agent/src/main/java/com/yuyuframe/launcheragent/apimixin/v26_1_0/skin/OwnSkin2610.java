package com.yuyuframe.launcheragent.apimixin.v26_1_0.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.blaze3d.platform.NativeImage;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.skin.OwnSkin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Le skin d'un compte hors ligne sur 26.1 : pose celui du launcher sur notre
 * propre joueur quand le jeu n'en a aucun pour lui. Pendant de
 * {@code OwnSkin261}, mêmes types et mêmes signatures (vérifié javap sur le
 * jar 26.1). Les règles sont dans {@link OwnSkin}.
 */
public final class OwnSkin2610 {

    private OwnSkin2610() {
    }

    private static final String TEXTURES = "textures";

    /** La texture inscrite auprès du jeu, et le skin dont elle vient. */
    private static Identifier textureId;
    private static ClientAsset.Texture texture;
    private static int textureRevision;
    /** Le skin rendu à la dernière lecture, refait quand celui de vanilla ou le nôtre change. */
    private static PlayerSkin built;
    private static PlayerSkin builtOver;
    private static int builtRevision;
    private static int failedRevision;

    /**
     * À la sortie de {@code PlayerInfo.getSkin()}.
     *
     * @return notre skin, ou {@code null} pour garder celui du jeu
     */
    public static PlayerSkin replace(Object infoObject, PlayerSkin vanilla) {
        GameProfile profile = ((PlayerInfo) infoObject).getProfile();
        if (profile == null || vanilla == null || !OwnSkin.isSelf(profile.id())) {
            return null;
        }
        PropertyMap properties = profile.properties();
        if (properties != null && properties.containsKey(TEXTURES)) {
            // Le serveur nous donne un skin : c'est celui que tout le monde voit, on n'y touche pas.
            for (Property property : properties.get(TEXTURES)) {
                OwnSkin.serverTextures(property.value());
                break;
            }
            return null;
        }
        OwnSkin.serverTextures(null);
        OwnSkin.Skin skin = OwnSkin.current();
        if (skin == null || !OwnSkin.onGameThread()) {
            return null;
        }
        ClientAsset.Texture body = texture(skin);
        if (body == null) {
            return null;
        }
        if (built == null || builtOver != vanilla || builtRevision != skin.revision) {
            // La cape et les élytres restent ceux du jeu : notre skin n'en apporte pas.
            built = PlayerSkin.insecure(body, vanilla.cape(), vanilla.elytra(),
                skin.slim ? PlayerModelType.SLIM : PlayerModelType.WIDE);
            builtOver = vanilla;
            builtRevision = skin.revision;
        }
        return built;
    }

    /** La texture de ce skin, créée à la première lecture et refaite quand il change. */
    private static ClientAsset.Texture texture(OwnSkin.Skin skin) {
        if (texture != null && textureRevision == skin.revision) {
            return texture;
        }
        if (failedRevision == skin.revision) {
            return null;
        }
        try {
            Identifier id = Identifier.fromNamespaceAndPath("yuyuframe", "skin/own_" + skin.revision);
            NativeImage image = NativeImage.read(skin.png);
            Minecraft client = Minecraft.getInstance();
            // La texture garde l'image et la fermera avec elle.
            client.getTextureManager().register(id, new DynamicTexture(() -> "Skin YuyuFrame", image));
            if (textureId != null) {
                client.getTextureManager().release(textureId);
            }
            textureId = id;
            texture = new ClientAsset.DownloadedTexture(id, "yuyuframe:skin");
            textureRevision = skin.revision;
            return texture;
        } catch (Throwable error) {
            // Une fois par skin : sans cela l'échec reviendrait à chaque image.
            failedRevision = skin.revision;
            LauncherLog.err("[SKIN] Texture du skin impossible à créer", error);
            return null;
        }
    }
}
