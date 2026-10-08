package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.mojang.authlib.GameProfile;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.PlayerEntity;

/**
 * « Ce joueur doit-il être stylisé ? », pour les deux endroits de la 1.8.9
 * où son pseudo s'affiche : l'étiquette au-dessus de sa tête et la liste Tab.
 *
 * <p>La question porte sur le PROFIL du joueur — son UUID et son pseudo réels
 * — jamais sur le texte affiché, que les serveurs décorent (grade, couleur
 * d'équipe) et qui ne dit rien de fiable. La réponse et les réglages sont dans
 * {@link PlayerStyle}.
 */
@YarnNamed
public final class PresenceLabel189 {

    private PresenceLabel189() {
    }

    /**
     * L'étiquette qu'on dessine est-elle le PSEUDO d'un joueur à styliser ?
     *
     * <p>Un joueur peut avoir deux étiquettes : son pseudo et, juste dessous,
     * la ligne de score que le serveur affiche sous les noms (les points de
     * vie, le plus souvent). Les deux passent par la même méthode de vanilla,
     * avec la même entité : c'est le texte qui les distingue, la ligne de
     * score ne contenant pas le pseudo. Elle garde l'aspect de vanilla.
     */
    public static boolean styled(Object entity, String text) {
        if (text == null || !(entity instanceof PlayerEntity)) {
            return false;
        }
        // Son propre nametag a son réglage à lui, sans question au serveur.
        boolean self = entity == MinecraftClient.getInstance().player;
        if (self ? !PlayerStyle.ownStyled : !PlayerStyle.active) {
            return false;
        }
        GameProfile profile = ((PlayerEntity) entity).getGameProfile();
        if (profile == null || profile.getName() == null || !text.contains(profile.getName())) {
            return false;
        }
        return self || styled(profile);
    }

    /** La ligne de la liste Tab est-elle celle d'un joueur à styliser ? */
    public static boolean styledEntry(Object entry) {
        if (!PlayerStyle.tab || !(entry instanceof PlayerListEntry)) {
            return false;
        }
        GameProfile profile = ((PlayerListEntry) entry).getProfile();
        // Notre propre ligne : on n'a pas à demander au serveur si l'on est soi-même un joueur YuyuFrame.
        if (isOwn(profile)) {
            return PlayerStyle.enabled;
        }
        return PlayerStyle.active && styled(profile);
    }

    private static boolean isOwn(GameProfile profile) {
        Object player = MinecraftClient.getInstance().player;
        if (profile == null || profile.getId() == null || !(player instanceof PlayerEntity)) {
            return false;
        }
        GameProfile own = ((PlayerEntity) player).getGameProfile();
        return own != null && profile.getId().equals(own.getId());
    }

    private static boolean styled(GameProfile profile) {
        if (profile == null) {
            return false;
        }
        return PlayerStyle.applies(profile.getId(), profile.getName());
    }
}
