package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/**
 * Accès PARTAGÉ aux données du joueur et du monde — <b>zéro réflexion</b>.
 *
 * <p>Créé lors du refacto du dossier {@code module/} (2026-08-27). Constat de
 * départ : 16 modules appelaient {@code McReflect.minecraftClient()} chacun de
 * leur côté, 9 refaisaient l'accès au champ {@code player}, et
 * {@code CoordsModule} / {@code MumbleLinkModule} dupliquaient carrément la
 * lecture de la position et du yaw. Les deux copies étaient correctes — mais
 * chacune avait dû découvrir et corriger SÉPARÉMENT les mêmes pièges de
 * mappings, et un troisième module en aurait fait une troisième.
 *
 * <p><b>Tout passe par les accessors Mixin</b>, jamais par la réflexion : les
 * getters sont synthétisés au tissage, donc résolus une fois pour toutes au
 * chargement au lieu d'être re-résolus à chaque appel.
 *
 * <p>{@code Minecraft.player} et {@code level} sont pourtant PUBLICS sur
 * 26.1.2 : un accès direct compilerait. Ils passent quand même par l'accessor,
 * sur demande explicite — mélanger accès directs et accessors ferait perdre
 * exactement la propriété ci-dessus.
 *
 * <p><b>Plus aucun accessor n'est nommé ici</b> (2026-09-09) : cette classe
 * demande un {@link AccessPoint} au {@link AccessorRegistry}, qui le route
 * vers l'accessor de la tranche active. Auparavant elle importait
 * {@code MinecraftAccessor261} en dur — le code de {@code runtime/} ne
 * contenait aucun nom de classe Minecraft, mais restait cloué à 26.1.2 par cet
 * import. Ajouter une version ne touche donc plus ce fichier : il suffit
 * d'écrire les accessors de la tranche et sa classe de liaisons.
 *
 * <p><b>Limite restante, à connaître avant un portage</b> : les TYPES de
 * retour ({@link LocalPlayer}, {@link ClientLevel}) sont ceux du jeu. Stables
 * sur la ligne 26.x (non obfusquée), ils n'existent pas du tout sur une
 * tranche obfusquée (1.8-1.21.x, gelées) — servir celles-ci demanderait des
 * types neutres à nous, ce qui toucherait chaque module appelant. Hors tranche
 * liée, tout renvoie ici une valeur neutre.
 *
 * <p>Portée VOLONTAIREMENT limitée à ce qui était réellement dupliqué. Faire
 * transiter par ici tout ce que chaque module lit (durabilité d'armure, effets
 * de potion, ping…) en ferait une classe fourre-tout : ces lectures-là sont
 * propres à un module et n'ont aucune raison d'être partagées.
 */
public final class PlayerData {
    private PlayerData() {}

    /**
     * Joueur courant, ou {@code null} (hors partie, ou version non liée).
     *
     * <p>Receveur {@code null} = « le client courant » : c'est la liaison de
     * la tranche qui va le chercher, pour qu'aucun appelant neutre n'ait à
     * nommer {@code Minecraft} — voir {@code AccessorBindings261}.
     */
    public static LocalPlayer player() {
        return AccessorRegistry.as(LocalPlayer.class, AccessPoint.CLIENT_PLAYER, null);
    }

    /** Monde client courant, ou {@code null}. */
    public static ClientLevel level() {
        return AccessorRegistry.as(ClientLevel.class, AccessPoint.CLIENT_LEVEL, null);
    }

    /**
     * {@code true} si un joueur est en partie — raccourci de lisibilité pour
     * les modules.
     *
     * <p>Passe par le point d'accès et NON par {@link #player()} : la variante
     * typée nomme le type 26.1.2 dans sa signature, donc l'appeler suffirait à
     * lier cette classe et à tomber en {@code NoClassDefFoundError} sur une
     * autre version — même piège que {@code ClientData.screen()} (v1067).
     */
    public static boolean inGame() {
        return AccessorRegistry.get(AccessPoint.CLIENT_PLAYER, null) != null;
    }

    /**
     * Position du joueur, {@code {x, y, z}} — jamais {@code null} (zéros hors partie).
     *
     * <p>HISTORIQUE conservé du code réflexif supprimé ici le 2026-08-27, à
     * relire avant tout portage vers un autre bracket : {@code Entity.x}/
     * {@code y}/{@code z} n'existent plus comme CHAMPS depuis un remaniement
     * Mojang antérieur à la 1.16.5 — remplacés par les méthodes
     * {@code getX()}/{@code getY()}/{@code getZ()} (mappings 1.16.5 :
     * {@code ()D cD method_23317 getX}). Un accessor de CHAMP {@code x} sur un
     * bracket récent ne peut donc pas fonctionner.
     */
    public static double[] position() {
        Object v = AccessorRegistry.get(AccessPoint.PLAYER_POSITION, null);
        return v instanceof double[] ? (double[]) v : new double[]{ 0, 0, 0 };
    }

