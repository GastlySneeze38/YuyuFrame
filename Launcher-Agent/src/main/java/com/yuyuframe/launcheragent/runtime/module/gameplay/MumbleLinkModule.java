package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mumble.MumbleLinkBridge;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import net.minecraft.client.player.LocalPlayer;

import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.mojang.authlib.GameProfile;

/**
 * Port de PvP-Mod MumbleLinkHandler/MumbleLinkConfig — envoie position/
 * orientation/nom du joueur à Mumble (audio positionnel 3D) via mémoire
 * partagée Win32 (voir MumbleLinkBridge, tout le protocole/JNA y vit). Ce
 * module ne fait que lire l'état joueur par réflexion CHAQUE frame (même
 * pattern que CoordsModule pour X/Y/Z/yaw) et transmettre au bridge — aucun
 * Mixin nécessaire, contrairement à FOV/sneak/etc, puisqu'on ne fait que LIRE
 * un état déjà mis à jour par vanilla, jamais le modifier.
 */
public final class MumbleLinkModule extends LauncherModule {

    public MumbleLinkModule() {
        super("mumble-link", "Mumble Link", "Envoie la position/orientation à Mumble pour l'audio positionnel 3D",
            "Audio positionnel 3D (Mumble)", false,
            // DÉCLARÉ, sinon ClientTickMixin261 n'est pas tissé : le filtre de
            // MixinHookPointRegistry ne retient que les mixins dont le
            // HookPoint est réclamé par au moins un module.
            HookPoint.CLIENT_TICK);
        iconUrl = icons8("headphones");

        // BUG STRUCTUREL (2026-09-01) — LE TICK MUMBLE NE DOIT PAS DÉPENDRE DU
        // RENDU. `ModuleRegistry.tickAll()` est appelé depuis
        // GlobalUiRenderMixin261, donc une fois par FRAME RENDUE. Or Mumble
        // coupe le lien dès que `uiTick` cesse de bouger pendant 5 secondes
        // (plugins/link/link.cpp) — et Minecraft arrête ou étrangle le rendu
        // exactement quand l'utilisateur va regarder Mumble : fenêtre
        // minimisée, perte de focus, limite de FPS en arrière-plan, sans
        // parler des mods du genre dynamic_fps qui tombent à 1 image/s.
        // Autrement dit, le lien se coupait potentiellement au moment précis
        // où l'on va vérifier s'il tient.
        //
        // Le tick de JEU, lui, continue à 20/s quoi qu'il arrive : c'est
        // dessus que tickent le mod de référence fabric-mumblelink-mod
        // (ClientTickEvents.START_CLIENT_TICK) et les autres implémentations
        // qui fonctionnent. On s'y accroche donc AUSSI — les deux sources
        // appellent le même `push()`, ce qui ne coûte qu'un incrément de
        // compteur en double et garantit que la source survivante suffit.
        VanillaHookRegistry.register(HookPoint.CLIENT_TICK, ctx -> { push(); return false; });
    }

    @Override
    public void onTick() {
        push();
    }

    private void push() {
        // tickAll() ne rappelle que les modules actifs, mais le hook
        // CLIENT_TICK, lui, est enregistré une fois pour toutes : sans cette
        // garde, le module désactivé continuerait d'alimenter Mumble.
        if (!isEnabled()) return;
        try {
            if (!MumbleLinkBridge.ensureInit()) return;

            // Position/orientation par les ACCESSORS Mixin (voir PlayerData) —
            // plus aucune réflexion ici. Ce module et CoordsModule avaient
            // chacun leur propre lecture de x/y/z/yaw, avec les mêmes pièges de
            // mappings découverts et corrigés séparément des deux côtés ; il
            // n'en existe désormais qu'une seule implémentation.
            LocalPlayer directPlayer = PlayerData.player();
            if (directPlayer == null) return;

            double[] pos = PlayerData.position();
            float eyeHeight = PlayerData.eyeHeight();
            // GameProfile (com.mojang.authlib) — bibliothèque EXTERNE, pas sur
            // le classpath de compilation de ce module (ni stub ni jar,
            // contrairement à net.minecraft.*) : getName() par réflexion reste
            // nécessaire pour ce seul champ, voir profileName(). C'est la seule
            // réflexion restante du module, et elle ne porte pas sur le jeu.
            String username = identity(profileName(directPlayer.getGameProfile()));

            float yawRad = (float) Math.toRadians(PlayerData.yaw());
            float pitchRad = (float) Math.toRadians(PlayerData.pitch());
            MumbleLinkBridge.update((float) pos[0], (float) (pos[1] + eyeHeight), (float) pos[2],
                yawRad, pitchRad, username, context());
        } catch (Throwable t) {
            LauncherLog.err("[MumbleLinkModule] onTick: " + t);
        }
    }





