package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;

import java.lang.reflect.Method;
import java.util.UUID;

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
            try {
                Object mc = McReflect.minecraftClient();
                if (mc == null) return new String[]{ "-- ms" };

                Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                if (player == null) return new String[]{ "-- ms" };

                Object uuid = playerUuid(player);
                if (uuid == null) return new String[]{ "-- ms" };

                // 26.1+ : getNetworkHandler()→getConnection(), getPlayerListEntry()→
                // getPlayerInfo() — vérifiés par javap sur le jar client 26.1.2 réel.
                Object handler = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getNetworkHandler", "getConnection").invoke(mc);
                if (handler == null) return new String[]{ "-- ms" };

                Object entry = McReflect.oneArgMethod(handler.getClass(),
                        "net/minecraft/client/network/ClientPlayNetworkHandler", "getPlayerListEntry", "getPlayerInfo", UUID.class)
                        .invoke(handler, uuid);
                if (entry == null) return new String[]{ "-- ms" };

                int latency = (int) McReflect.noArgMethod(entry.getClass(),
                        "net/minecraft/client/network/PlayerListEntry", "getLatency").invoke(entry);
                return new String[]{ latency + " ms" };
            } catch (Throwable t) {
                return new String[]{ "-- ms" };
            }
        }

        /**
         * BUG TROUVÉ (1.20.4, test utilisateur) : {@code Entity.getUuid()}
         * n'a AUCUNE entrée nommée dans les mappings Yarn 1.20.4 (seul le
         * champ {@code uuid} lui-même est nommé — vérifié dans
         * mappings/yarn-1.20.4-mergedv2.jar : {@code f Ljava/util/UUID; ay
         * field_6021 uuid}, pas de ligne {@code m ... getUuid}) —
         * {@code McReflect.noArgMethod(...)} retombe donc sur le nom Yarn
         * "getUuid" INCHANGÉ (aucune méthode obfusquée réelle ne s'appelle
         * ainsi), renvoie {@code null}, et l'ancien code appelait directement
         * {@code .invoke(player)} dessus SANS vérifier null — NullPointerException
         * avalée silencieusement par le catch de {@code lines()} (aucun log),
         * d'où "-- ms" en boucle sans le moindre indice dans les logs. Lit le
         * champ {@code uuid} directement en repli (comme {@code
         * CoordsModule.playerPos} pour x/y/z) plutôt que de dépendre d'un nom
         * de méthode qui n'existe pas forcément dans les mappings chargées.
         */
        private static Method cachedGetUuid;
        private static boolean getUuidResolveAttempted;

        private Object playerUuid(Object player) throws Exception {
            Method m = cachedGetUuid;
            if (m == null && !getUuidResolveAttempted) {
                getUuidResolveAttempted = true;
                // 26.1+ : getUuid()→getUUID() (casse différente), vérifié par javap.
                m = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getUuid", "getUUID");
                cachedGetUuid = m;
            }
            if (m != null) return m.invoke(player);
            return McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "uuid").get(player);
        }
    }
}
