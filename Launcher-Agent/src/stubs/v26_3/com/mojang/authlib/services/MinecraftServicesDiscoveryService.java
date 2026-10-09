package com.mojang.authlib.services;

import com.mojang.authlib.minecraft.UserApiService;

import java.net.Proxy;

/**
 * Stub compile-only (authlib 10, 26.3) — a remplacé
 * {@code YggdrasilAuthenticationService} : c'est par lui que le jeu fabrique
 * le service d'un compte à partir de son jeton
 * ({@code Minecraft.createUserApiService}, relevé par javap). Sa création
 * peut interroger le réseau : jamais sur le fil du jeu.
 */
public class MinecraftServicesDiscoveryService {
    private MinecraftServicesDiscoveryService() {
    }

    public static MinecraftServicesDiscoveryService create(Proxy proxy) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public UserApiService createUserApiService(String accessToken) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
