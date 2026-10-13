package com.yuyuframe.launcheragent.apimixin.data;

/**
 * Le paquet « une entité a subi des dégâts », en données NEUTRES — contexte
 * de {@code HookPoint.DAMAGE_EVENT}. Le mixin de chaque version le remplit à
 * partir du paquet du jeu, qui ne sort jamais de son dossier.
 *
 * <p>Les identifiants sont ceux du réseau ({@code AccessPoint.ENTITY_NETWORK_ID}).
 * {@link #NONE} quand le paquet n'en donne pas : dégâts d'environnement
 * (chute, lave, vide).
 */
public final class DamageEvent {

    /** Pas d'entité à cette place. */
    public static final int NONE = -1;

    /** L'entité touchée. */
    public final int entityId;
    /**
     * Type de dégât, chemin de registre sans espace de noms
     * ({@code "player_attack"}, {@code "mace_smash"}, {@code "fall"}) — ou
     * {@code null} si le client ne le connaît pas.
     */
    public final String type;
    /** L'auteur des dégâts (le joueur qui frappe), ou {@link #NONE}. */
    public final int causeId;
    /** Ce qui a touché (lui-même, ou son projectile), ou {@link #NONE}. */
    public final int directId;

    public DamageEvent(int entityId, String type, int causeId, int directId) {
        this.entityId = entityId;
        this.type = type;
        this.causeId = causeId;
        this.directId = directId;
    }
}
