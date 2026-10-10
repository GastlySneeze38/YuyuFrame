package net.minecraft.world.entity.player;

import net.minecraft.core.ClientAsset;

/**
 * Stub compile-only (26.1+) — le skin d'un joueur, un record : corps, cape,
 * élytres, modèle (vérifié javap, 26.1 à 26.3).
 */
public final class PlayerSkin {
    private PlayerSkin() {}

    /** Un skin que Mojang n'a pas signé. */
    public static PlayerSkin insecure(ClientAsset.Texture body, ClientAsset.Texture cape, ClientAsset.Texture elytra,
                                      PlayerModelType model) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public ClientAsset.Texture cape() { return null; }

    public ClientAsset.Texture elytra() { return null; }
}
