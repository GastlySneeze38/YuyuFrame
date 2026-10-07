package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.mojang.authlib.GameProfile;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.runtime.module.visual.YuyuPlayersModule;
import com.yuyuframe.launcheragent.runtime.presence.NativePresence;
import net.minecraft.entity.player.PlayerEntity;

import java.util.UUID;

/**
 * Marque l'étiquette de nom d'un joueur YuyuFrame.
 *
 * <p><b>Provisoire</b> : le pseudo passe en NOIR, uniquement pour vérifier en
 * jeu que la chaîne entière fonctionne (jeton reçu du launcher, question au
 * serveur, réponse en mémoire). Ce n'est pas l'affichage final.
 *
 * <p>La question porte sur le PROFIL du joueur — son UUID et son pseudo réels
 * — jamais sur le texte de l'étiquette, que les serveurs décorent (grade,
 * couleur d'équipe) et qui ne dit rien de fiable.
 */
@YarnNamed
public final class PresenceLabel189 {

    private PresenceLabel189() {
    }

    /**
     * @param entity l'entité dont on dessine l'étiquette
     * @param text   le texte que vanilla allait dessiner
     * @return ce texte, ou sa version marquée si le joueur utilise YuyuFrame
     */
    public static String decorate(Object entity, String text) {
        // Module éteint (Confort visuel → Joueurs YuyuFrame) : aucune question ne part.
        if (!(entity instanceof PlayerEntity) || !YuyuPlayersModule.active() || !NativePresence.available()) {
            return text;
        }
        GameProfile profile = ((PlayerEntity) entity).getGameProfile();
        if (profile == null) {
            return text;
        }
        UUID id = profile.getId();
        String name = profile.getName();
        if (id == null || name == null) {
            return text;
        }
        int answer = NativePresence.query(id, name);
        logStatusChange();
        return answer == NativePresence.PRESENT ? "§0" + stripFormatting(text) : text;
    }

    private static long nextStatusCheck;
    private static int lastState = -1, lastCached = -1, lastPresent = -1;

    /**
     * Provisoire, comme le noir : une ligne dans le journal de l'agent quand
     * l'état du noyau change (jeton reçu ou non, joueurs en mémoire, joueurs
     * reconnus). Permet de vérifier la chaîne même seul sur un serveur, où
     * l'on ne voit l'étiquette de personne. Au plus une lecture par seconde.
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
            com.yuyuframe.launcheragent.base.log.LauncherLog.agent(2, "[PRESENCE] état=" + s[0]
                + " (1 en marche, 2 nouvel essai, 3 quota, 4 jeton refusé, 5 pas de jeton)"
                + " — joueurs en mémoire=" + s[1] + ", reconnus YuyuFrame=" + s[2] + ", en attente=" + s[3]);
        }
    }

    /** Sans ses codes de couleur : un code plus loin dans le texte reprendrait la main sur le noir. */
    private static String stripFormatting(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < length) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
