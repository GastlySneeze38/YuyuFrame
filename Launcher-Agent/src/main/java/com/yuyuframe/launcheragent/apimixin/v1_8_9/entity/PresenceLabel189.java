package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.mojang.authlib.GameProfile;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.presence.NativePresence;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
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
        if (!PlayerStyle.active || text == null || !(entity instanceof PlayerEntity)) {
            return false;
        }
        GameProfile profile = ((PlayerEntity) entity).getGameProfile();
        if (profile == null || profile.getName() == null || !text.contains(profile.getName())) {
            return false;
        }
        return styled(profile);
    }

    /** La ligne de la liste Tab est-elle celle d'un joueur à styliser ? */
    public static boolean styledEntry(Object entry) {
        if (!PlayerStyle.active || !PlayerStyle.tab || !(entry instanceof PlayerListEntry)) {
            return false;
        }
        return styled(((PlayerListEntry) entry).getProfile());
    }

    private static boolean styled(GameProfile profile) {
        if (profile == null) {
            return false;
        }
        boolean styled = PlayerStyle.applies(profile.getId(), profile.getName());
        logStatusChange();
        return styled;
    }

    private static long nextStatusCheck;
    private static int lastState = -1, lastCached = -1, lastPresent = -1;

    /**
     * Provisoire : une ligne dans le journal de l'agent quand l'état du noyau
     * change (jeton reçu ou non, joueurs en mémoire, joueurs reconnus). Permet
     * de vérifier la chaîne même seul sur un serveur. Au plus une lecture par
     * seconde.
     */
    private static void logStatusChange() {
        long now = System.currentTimeMillis();
        if (now < nextStatusCheck) {
            return;
        }
        nextStatusCheck = now + 1000;
        int[] s = NativePresence.status();
        if (s[0] != lastState || s[1] != lastCached || s[2] != lastPresent) {
            lastState = s[0];
            lastCached = s[1];
            lastPresent = s[2];
            LauncherLog.agent(2, "[PRESENCE] état=" + s[0]
                + " (1 en marche, 2 nouvel essai, 3 quota, 4 jeton refusé, 5 pas de jeton)"
                + " — joueurs en mémoire=" + s[1] + ", reconnus YuyuFrame=" + s[2] + ", en attente=" + s[3]);
        }
    }
}
