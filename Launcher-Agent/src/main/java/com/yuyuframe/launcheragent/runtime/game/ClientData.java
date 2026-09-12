package com.yuyuframe.launcheragent.runtime.game;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Accès PARTAGÉ à l'état du client Minecraft — <b>zéro réflexion</b>.
 * Pendant de {@link PlayerData}, qui couvre le joueur et le monde.
 *
 * <p>Créé lors du refacto du dossier {@code module/} (2026-08-27). Constat :
 * 17 modules appelaient {@code McReflect.minecraftClient()} chacun de leur
 * côté, suivis de 17 lectures réflexives d'un champ de {@code Minecraft}
 * ({@code player} ×8, {@code options} ×6, {@code user}, {@code gui},
 * {@code fps}). Toutes ces cibles avaient DÉJÀ un accessor : la réflexion y
 * était purement historique.
 *
 * <p><b>Champs → accessors Mixin, méthodes → appel direct.</b> Un
 * {@code @Accessor} Sponge synthétise un getter de CHAMP au tissage ; il ne
 * s'applique pas à une méthode déjà publique. {@link #connection()} appelle
 * donc directement {@code getConnection()} — méthode publique au descripteur
 * vérifié par javap sur le jar client 26.1.2 réel (voir le stub
 * {@code Minecraft}). Ce n'est pas de la réflexion, et ça passe quand même par
 * cette classe pour que la surface d'accès reste unique.
 *
 * <p>Ce chemin direct a toutefois un COÛT que {@code connection()} paie encore
 * et que {@link #playUiSound} ne paie plus : il nomme des types 26.1.2 dans sa
 * signature, donc il ne sert que cette version. Toute méthode d'ici qui doit
 * fonctionner sur les deux tranches passe par un {@link AccessPoint}.
 *
 * <p><b>Plus aucun accessor n'est nommé ici</b> (2026-09-09) : les lectures
 * passent par {@link AccessorRegistry}, qui route chaque {@link AccessPoint}
 * vers l'accessor de la tranche active. Voir {@link PlayerData} pour le
 * raisonnement complet et la limite qui subsiste (les TYPES de retour restent
 * ceux du jeu). Hors tranche liée, tout renvoie une valeur neutre.
 */
public final class ClientData {
    private ClientData() {}

    /** Instance brute de {@code Minecraft}, ou {@code null} — pour les APPELS DE MÉTHODE publics, jamais pour lire un champ (passer par les getters ci-dessous). */
    public static Minecraft client() {
        return AccessorRegistry.as(Minecraft.class, AccessPoint.CLIENT_INSTANCE, null);
    }

    /** Options du jeu, ou {@code null}. */
    public static Options options() {
        return AccessorRegistry.as(Options.class, AccessPoint.CLIENT_OPTIONS, null);
    }

    /**
     * Pseudo du joueur connecté, ou {@code ""} si indisponible.
     *
     * <p>Rendu en {@code String} par le point d'accès : les appelants n'ont
     * ainsi à nommer ni la session du jeu, ni {@code GameProfile}
     * (com.mojang.authlib), absent de tout classpath de compilation.
     */
    public static String username() {
        Object v = AccessorRegistry.get(AccessPoint.CLIENT_USERNAME, null);
        return v instanceof String ? (String) v : "";
    }

    /**
     * Les mêmes options, mais en poignée OPAQUE — pour les chemins PARTAGÉS
     * entre versions.
     *
     * <p>{@link #options()} nomme le type 26.1.2 dans SA SIGNATURE : l'appeler
     * suffit à lier cette classe, donc {@code NoClassDefFoundError} ailleurs —
     * c'est ce qui tuait {@code ZoomModule} et {@code FullbrightModule} à chaque
     * tick sur 1.21.11, alors même que le point d'accès {@code CLIENT_OPTIONS}
     * est lié des DEUX côtés.
     *
     * <p>L'aiguillage par version, lui, fonctionne déjà : {@link AccessorRegistry}
     * choisit les liaisons de la version détectée. Seul le type de retour faisait
     * obstacle. Les appelants qui ne font que repasser la poignée à
     * {@link GameOptions} n'ont de toute façon aucun usage du type.
     */
    public static Object optionsObject() {
        return AccessorRegistry.as(Object.class, AccessPoint.CLIENT_OPTIONS, null);
    }

    /** Session utilisateur (pseudo, UUID), ou {@code null}. */
    public static User user() {
        return AccessorRegistry.as(User.class, AccessPoint.CLIENT_USER, null);
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
        return AccessorRegistry.as(Screen.class, AccessPoint.CLIENT_SCREEN, null);
    }

    /**
     * Le même écran, mais en poignée OPAQUE — pour les chemins PARTAGÉS entre
     * versions.
     *
     * <p>{@link #screen()} nomme le type 26.1.2 dans SA SIGNATURE : l'appeler
     * suffit à faire lier cette classe, donc {@code NoClassDefFoundError} sur
     * une autre version. C'est exactement ce qui empêchait le HUD de s'afficher
     * en 1.21.11 (v1067) : le handler de {@code HUD_EXTRACT_CHAT} mourait ici,
     * avant d'avoir rien dessiné. Un chemin commun aux versions ne doit donc
     * jamais passer par la variante typée.
     *
     * <p>Les appelants n'en font de toute façon qu'un {@code != null} ou une
     * classification par nom ({@code HudScreenKind}) — le type ne leur sert pas.
     */
    public static Object screenObject() {
        return AccessorRegistry.as(Object.class, AccessPoint.CLIENT_SCREEN, null);
    }

    /**
     * Ouvre un écran, ou le ferme si {@code screen} est {@code null} —
     * {@code true} si l'ouverture a eu lieu.
     *
     * <p>Prend un {@link Object} : nos écrans deviennent des écrans valides du
     * jeu au CHARGEMENT ({@code ScreenStubPatcher}), pas à la compilation. Voir
     * {@link AccessPoint#CLIENT_SET_SCREEN}.
     */
    public static boolean setScreen(Object screen) {
        Object v = AccessorRegistry.invoke(AccessPoint.CLIENT_SET_SCREEN, null, screen);
        return v instanceof Boolean && (Boolean) v;
    }

    /** HUD vanilla, ou {@code null}. */
    public static Gui gui() {
        return AccessorRegistry.as(Gui.class, AccessPoint.CLIENT_GUI, null);
    }

    /**
     * FPS courant, ou {@code -1} si indisponible.
     *
     * <p>Le champ est {@code static}, donc l'accès n'a pas de receveur. Son
     * accessor lève volontairement si le mixin n'est pas tissé, plutôt que de
     * renvoyer un 0 faux ; {@link AccessorRegistry} attrape, le signale une
     * fois dans le log, et le repli {@code -1} ci-dessous dit explicitement
     * « inconnu ».
     */
    public static int fps() {
        return AccessorRegistry.getInt(AccessPoint.CLIENT_FPS, null, -1);
    }

    /**
     * Joue un son d'interface — {@code true} s'il est effectivement parti.
     *
     * <p>Remplace l'ancien {@code soundManager()} (retiré le 2026-09-12), qui
     * rendait le gestionnaire de sons 26.1.2 : chaque appelant devait ensuite
     * nommer {@code SimpleSoundInstance} et {@code SoundEvents} lui-même, donc
     * ne tournait que sur cette version. Toute la chaîne est désormais faite
     * par la liaison de la tranche active.
     *
     * @param soundId identifiant de registre, ex.
     *                {@code "minecraft:block.amethyst_block.chime"} — voir
     *                {@link AccessPoint#SOUND_PLAY_UI} pour pourquoi une chaîne.
     * @param pitch   hauteur, 1 = normale.
     * @param volume  volume, 1 = plein.
     */
    public static boolean playUiSound(String soundId, float pitch, float volume) {
        Object v = AccessorRegistry.invoke(AccessPoint.SOUND_PLAY_UI, null,
            soundId, Float.valueOf(pitch), Float.valueOf(volume));
        return v instanceof Boolean && (Boolean) v;
    }

    /**
     * Taille de l'écran en PIXELS GUI — {@code {largeur, hauteur}}, ou
     * {@code null} si la fenêtre n'est pas lisible.
     *
     * <p>Les VRAIES dimensions, pas une reconstruction par division : l'arrondi
     * de vanilla ne se redérive pas exactement, et un pixel d'écart décale tout
     * un alignement sur ses barres.
     */
    public static int[] guiSize() {
        Object v = AccessorRegistry.get(AccessPoint.CLIENT_GUI_SIZE, null);
        return v instanceof int[] && ((int[]) v).length == 2 ? (int[]) v : null;
    }

    /**
     * Contenu BRUT d'une ressource du jeu, ou {@code null} si elle est absente.
     *
     * <p>Passe par le gestionnaire de ressources, donc SUIT LES RESOURCE PACKS
     * — un chargement par le classloader sert la version du jar et les ignore.
     * L'appelant décode lui-même les octets.
     */
    public static byte[] resourceBytes(String namespace, String path) {
        if (namespace == null || path == null) return null;
        Object v = AccessorRegistry.invoke(AccessPoint.RESOURCE_BYTES, null, namespace, path);
        return v instanceof byte[] ? (byte[]) v : null;
    }

    /**
     * Fenêtre du jeu — {@code null} hors bracket 26.1.2.
     *
     * <p>Ajouté pour {@code UiVanillaItemRenderer.guiScale()}, qui positionne
     * tout le rendu relatif au HUD vanilla et était appelé à chaque frame par
     * réflexion. {@code getGuiScaledWidth()} est une méthode publique, seul
     * l'accès au champ {@code window} demandait l'accessor.
     */
    public static com.mojang.blaze3d.platform.Window window() {
        return AccessorRegistry.as(com.mojang.blaze3d.platform.Window.class, AccessPoint.CLIENT_WINDOW, null);
    }

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
        return AccessorRegistry.as(net.minecraft.server.packs.resources.ReloadableResourceManager.class,
            AccessPoint.CLIENT_RESOURCE_MANAGER, null);
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
