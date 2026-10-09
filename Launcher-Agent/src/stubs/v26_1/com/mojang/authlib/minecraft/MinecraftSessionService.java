package com.mojang.authlib.minecraft;

import com.mojang.authlib.yggdrasil.ProfileResult;

import java.util.UUID;

/** Stub compile-only (authlib 6 à 9) — le profil d'un compte, propriétés de skin comprises. */
public interface MinecraftSessionService {
    ProfileResult fetchProfile(UUID profileId, boolean requireSecure);
}
