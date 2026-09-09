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

    /** {@code true} si un joueur est en partie — raccourci de lisibilité pour les modules. */
    public static boolean inGame() {
        return player() != null;
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
        LocalPlayer p = player();
        if (p == null) return new double[]{ 0, 0, 0 };
        return new double[]{ p.getX(), p.getY(), p.getZ() };
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
        LocalPlayer p = player();
        return p == null ? 0f : p.getYRot();
    }

    /** Rotation verticale (pitch), en degrés — 0 hors partie. */
    public static float pitch() {
        LocalPlayer p = player();
        return p == null ? 0f : p.getXRot();
    }

    /** Hauteur des yeux au-dessus des pieds — 0 hors partie. */
    public static float eyeHeight() {
        LocalPlayer p = player();
        return p == null ? 0f : p.getEyeHeight();
    }
}
