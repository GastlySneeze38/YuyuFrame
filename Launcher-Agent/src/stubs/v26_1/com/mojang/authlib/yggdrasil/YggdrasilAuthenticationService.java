package com.mojang.authlib.yggdrasil;

import com.mojang.authlib.minecraft.UserApiService;

import java.net.Proxy;

/**
 * Stub compile-only (authlib 6 à 9, vérifié par javap sur 7.0.61, 7.0.63 et
 * 9.0.75) — de quoi refaire le service d'un compte à partir de son jeton. La
 * création ne fait aucun appel réseau.
 */
public class YggdrasilAuthenticationService {
    public YggdrasilAuthenticationService(Proxy proxy) {
    }

    public UserApiService createUserApiService(String accessToken) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
