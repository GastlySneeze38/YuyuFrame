package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.MinecraftAccessor261;
import net.minecraft.client.Minecraft;
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
 * <p><b>Tout passe par les accessors Mixin</b> ({@link MinecraftAccessor261},
 * déclaré via {@code MixinHookPointRegistry}) plutôt que par la
 * réflexion. C'est ce qui rend le portage multiversion mécanique : les getters
 * sont synthétisés au tissage, donc résolus une fois pour toutes au chargement
 * au lieu d'être re-résolus à chaque appel, et surtout un changement de nom ou
 * de visibilité d'un champ sur une autre version ne touche que l'accessor de
 * CE bracket — jamais un appelant.
 *
 * <p>{@code Minecraft.player} et {@code level} sont pourtant PUBLICS sur
 * 26.1.2 : un accès direct compilerait. Ils passent quand même par l'accessor,
 * sur demande explicite — mélanger accès directs et accessors ferait perdre
 * exactement la propriété ci-dessus.
 *
 * <p><b>PORTÉE : bracket 26.1.2 uniquement.</b> Les accessors n'existent que
 * pour ce bracket ({@code *261}) ; sur 1.8.9/1.16.5/1.20.4/1.21.4, où le jeu
 * est obfusqué, ces classes n'existent pas et tout renvoie ici une valeur
 * neutre. Porter un module vers un autre bracket demande d'y écrire son jeu
 * d'accessors et de router cette classe dessus — c'est précisément le travail
 * que cette centralisation rend faisable en un seul endroit.
 *
 * <p>Portée VOLONTAIREMENT limitée à ce qui était réellement dupliqué. Faire
 * transiter par ici tout ce que chaque module lit (durabilité d'armure, effets
 * de potion, ping…) en ferait une classe fourre-tout : ces lectures-là sont
 * propres à un module et n'ont aucune raison d'être partagées.
 */
public final class PlayerData {
    private PlayerData() {}

    /**
     * Instance de {@code Minecraft} vue comme accessor, ou {@code null} hors
     * bracket 26.1.2.
     *
     * <p>Le try/catch est indispensable et non décoratif : {@code
     * Minecraft.getInstance()} référence un nom de classe RÉEL, qui n'existe
     * pas sur un bracket obfusqué — on y récolte un {@code
     * NoClassDefFoundError}, pas une exception ordinaire, d'où le
     * {@code Throwable}.
     */
    private static MinecraftAccessor261 accessor() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc instanceof MinecraftAccessor261 ? (MinecraftAccessor261) mc : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Joueur courant, ou {@code null} (hors partie, ou bracket non supporté). */
    public static LocalPlayer player() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$player();
    }

    /** Monde client courant, ou {@code null}. */
    public static ClientLevel level() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$level();
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
