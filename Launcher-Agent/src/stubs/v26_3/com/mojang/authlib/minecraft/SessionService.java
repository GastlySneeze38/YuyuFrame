package com.mojang.authlib.minecraft;

import com.mojang.authlib.services.ProfileResult;

import java.util.UUID;

/**
 * Stub compile-only (authlib 10, 26.3) — a remplacé
 * {@code MinecraftSessionService} ; {@code ProfileResult} a changé de paquet
 * au passage (javap sur authlib-10.0.77).
 */
public interface SessionService {
    ProfileResult fetchProfile(UUID profileId, boolean requireSecure);
}
