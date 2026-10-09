package com.yuyuframe.launcheragent.apimixin.v26_3.account;

import com.mojang.authlib.minecraft.SessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.services.MinecraftServicesDiscoveryService;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.MinecraftAccessor263;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Changer de compte Minecraft pendant que le jeu tourne, sur 26.3 — la
 * liaison de {@code AccessPoint.CLIENT_SET_ACCOUNT}. Mêmes quatre objets à
 * refaire qu'en 26.1.2 (voir {@code AccountSwitch261}, où chacun est
 * expliqué) : {@code user}, {@code userApiService},
 * {@code profileKeyPairManager}, {@code profileFuture}.
 *
 * <p>Ce qui change sur cette version, c'est la bibliothèque d'authentification
 * (authlib 10, relevé par javap) : {@code YggdrasilAuthenticationService} a
 * disparu, le service d'un compte se fabrique par
 * {@code MinecraftServicesDiscoveryService} — c'est ce que fait le jeu
 * lui-même ({@code Minecraft.createUserApiService}) ; le service de session
 * s'appelle {@code SessionService}, et {@code ProfileResult} a changé de
 * paquet.
 *
 * <p>Créer le service de découverte peut interroger le réseau : cette méthode
 * ne s'appelle pas depuis le fil du jeu.
 */
public final class AccountSwitch263 {

    private AccountSwitch263() {
    }

    /** @return {@code null} si le compte est changé, sinon ce qui a empêché de le faire */
    public static String apply(MinecraftAccessor263 client, String name, UUID id, String token, boolean offline) {
        try {
            User user = new User(name, id, token, Optional.empty(), Optional.empty());
            UserApiService service = offline
                ? UserApiService.OFFLINE
                : MinecraftServicesDiscoveryService.create(Proxy.NO_PROXY).createUserApiService(token);
            ProfileKeyPairManager keys = ProfileKeyPairManager.create(service, user, client.la$gameDirectory().toPath());
            SessionService sessions = client.la$services().sessionService();
            // Sans profil, le jeu en fabrique un du pseudo et de l'UUID (getGameProfile) : juste, mais sans skin.
            CompletableFuture<Object> profile = offline
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> fetch(sessions, id));

            client.la$setUserApiService(service);
            client.la$setProfileKeyPairManager(keys);
            client.la$setProfileFuture(profile);
            // En dernier : c'est lui que le reste du jeu lit pour savoir qui joue.
            client.la$setUser(user);
            return null;
        } catch (Throwable error) {
            return String.valueOf(error);
        }
    }

    /** Un profil introuvable ne doit pas faire échouer {@code getGameProfile()}, qui attend ce résultat. */
    private static Object fetch(SessionService sessions, UUID id) {
        try {
            return sessions.fetchProfile(id, true);
        } catch (Throwable unreachable) {
            return null;
        }
    }
}
