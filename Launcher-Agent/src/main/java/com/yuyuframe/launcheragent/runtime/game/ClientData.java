package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.MinecraftAccessor261;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.sounds.SoundManager;

/**
 * Accès PARTAGÉ à l'état du client Minecraft — <b>zéro réflexion</b>.
 * Pendant de {@link PlayerData}, qui couvre le joueur et le monde.
 *
 * <p>Créé lors du refacto du dossier {@code module/} (2026-08-27). Constat :
 * 17 modules appelaient {@code McReflect.minecraftClient()} chacun de leur
 * côté, suivis de 17 lectures réflexives d'un champ de {@code Minecraft}
 * ({@code player} ×8, {@code options} ×6, {@code user}, {@code gui},
 * {@code fps}). Toutes ces cibles avaient DÉJÀ un accessor dans
 * {@link MinecraftAccessor261} : la réflexion y était purement historique.
 *
 * <p><b>Champs → accessors Mixin, méthodes → appel direct.</b> Un
 * {@code @Accessor} Sponge synthétise un getter de CHAMP au tissage ; il ne
 * s'applique pas à une méthode déjà publique. {@link #soundManager()} et
 * {@link #connection()} appellent donc directement {@code getSoundManager()}/
 * {@code getConnection()} — méthodes publiques au descripteur vérifié par
 * javap sur le jar client 26.1.2 réel (voir le stub {@code Minecraft}). Ce
 * n'est pas de la réflexion, et ça passe quand même par cette classe pour que
 * la surface d'accès reste unique.
 *
 * <p><b>PORTÉE : bracket 26.1.2 uniquement</b>, comme {@link PlayerData} —
 * hors de ce bracket, tout renvoie une valeur neutre. Voir sa javadoc pour le
 * raisonnement complet sur le portage multiversion.
 */
public final class ClientData {
    private ClientData() {}

    /**
     * Instance de {@code Minecraft} vue comme accessor, ou {@code null} hors
     * bracket 26.1.2.
     *
     * <p>Le try/catch n'est pas décoratif : {@code Minecraft.getInstance()}
     * référence un nom de classe RÉEL, absent d'un bracket obfusqué — on y
     * récolte un {@code NoClassDefFoundError}, d'où le {@code Throwable}.
     */
    private static MinecraftAccessor261 accessor() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc instanceof MinecraftAccessor261 ? (MinecraftAccessor261) mc : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Instance brute de {@code Minecraft}, ou {@code null} — pour les APPELS DE MÉTHODE publics, jamais pour lire un champ (passer par les getters ci-dessous). */
    public static Minecraft client() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Options du jeu, ou {@code null}. */
    public static Options options() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$options();
    }

    /** Session utilisateur (pseudo, UUID), ou {@code null}. */
    public static User user() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$user();
    }

    /**
     * Écran actuellement ouvert, ou {@code null} si le joueur est en jeu.
     *
     * <p>Ajouté le 2026-08-30 pour le rendu du HUD depuis la passe GUI : c'est
     * le TYPE d'écran ouvert qui décide de la visibilité de chaque élément
     * (voir {@code HudOverlayRenderer}). L'ancien chemin recevait cet écran du
     * mixin ; en émettant depuis un hook d'extraction, il faut aller le
     * chercher.
     */
    public static Screen screen() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$screen();
    }

    /** HUD vanilla, ou {@code null}. */
    public static Gui gui() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$gui();
    }

    /**
     * FPS courant, ou {@code -1} si indisponible.
     *
     * <p>{@code la$fps()} est {@code static} (le champ l'est), et son corps de
     * repli lève volontairement si le mixin n'est pas tissé plutôt que de
     * renvoyer un 0 faux — voir sa javadoc. D'où le catch ici, qui traduit ce
     * cas en {@code -1} explicitement « inconnu ».
     */
    public static int fps() {
        try {
            return MinecraftAccessor261.la$fps();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Gestionnaire de sons, ou {@code null}. Méthode publique, pas un champ — voir la javadoc de classe. */
    public static SoundManager soundManager() {
        Minecraft mc = client();
        try {
            return mc == null ? null : mc.getSoundManager();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Connexion réseau au serveur, ou {@code null} hors partie. Méthode publique, pas un champ. */
    /**
     * Gestionnaire de ressources du jeu — {@code null} hors bracket 26.1.2.
     *
     * <p>C'est par LUI qu'il faut charger toute texture que l'on veut voir
     * suivre les resource packs : le classloader, lui, sert la version du
     * jar et ignore les packs. Ajouté le 2026-08-31 après constat sur le pack
     * « Ice Cream » de l'utilisateur, qui surcharge à la fois les sprites
     * vanilla de faim/cœur ET l'atlas d'AppleSkin.
     */
    public static net.minecraft.server.packs.resources.ReloadableResourceManager resourceManager() {
        MinecraftAccessor261 mc = accessor();
        return mc == null ? null : mc.la$resourceManager();
    }

    public static ClientPacketListener connection() {
        Minecraft mc = client();
        try {
            return mc == null ? null : mc.getConnection();
        } catch (Throwable t) {
            return null;
        }
    }
}
