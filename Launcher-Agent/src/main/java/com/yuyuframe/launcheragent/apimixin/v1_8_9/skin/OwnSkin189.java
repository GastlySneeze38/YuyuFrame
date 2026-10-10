package com.yuyuframe.launcheragent.apimixin.v1_8_9.skin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.skin.OwnSkin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.awt.image.BufferedImage;

/**
 * Le skin d'un compte hors ligne sur 1.8.9 : pose celui du launcher sur notre
 * propre joueur quand le jeu n'en a aucun pour lui. Les règles sont dans
 * {@link OwnSkin} ; ici, seulement ce qui nomme des types du jeu — sous leur
 * nom Yarn legacy, traduits au chargement ({@link YarnNamed}).
 *
 * <p>Sur cette version le skin d'un joueur se lit en deux fois dans sa ligne
 * de la liste des joueurs ({@code PlayerListEntry}, {@code bdc}) : la texture
 * ({@code getSkinTexture}) et le modèle ({@code getModel}, « default » ou
 * « slim »). Les deux retombent sur l'apparence par défaut quand le profil n'a
 * pas de propriété {@code textures} : c'est ce cas-là, et lui seul, qu'on
 * remplace — par les deux à la fois, sans quoi des bras fins seraient dessinés
 * sur un modèle large.
 */
@YarnNamed
public final class OwnSkin189 {

    private OwnSkin189() {
    }

    private static final String TEXTURES = "textures";

    /** La texture inscrite auprès du jeu, et le skin dont elle vient. */
    private static Identifier textureId;
    private static int textureRevision;
    private static int failedRevision;

    /**
     * À la sortie de {@code PlayerListEntry.getSkinTexture()}.
     *
     * @return notre texture, ou {@code null} pour garder celle du jeu
     */
    public static Object skinTexture(Object entryObject) {
        OwnSkin.Skin skin = wanted(entryObject, true);
        return skin == null || !OwnSkin.onGameThread() ? null : texture(skin);
    }

    /**
     * À la sortie de {@code PlayerListEntry.getModel()}.
     *
     * @return « slim » ou « default », ou {@code null} pour garder celui du jeu
     */
    public static String model(Object entryObject) {
        OwnSkin.Skin skin = wanted(entryObject, false);
        // Le modèle ne change que si la texture a pu être créée : l'un ne va pas sans l'autre.
        if (skin == null || textureId == null || textureRevision != skin.revision) {
            return null;
        }
        return skin.slim ? "slim" : "default";
    }

    /**
     * Notre skin, si cette ligne est la nôtre et que le serveur ne nous en
     * donne aucun.
     *
     * @param report rapporter à {@link OwnSkin} ce que le serveur donne — fait par un seul des deux appelants
     */
    private static OwnSkin.Skin wanted(Object entryObject, boolean report) {
        GameProfile profile = ((PlayerListEntry) entryObject).getProfile();
        if (profile == null || !OwnSkin.isSelf(profile.getId())) {
            return null;
        }
        PropertyMap properties = profile.getProperties();
        if (properties != null && properties.containsKey(TEXTURES)) {
            // Le serveur nous donne un skin : c'est celui que tout le monde voit, on n'y touche pas.
            if (report) {
                for (Property property : properties.get(TEXTURES)) {
                    OwnSkin.serverTextures(property.getValue());
                    break;
                }
            }
            return null;
        }
        if (report) {
            OwnSkin.serverTextures(null);
        }
        return OwnSkin.current();
    }

    /** La texture de ce skin, créée à la première lecture et refaite quand il change. */
    private static Identifier texture(OwnSkin.Skin skin) {
        if (textureId != null && textureRevision == skin.revision) {
            return textureId;
        }
        if (failedRevision == skin.revision) {
            return null;
        }
        try {
            BufferedImage image = new BufferedImage(OwnSkin.SIZE, OwnSkin.SIZE, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, OwnSkin.SIZE, OwnSkin.SIZE, skin.argb, 0, OwnSkin.SIZE);
            Identifier id = new Identifier("yuyuframe", "skin/own_" + skin.revision);
            MinecraftClient client = MinecraftClient.getInstance();
            client.getTextureManager().loadTexture(id, new NativeImageBackedTexture(image));
            if (textureId != null) {
                client.getTextureManager().close(textureId);
            }
            textureId = id;
            textureRevision = skin.revision;
            return id;
        } catch (Throwable error) {
            // Une fois par skin : sans cela l'échec reviendrait à chaque image.
            failedRevision = skin.revision;
            LauncherLog.err("[SKIN] Texture du skin impossible à créer", error);
            return null;
        }
    }
}
