package com.yuyuframe.launcheragent.apimixin.data;

/**
 * Le paquet « événement d'entité », en données NEUTRES — contexte de
 * {@code HookPoint.ENTITY_EVENT}.
 */
public final class EntityEvent {

    /** L'entité meurt. */
    public static final int DEATH = 3;
    /** L'entité vient de consommer un totem. */
    public static final int TOTEM = 35;

    /** Identifiant réseau de l'entité ({@code AccessPoint.ENTITY_NETWORK_ID}). */
    public final int entityId;
    /** Code de l'événement, tel que le serveur l'envoie. */
    public final int status;

    public EntityEvent(int entityId, int status) {
        this.entityId = entityId;
        this.status = status;
    }
}
