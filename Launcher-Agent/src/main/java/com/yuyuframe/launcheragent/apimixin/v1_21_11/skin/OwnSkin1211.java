package com.yuyuframe.launcheragent.apimixin.v1_21_11.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.skin.OwnSkin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.entity.player.PlayerSkinType;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.AssetInfo;
import net.minecraft.util.Identifier;

/**
 * Le skin d'un compte hors ligne sur 1.21.11 : pose celui du launcher sur
 * notre propre joueur quand le jeu n'en a aucun pour lui. Pendant de
 * {@code OwnSkin261}, sous les noms Yarn, traduits au chargement
 * ({@link YarnNamed}). Les règles sont dans {@link OwnSkin}.
 *
 * <p>Le jeu lit le skin d'un joueur dans sa ligne de la liste des joueurs
 * ({@code PlayerListEntry.getSkinTextures()}, {@code hiq.g()}) — le
 * personnage, la liste Tab et la poupée de l'inventaire passent tous par là, y
 * compris en solo. Sans propriété {@code textures} dans le profil, il rend le
 * skin par défaut : c'est ce cas-là, et lui seul, qu'on remplace.
 */
@YarnNamed
public final class OwnSkin1211 {

    private OwnSkin1211() {
    }

    private static final String TEXTURES = "textures";

    /** La texture inscrite auprès du jeu, et le skin dont elle vient. */
    private static Identifier textureId;
    private static AssetInfo.TextureAsset texture;
    private static int textureRevision;
    /** Le skin rendu à la dernière lecture, refait quand celui de vanilla ou le nôtre change. */
    private static SkinTextures built;
    private static SkinTextures builtOver;
    private static int builtRevision;
    private static int failedRevision;

    /**
     * À la sortie de {@code PlayerListEntry.getSkinTextures()}.
     *
     * @return notre skin, ou {@code null} pour garder celui du jeu
     */
    public static Object replace(Object entryObject, Object vanillaObject) {
        if (!(vanillaObject instanceof SkinTextures)) {
            return null;
        }
        SkinTextures vanilla = (SkinTextures) vanillaObject;
        GameProfile profile = ((PlayerListEntry) entryObject).getProfile();
        if (profile == null || !OwnSkin.isSelf(profile.id())) {
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
        AssetInfo.TextureAsset body = texture(skin);
        if (body == null) {
            return null;
        }
        if (built == null || builtOver != vanilla || builtRevision != skin.revision) {
            // La cape et les élytres restent ceux du jeu : notre skin n'en apporte pas.
            built = SkinTextures.create(body, vanilla.cape(), vanilla.elytra(),
                skin.slim ? PlayerSkinType.SLIM : PlayerSkinType.WIDE);
            builtOver = vanilla;
            builtRevision = skin.revision;
        }
        return built;
    }

    /** La texture de ce skin, créée à la première lecture et refaite quand il change. */
    private static AssetInfo.TextureAsset texture(OwnSkin.Skin skin) {
        if (texture != null && textureRevision == skin.revision) {
            return texture;
        }
        if (failedRevision == skin.revision) {
            return null;
        }
        try {
            Identifier id = Identifier.of("yuyuframe", "skin/own_" + skin.revision);
            NativeImage image = NativeImage.read(skin.png);
            MinecraftClient client = MinecraftClient.getInstance();
            // La texture garde l'image et la fermera avec elle.
            client.getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "Skin YuyuFrame", image));
            if (textureId != null) {
                client.getTextureManager().destroyTexture(textureId);
            }
            textureId = id;
            texture = new AssetInfo.SkinAssetInfo(id, "yuyuframe:skin");
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