    /**
     * Cloisonnement Mumble : deux joueurs ne s'entendent positionnellement que
     * si cette chaîne est IDENTIQUE chez les deux — c'est une comparaison
     * d'octets faite par le serveur Mumble
     * ({@code sender.ssContext == receiver.ssContext}, voir
     * {@code src/murmur/AudioReceiverBuffer.cpp}), et le seul mécanisme prévu
     * pour ça.
     *
     * <p>Ce champ n'était pas écrit du tout : le contexte restait vide, donc
     * identique pour TOUT LE MONDE — deux joueurs sur deux serveurs différents,
     * ou dans deux dimensions différentes, s'entendaient comme s'ils étaient
     * côte à côte, avec des coordonnées qui n'ont aucun rapport entre elles.
     *
     * <p>On y met donc l'adresse du serveur ET la dimension. Le préfixe évite
     * une collision improbable mais gratuite à écarter avec un autre jeu qui
     * utiliserait la même mémoire partagée.
     */
    private String context() {
        return CONTEXT;
    }

    /**
     * Identité au format JSON de Badlion — {@code {"name":"<pseudo>"}}.
     *
     * <p>Contrairement au contexte, l'identité n'entre PAS dans la décision
     * d'audio positionnel (le serveur ne compare que {@code ssContext}, voir
     * {@code AudioReceiverBuffer.cpp}) : elle sert à identifier le joueur
     * auprès des autres et des droits du serveur Mumble. On reprend malgré
     * tout la forme JSON, commune à Badlion et à zsawyer/MumbleLink, plutôt
     * que le pseudo brut — c'est ce que d'éventuels outils côté serveur
     * s'attendent à parser.
     *
     * <p>Badlion y ajoute {@code worldSpawn} et {@code dimension}. Non repris :
     * ces deux champs ne changent rien au rendu positionnel, et les lire
     * demanderait des accessors supplémentaires pour un gain nul. À ajouter si
     * un jour un serveur s'en sert.
     *
     * <p>Pas d'échappement JSON : un pseudo Minecraft est limité à
     * {@code [A-Za-z0-9_]}, aucun caractère à échapper ne peut y figurer.
     */
    private String identity(String name) {
        return "{\"name\":\"" + (name != null ? name : "") + "\"}";
    }

    /**
     * Contexte de Badlion Client, relevé à l'octet près.
     *
     * <p>Mumble n'applique l'audio positionnel qu'entre deux utilisateurs dont
     * le contexte est identique OCTET POUR OCTET : le serveur retire purement
     * et simplement les données de position sinon
     * ({@code positionalDataAvailable && sender.ssContext == receiver.ssContext},
     * {@code src/murmur/AudioReceiverBuffer.cpp}). La voix arrive alors sans
     * atténuation ni direction — on entend tout le monde à plein volume,
     * exactement comme si le lien ne servait à rien.
     *
     * <p>DEUX ERREURS SUCCESSIVES LE MÊME JOUR (2026-09-01), toutes deux
     * « raisonnables » et toutes deux fausses :
     * <ol>
     *   <li>{@code "yuyuframe\0<serveur>\0<dimension>"} — le bon usage du champ
     *       dans l'absolu (cloisonner serveurs et dimensions), mais ça nous
     *       isolait de tout le monde ;</li>
     *   <li>chaîne vide, d'après le mod historique zsawyer/MumbleLink dont le
     *       {@code generateContext} rend {@code ""} — plus interopérable en
     *       théorie, mais toujours muet en pratique.</li>
     * </ol>
     *
     * <p>La bonne valeur n'était pas déductible : elle a été MESURÉE. Sonde
     * lancée pendant que Badlion Client tournait (client dont l'audio
     * positionnel fonctionne pour l'utilisateur et ses joueurs) :
     * <pre>
     *   context  : {"domain":"AllTalk"}
     *   identity : {"name":"...","worldSpawn":[4,120,1],"dimension":0}
     *   description : Badlion Client implementation of MumbleLink.
     * </pre>
     *
     * <p>C'est donc CETTE chaîne, à l'octet près. Mumble y préfixe de son côté
     * {@code applicationName} + NUL — soit « Minecraft », que Badlion écrit
     * aussi dans {@code name}, donc les préfixes concordent également.
     *
     * <p>Règle à retenir : ce champ n'est pas un choix de conception, c'est un
     * protocole d'entente. Tout ce qu'on y met nous coupe de quiconque ne met
     * pas exactement la même chose — et la seule source fiable est la mémoire
     * partagée d'un client qui marche.
     */
    private static final String CONTEXT = "{\"domain\":\"AllTalk\"}";

    /** {@code GameProfile.getName()} par réflexion directe — voir javadoc de classe (bibliothèque externe, hors classpath de compilation). */
    /**
     * BUG TROUVÉ (2026-08-27) : cette méthode lisait le pseudo par réflexion
     * sur {@code getName()} — or {@code GameProfile} est devenu un RECORD,
     * dont l'accesseur s'appelle {@code name()}. La recherche échouait, le
     * {@code catch} renvoyait {@code null}, et Mumble recevait un pseudo vide
     * sans le moindre log. Le stub {@code com.mojang.authlib.GameProfile}
     * (ajouté en même temps) supprime la réflexion ET l'erreur de nom.
     */
    private String profileName(GameProfile profile) {
        return profile == null ? null : profile.name();
    }
}
