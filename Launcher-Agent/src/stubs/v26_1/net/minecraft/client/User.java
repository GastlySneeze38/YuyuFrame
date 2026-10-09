package net.minecraft.client;

import java.util.Optional;
import java.util.UUID;

/**
 * Stub compile-only (26.1+) — {@code getName()} publique, voir
 * {@code ChatEnhancementsModule}. Le constructeur sert au changement de compte
 * en jeu ({@code AccountSwitch261} et ses pendants ; signature relevée par
 * javap sur les jars 26.1 à 26.3).
 */
public class User {
    public User(String name, UUID uuid, String accessToken, Optional<String> xuid, Optional<String> clientId) {
    }

    public String getName() { return null; }
}
