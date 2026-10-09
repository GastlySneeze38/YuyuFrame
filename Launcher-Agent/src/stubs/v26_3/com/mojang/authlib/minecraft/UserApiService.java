package com.mojang.authlib.minecraft;

/**
 * Stub compile-only — authlib n'est pas obfusquée, mais elle n'est sur aucun
 * classpath de compilation. Poignée opaque : ce que le jeu construit à partir
 * du jeton d'un compte (clés de signature du chat, liste des joueurs bloqués).
 */
public interface UserApiService {
    /** Le service d'un compte hors ligne. Pas une constante de compilation : lue dans la vraie classe. */
    UserApiService OFFLINE = Holder.offline();

    /** Seulement pour que {@link #OFFLINE} ne soit pas replié en constante par le compilateur. */
    final class Holder {
        private Holder() {
        }

        static UserApiService offline() {
            return null;
        }
    }
}
