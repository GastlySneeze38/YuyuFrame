package net.minecraft.entity.player;

import net.minecraft.util.AssetInfo;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code ddq}, un record) — le skin d'un
 * joueur : corps, cape, élytres, modèle. Pendant de {@code PlayerSkin} en
 * 26.1.2.
 */
public final class SkinTextures {

    private SkinTextures() {
    }

    /** {@code ddq.a(iu$c, iu$c, iu$c, ddp)} — un skin que Mojang n'a pas signé. */
    public static SkinTextures create(AssetInfo.TextureAsset body, AssetInfo.TextureAsset cape,
                                      AssetInfo.TextureAsset elytra, PlayerSkinType model) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public AssetInfo.TextureAsset cape() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public AssetInfo.TextureAsset elytra() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
