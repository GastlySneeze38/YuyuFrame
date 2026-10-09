package com.yuyuframe.launcheragent.apimixin.v26_1_2.account;

import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.MinecraftAccessor261;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;

import java.net.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Changer de compte Minecraft pendant que le jeu tourne, sur 26.1.2 — la
 * liaison de {@code AccessPoint.CLIENT_SET_ACCOUNT}.
 *
 * <p>Le jeu reçoit son compte une fois, au démarrage, et fabrique aussitôt à
 * partir de lui trois autres objets (constructeur de {@code Minecraft}, relevé
 * par javap). Les quatre sont refaits ici, sinon le compte a l'air changé et
 * se fait refuser :
 * <ul>
 *   <li>{@code user} — ce que le jeu présente au serveur de session de Mojang
 *       en rejoignant un serveur ;</li>
 *   <li>{@code userApiService} — le service du compte, construit sur son
 *       jeton ;</li>
 *   <li>{@code profileKeyPairManager} — les clés de signature du chat. Celles
 *       de l'ancien compte, présentées sous le nouveau, valent une expulsion
 *       sur tout serveur qui les exige (le réglage par défaut) ;</li>
 *   <li>{@code profileFuture} — le profil (pseudo, skin) avec lequel le jeu
 *       ouvre un monde solo.</li>
 * </ul>
 *
 * <p>Restent ceux de l'ancien compte, sans effet sur le jeu en ligne : la
 * télémétrie, les restrictions du compte lues au démarrage (chat ou
 * multijoueur interdits par un contrôle parental), la liste des joueurs
 * bloqués et le contexte de signalement.
 *
 * <p>Rien ici n'interroge le réseau sur le fil appelant : le profil est
 * demandé en arrière-plan, les clés à la prochaine connexion.
 */
public final class AccountSwitch261 {

    private AccountSwitch261() {
    }

    /** @return {@code null} si le compte est changé, sinon ce qui a empêché de le faire */
    public static String apply(MinecraftAccessor261 client, String name, UUID id, String token, boolean offline) {
        try {
            User user = new User(name, id, token, Optional.empty(), Optional.empty());
            UserApiService service = offline
                ? UserApiService.OFFLINE
                : new YggdrasilAuthenticationService(Proxy.NO_PROXY).createUserApiService(token);
            ProfileKeyPairManager keys = ProfileKeyPairManager.create(service, user, client.la$gameDirectory().toPath());
            MinecraftSessionService sessions = client.la$services().sessionService();
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
    private static Object fetch(MinecraftSessionService sessions, UUID id) {
        try {
            return sessions.fetchProfile(id, true);
        } catch (Throwable unreachable) {
            return null;
        }
    }
}
