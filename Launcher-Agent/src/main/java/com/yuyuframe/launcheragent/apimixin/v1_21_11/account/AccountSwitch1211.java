package com.yuyuframe.launcheragent.apimixin.v1_21_11.account;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.core.MinecraftClientAccessor1211;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.ProfileKeys;
import net.minecraft.client.session.Session;
import net.minecraft.util.ApiServices;

import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Changer de compte Minecraft pendant que le jeu tourne, sur 1.21.11 — la
 * liaison de {@code AccessPoint.CLIENT_SET_ACCOUNT}. Mêmes quatre objets à
 * refaire qu'en 26.1.2 (voir {@code AccountSwitch261}, où chacun est
 * expliqué), sous leurs noms Yarn : {@code session}, {@code userApiService},
 * {@code profileKeys}, {@code gameProfileFuture}.
 *
 * <p>Les types du jeu sont nommés en Yarn et traduits au chargement
 * ({@link YarnNamed}) ; chaque membre est appelé sur la classe qui le déclare.
 * La bibliothèque d'authentification, elle, n'est pas obfusquée.
 */
@YarnNamed
public final class AccountSwitch1211 {

    private AccountSwitch1211() {
    }

    /** @return {@code null} si le compte est changé, sinon ce qui a empêché de le faire */
    public static String apply(MinecraftClient client, String name, UUID id, String token, boolean offline) {
        try {
            Object woven = client;
            if (!(woven instanceof MinecraftClientAccessor1211)) {
                return "Accès au client non tissé";
            }
            MinecraftClientAccessor1211 access = (MinecraftClientAccessor1211) woven;

            Session session = new Session(name, id, token, Optional.empty(), Optional.empty());
            UserApiService service = offline
                ? UserApiService.OFFLINE
                : new YggdrasilAuthenticationService(Proxy.NO_PROXY).createUserApiService(token);
            ProfileKeys keys = ProfileKeys.create(service, session, client.runDirectory.toPath());
            ApiServices services = client.getApiServices();
            MinecraftSessionService sessions = services.sessionService();
            // Sans profil, le jeu en fabrique un du pseudo et de l'UUID (getGameProfile) : juste, mais sans skin.
            CompletableFuture<Object> profile = offline
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> fetch(sessions, id));

            access.la$setUserApiService(service);
            access.la$setProfileKeys(keys);
            access.la$setGameProfileFuture(profile);
            // En dernier : c'est elle que le reste du jeu lit pour savoir qui joue.
            access.la$setSession(session);
            return null;
        } catch (Throwable error) {
            return String.valueOf(error);
        }
    }

    /** Un profil introuvable ne doit pas faire échouer {@code getGameProfile()}, qui attend ce résultat. */
    private static Object fetch(MinecraftSessionService sessions, UUID id) {
        try {
            return sessions.fetchProfile(id, true);
        } catch (Throwable unreachable) {
            return null;
        }
    }
}
