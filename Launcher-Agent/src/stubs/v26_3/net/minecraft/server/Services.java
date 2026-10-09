package net.minecraft.server;

import com.mojang.authlib.minecraft.SessionService;

/** Stub compile-only (26.3) — un record dans le jeu ; seul son accesseur du service de session sert. */
public final class Services {
    private Services() {
    }

    public SessionService sessionService() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
