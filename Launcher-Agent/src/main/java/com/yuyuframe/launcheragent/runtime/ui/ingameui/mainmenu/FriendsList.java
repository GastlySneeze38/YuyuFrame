package com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu;

import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * La liste de l'onglet Amis, dans une zone qui défile : trois sections, des
 * cartes d'autant plus grandes que l'ami est disponible ({@link FriendTile}).
 * En jeu : deux colonnes ; en ligne et hors ligne : trois. Moins quand la
 * place manque.
 *
 * <p>La liste ne se refait que quand la composition change (qui est là, dans
 * quelle section : {@link #signature}) ; le reste, chaque carte le relit.
 */
public final class FriendsList {
    private FriendsList() {}

    private enum Group { IN_GAME, ONLINE, OFFLINE }

    private static Group groupOf(GameFriends.Friend f) {
        if (f.inGame) return Group.IN_GAME;
        return f.online() ? Group.ONLINE : Group.OFFLINE;
    }

    private static boolean matches(GameFriends.Friend f, String filter) {
        return filter.isEmpty() || f.name.toLowerCase(Locale.ROOT).contains(filter);
    }

    /** Qui est là et dans quelle section : quand elle change, la liste se refait. */
    public static String signature(List<GameFriends.Friend> friends, String filter) {
        StringBuilder b = new StringBuilder(filter).append('|');
        for (GameFriends.Friend f : friends) {
            if (matches(f, filter)) b.append(groupOf(f).ordinal()).append(f.id).append(',');
        }
        return b.toString();
    }

    /** Le premier ami à montrer dans la fiche : celui en jeu, sinon en ligne, sinon le premier. */
    public static String firstId(List<GameFriends.Friend> friends) {
        for (GameFriends.Friend f : friends) if (f.inGame) return f.id;
        for (GameFriends.Friend f : friends) if (f.online()) return f.id;
        return friends.isEmpty() ? null : friends.get(0).id;
    }

    /** Remplit {@code scroll}, de {@code top} vers le bas, sur {@code width} à partir de {@code x}. */
    public static void build(UiScrollContainer scroll, float x, float top, float width, List<GameFriends.Friend> friends,
                             String filter, Supplier<String> selected, Consumer<String> onSelect) {
        String f = filter.trim().toLowerCase(Locale.ROOT);
        List<GameFriends.Friend> inGame = new ArrayList<>(), online = new ArrayList<>(), offline = new ArrayList<>();
        for (GameFriends.Friend friend : friends) {
            if (!matches(friend, f)) continue;
            switch (groupOf(friend)) {
                case IN_GAME: inGame.add(friend); break;
                case ONLINE: online.add(friend); break;
                default: offline.add(friend); break;
            }
        }

        float y = top;
        boolean first = true;
        Object[][] sections = {
            { "En jeu", inGame, FriendTile.Size.IN_GAME, 2, px(380f) },
            { "En ligne", online, FriendTile.Size.ONLINE, 3, px(240f) },
            { "Hors ligne", offline, FriendTile.Size.OFFLINE, 3, px(220f) },
        };
        for (Object[] s : sections) {
            @SuppressWarnings("unchecked")
            List<GameFriends.Friend> list = (List<GameFriends.Friend>) s[1];
            if (list.isEmpty()) continue;
            FriendTile.Size size = (FriendTile.Size) s[2];
            int maxCols = (Integer) s[3];
            float minW = (Float) s[4];
            float gap = px(size == FriendTile.Size.IN_GAME ? UiTheme.Space.S14 : UiTheme.Space.S12);

            if (!first) y -= px(UiTheme.Space.S28);
            first = false;
            float titleH = px(UiTheme.Space.S32);
            SectionTitle title = new SectionTitle(Lang.tr((String) s[0]), String.valueOf(list.size()));
            title.x = x;
            title.y = y - titleH;
            title.w = width;
            title.h = titleH;
            scroll.add(title);
            y -= titleH + px(UiTheme.Space.S12);

            int cols = Math.max(1, Math.min(maxCols, (int) Math.floor((width + gap) / (minW + gap))));
            float cw = (width - (cols - 1) * gap) / cols;
            float th = FriendTile.height(size);
            for (int i = 0; i < list.size(); i++) {
                int row = i / cols, col = i % cols;
                FriendTile tile = new FriendTile(list.get(i).id, size, selected, onSelect);
                tile.x = x + col * (cw + gap);
                tile.y = y - (row + 1) * th - row * gap;
                tile.w = cw;
                tile.h = th;
                scroll.add(tile);
            }
            int rows = (list.size() + cols - 1) / cols;
            y -= rows * th + (rows - 1) * gap;
        }
    }
}
