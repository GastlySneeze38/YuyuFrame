package com.yuyuframe.launcheragent.apimixin;

/**
 * Un effet actif sur le joueur, en données NEUTRES.
 *
 * <h2>Pourquoi un porteur plutôt qu'une poignée</h2>
 *
 * Les points d'accès qui rendent une poignée ({@code CLIENT_PLAYER}…) ne
 * servent qu'à la repasser au jeu. Ici, l'appelant veut LIRE : un nom, une
 * durée, une couleur. Lui rendre l'objet du jeu l'obligerait à nommer
 * {@code MobEffectInstance} / {@code StatusEffectInstance} pour en tirer quoi
 * que ce soit — donc à ne fonctionner que sur une version.
 *
 * <p>Ce porteur traverse donc la frontière à la place des objets du jeu :
 * chaque liaison le remplit avec les méthodes de SA version, et le module ne
 * connaît plus que ces sept champs.
 *
 * <p>Immuable et sans dépendance : aucun type du jeu, aucun type du moteur
 * graphique. Il vit dans {@code apimixin} et non dans {@code runtime} pour que
 * les liaisons des deux versions puissent le construire sans inverser les
 * couches.
 */
public final class PlayerEffect {

    /** Nom TRADUIT de l'effet, sans le niveau (« Vitesse »), ou {@code "?"}. */
    public final String name;

    /** Niveau interne, 0 = niveau I. */
    public final int amplifier;

    /** Durée restante en TICKS ; sans objet si {@link #infinite}. */
    public final int durationTicks;

    /** Effet à durée illimitée — {@code durationTicks} vaut alors {@code -1} en jeu, d'où ce drapeau explicite. */
    public final boolean infinite;

    /** Couleur de l'effet, en RVB empaqueté. */
    public final int color;

    /** Effet bénéfique (par opposition à néfaste) — décide du tri et de la teinte. */
    public final boolean beneficial;

    /** Identifiant de registre complet, ex. {@code "minecraft:speed"} — {@code null} si illisible. */
    public final String registryId;

    public PlayerEffect(String name, int amplifier, int durationTicks, boolean infinite,
                        int color, boolean beneficial, String registryId) {
        this.name = name;
        this.amplifier = amplifier;
        this.durationTicks = durationTicks;
        this.infinite = infinite;
        this.color = color;
        this.beneficial = beneficial;
        this.registryId = registryId;
    }

    /**
     * Partie « chemin » de l'identifiant ({@code speed}), qui nomme aussi la
     * texture de l'effet — {@code null} si l'identifiant est illisible.
     */
    public String registryPath() {
        if (registryId == null) return null;
        int sep = registryId.indexOf(':');
        return sep < 0 ? registryId : registryId.substring(sep + 1);
    }
}