    /**
     * Rotation horizontale (yaw), en degrés — 0 hors partie.
     *
     * <p>HISTORIQUE conservé du code réflexif supprimé ici le 2026-08-27 :
     * lire le CHAMP {@code yaw} en remontant la hiérarchie depuis la classe
     * runtime du joueur donnait un champ HOMONYME sans rapport. En 1.20.4,
     * {@code "aG"} est bien le vrai nom obfusqué de {@code Entity.yaw}, mais
     * une classe intermédiaire ({@code bml}, entre la classe du joueur et
     * {@code Entity}) définit SA PROPRE {@code "aG"} d'un autre type, trouvée
     * en premier — d'où un {@code IllegalArgumentException: illegal data type
     * conversion to float}. C'est exactement ce que l'accessor évite par
     * construction : il cible {@code Entity} nommément, sans remontée de
     * hiérarchie. Même piège pour le champ {@code world}/{@code level}.
     */
    public static float yaw() {
        return AccessorRegistry.getFloat(AccessPoint.PLAYER_YAW, null, 0f);
    }

    /** Rotation verticale (pitch), en degrés — 0 hors partie. */
    public static float pitch() {
        return AccessorRegistry.getFloat(AccessPoint.PLAYER_PITCH, null, 0f);
    }

    /** Hauteur des yeux au-dessus des pieds — 0 hors partie. */
    public static float eyeHeight() {
        return AccessorRegistry.getFloat(AccessPoint.PLAYER_EYE_HEIGHT, null, 0f);
    }

    /**
     * L'effet est-il actif sur le joueur ?
     *
     * @param effectId identifiant de registre, ex. {@code "minecraft:darkness"}
     *                 — voir {@link AccessPoint#PLAYER_HAS_EFFECT} pour pourquoi
     *                 une chaîne plutôt que l'objet du jeu.
     * @return {@code false} hors partie, sur une version non liée, ou si cette
     *         version ne connaît pas cet identifiant.
     */
    public static boolean hasEffect(String effectId) {
        Object v = AccessorRegistry.invoke(AccessPoint.PLAYER_HAS_EFFECT, null, effectId);
        return v instanceof Boolean && (Boolean) v;
    }

    /** Retire cet effet s'il est actif — sans effet si la version ne le connaît pas. */
    public static void removeEffect(String effectId) {
        AccessorRegistry.invoke(AccessPoint.PLAYER_REMOVE_EFFECT, null, effectId);
    }

    /**
     * Latence du joueur local en millisecondes, ou {@code -1} si elle n'est pas
     * lisible (hors partie, entrée de liste absente, version non liée).
     *
     * <p>L'appelant DOIT distinguer ce {@code -1} d'un vrai ping : un « 0 ms »
     * affiché à la place serait faux.
     */
    public static int ping() {
        return AccessorRegistry.getInt(AccessPoint.PLAYER_PING, null, -1);
    }

    /**
     * Effets actifs, en données neutres — liste VIDE hors partie ou sur une
     * version non liée, jamais {@code null}.
     *
     * <p>Voir {@link com.yuyuframe.launcheragent.apimixin.PlayerEffect} : c'est
     * un porteur à nous, pas un objet du jeu, précisément pour que l'appelant
     * puisse le lire sans nommer de type de version.
     */
    @SuppressWarnings("unchecked")
    public static java.util.List<com.yuyuframe.launcheragent.apimixin.PlayerEffect> activeEffects() {
        Object v = AccessorRegistry.get(AccessPoint.PLAYER_ACTIVE_EFFECTS, null);
        return v instanceof java.util.List
            ? (java.util.List<com.yuyuframe.launcheragent.apimixin.PlayerEffect>) v
            : java.util.Collections.emptyList();
    }

    /** Points de vie courants — 0 hors partie. */
    public static float health() {
        return AccessorRegistry.getFloat(AccessPoint.PLAYER_HEALTH, null, 0f);
    }

    /** Points de vie maximum — 0 hors partie. */
    public static float maxHealth() {
        return AccessorRegistry.getFloat(AccessPoint.PLAYER_MAX_HEALTH, null, 0f);
    }

    /**
     * Charge de l'attaque, de 0 (vient de frapper) à 1 (prête).
     *
     * @param partialTick avancement dans le tick courant, comme le passe vanilla.
     * @param fallback    valeur rendue hors partie ou sur une version non liée —
     *                    {@code CrosshairModule} y met {@code -1} pour distinguer
     *                    « indisponible » de « prête », deux cas qui ne se
     *                    dessinent pas pareil.
     */
    public static float attackStrengthScale(float partialTick, float fallback) {
        Object v = AccessorRegistry.invoke(AccessPoint.PLAYER_ATTACK_STRENGTH, null, Float.valueOf(partialTick));
        return v instanceof Number ? ((Number) v).floatValue() : fallback;
    }
}
