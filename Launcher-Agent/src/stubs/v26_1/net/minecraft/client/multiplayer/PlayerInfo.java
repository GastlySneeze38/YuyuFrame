package net.minecraft.client.multiplayer;

/** Stub compile-only (26.1+) — méthodes publiques pour {@code PingModule} et la liste Tab. */
public abstract class PlayerInfo {
    public int getLatency() { return 0; }

    /** Le profil du joueur de cette ligne (vérifié javap, 26.1.2). */
    public com.mojang.authlib.GameProfile getProfile() { return null; }
}
