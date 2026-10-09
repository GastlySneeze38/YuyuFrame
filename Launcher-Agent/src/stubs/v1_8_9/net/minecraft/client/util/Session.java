package net.minecraft.client.util;

/** Stub compile-only 1.8.9, nom Yarn legacy ({@code avm}). */
public class Session {

    /**
     * {@code avm(String, String, String, String)} (javap) — pseudo, UUID SANS
     * tirets, jeton, sorte de compte ({@code "mojang"} ou {@code "legacy"}).
     * Sert au changement de compte en jeu.
     */
    public Session(String username, String uuid, String accessToken, String accountType) {
    }

    public String getUsername() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
