package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apimixin.mapping.McReflect;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;

import java.util.UUID;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

/** Port de PvP-Mod PingConfig/PingHud — sa propre carte, comme dans la référence. */
public final class PingModule extends SingleHudModule {
    public PingModule() {
        super("ping", "Ping", "Affiche la latence réseau", true,
            new HudElement("ping", "Ping", HudAnchor.TOP_RIGHT, 8f, 8f, new ContentSource()));
        hudElement().textColor = new UiColor(120, 220, 140, 255);
        hudElement().accentSuffix = " ms";
        iconUrl = icons8("wifi");
    }

    /** Ping réel du joueur local — retrouve le PlayerListEntry via son UUID (même chemin que l'onglet multijoueur vanilla). */
    private static final class ContentSource implements HudElement.ContentSource {
        @Override
        public String[] lines() {
            // Joueur par l'accessor Mixin (PlayerData), connexion par
            // ClientData (getConnection(), méthode publique) — zéro réflexion.
            //
            // Le repli réflexif multi-bracket a été supprimé le 2026-08-27.
            // HISTORIQUE à connaître avant de porter ce module vers un autre
            // bracket : {@code Entity.getUuid()} n'a AUCUNE entrée nommée dans
            // les mappings Yarn 1.20.4 (seul le CHAMP uuid l'est — vérifié dans
            // mappings/yarn-1.20.4-mergedv2.jar : "f Ljava/util/UUID; ay
            // field_6021 uuid", aucune ligne "m ... getUuid"). Une résolution
            // par nom de méthode y renvoyait null, et l'appel qui suivait
            // partait en NullPointerException avalée par le catch — "-- ms" en
            // boucle sans le moindre log. L'accessor rend ce piège sans objet
            // ici, mais il se reposera tel quel sur un bracket obfusqué.
            try {
                // Toute la chaîne (connexion → entrée de liste → latence) est
                // faite par la liaison de la version : le module ne tient plus
                // ni le joueur ni la connexion, donc ne nomme plus aucun type
                // du jeu. Le -1 est distinct d'un vrai ping, voir PlayerData.
                int ping = PlayerData.ping();
                return ping < 0 ? new String[]{ "-- ms" } : new String[]{ ping + " ms" };
            } catch (Throwable t) {
                return new String[]{ "-- ms" };
            }
        }
    }
}
