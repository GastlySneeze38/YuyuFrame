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

    /** L'entité dont on dessine l'étiquette est-elle un joueur à styliser ? */
    public static boolean styled(Object entity) {
        if (!PlayerStyle.active || !(entity instanceof PlayerEntity)) {
            return false;
        }
        return styled(((PlayerEntity) entity).getGameProfile());
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
