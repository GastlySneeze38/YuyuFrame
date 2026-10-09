package net.minecraft.server;

import com.mojang.authlib.minecraft.MinecraftSessionService;

/** Stub compile-only (26.1, 26.2) — un record dans le jeu ; seul son accesseur du service de session sert. */
public final class Services {
    private Services() {
    }

    public MinecraftSessionService sessionService() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
