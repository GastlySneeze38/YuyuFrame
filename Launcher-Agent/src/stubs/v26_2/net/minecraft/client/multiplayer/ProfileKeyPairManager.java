package net.minecraft.client.multiplayer;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.User;

import java.nio.file.Path;

/**
 * Stub compile-only (26.1+) — les clés de signature du chat d'un compte. Une
 * INTERFACE dans le jeu : {@code create} y est une méthode statique
 * d'interface, et le bytecode d'appel n'est pas le même que pour une classe.
 */
public interface ProfileKeyPairManager {
    static ProfileKeyPairManager create(UserApiService userApiService, User user, Path gameDirectory) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
