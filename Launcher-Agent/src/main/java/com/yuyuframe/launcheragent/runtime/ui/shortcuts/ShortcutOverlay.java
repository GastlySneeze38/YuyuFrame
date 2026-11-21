package com.yuyuframe.launcheragent.runtime.ui.shortcuts;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.apimixin.data.PauseScreenTag;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.account.GameAccounts;
import com.yuyuframe.launcheragent.runtime.combat.DuelHistory;
import com.yuyuframe.launcheragent.runtime.friends.GameFriends;
import com.yuyuframe.launcheragent.runtime.friends.GameMissions;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import com.yuyuframe.launcheragent.runtime.skin.AccountFaces;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.MinecraftFont;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpRank;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpScoreUi;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.McTexture;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiText;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.mainmenu.HomeIcons;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Les raccourcis YuyuFrame posés sur l'écran-titre et sur le menu Échap du
 * jeu : compte, menu, missions, rang, modules favoris, amis.
 *
 * <h2>Par-dessus l'écran du jeu</h2>
 *
 * Ce n'est pas un écran : le hub de chaque version appelle {@link #render} à
 * chaque image où un écran du jeu est ouvert, après lui. Les raccourcis lisent
 * la souris eux-mêmes ; l'écran du jeu reçoit donc aussi les clics. Deux
 * règles l'empêchent d'en profiter : un raccourci n'est jamais posé sur un
 * bouton (il est poussé vers la place libre la plus proche), et tant qu'une
 * liste est dépliée ou que l'éditeur est ouvert, les boutons du jeu sont
 * rendus inactifs.
 *
 * <h2>À côté des autres mods</h2>
 *
 * Les boutons de l'écran sont lus dans le jeu, ceux des autres mods compris
 * ({@link ScreenButtons}). Un raccourci « dans la rangée » s'ajoute aux icônes
 * du centre, qui se décalent pour rester centrées. Ce qu'un mod dessine sans
 * créer de bouton ne se voit pas : c'est pour ça que tout se déplace à la main.
 *
 * <h2>L'éditeur</h2>
 *
 * Clic droit sur un raccourci, ou dans le vide : l'écran s'assombrit, les
 * boutons du jeu se marquent comme obstacles, un panneau liste les raccourcis
 * et leur mode (libre, rangée, masqué). On glisse un raccourci libre pour le
 * déplacer ; lâché sur la rangée, il y entre ; tiré hors de la rangée, il en
 * sort.
 *
 * <p>Repère : tout est calculé en pixels de GUI, y vers le BAS, comme les
 * boutons du jeu ; la conversion vers les pixels de la fenêtre (y vers le
 * haut) se fait au dessin.
 */
public final class ShortcutOverlay {
    private ShortcutOverlay() {}

    // ── Reconnaître les écrans ──────────────────────────────────────────────

    private static volatile Class<?> titleClass;
    private static volatile boolean titlePending;

    /**
     * L'écran-titre vient de s'initialiser : c'est l'écran courant, on retient
     * sa classe. Par la classe plutôt que par {@code instanceof} : un mod qui
     * remplace l'écran-titre par le sien reste reconnu.
     */
    public static void titleScreenSeen() {
        Object screen = ClientData.screenObject();
        if (screen != null && !(screen instanceof UiDrawable)) {
            titleClass = screen.getClass();
        } else if (titleClass == null) {
            // Tout premier écran-titre : l'accès à l'écran courant peut ne pas être encore lié.
            titlePending = true;
        }
    }

    private static ShortcutLayout.Screen kindOf(Object screen) {
        if (screen == null) return null;
        if (titlePending) {
            titlePending = false;
            if (titleClass == null) titleClass = screen.getClass();
        }
        if (screen.getClass() == titleClass) return ShortcutLayout.Screen.TITLE;
        if (screen instanceof PauseScreenTag) return ShortcutLayout.Screen.PAUSE;
        return null;
    }

    // ── État ────────────────────────────────────────────────────────────────

    private static Object currentScreen;
    private static ShortcutLayout.Screen currentKind;
    private static boolean editing;
    private static boolean panelRight = true;
    /** Liste dépliée : {@code ACCOUNT}, {@code MODULES} ou {@code null}. */
    private static Shortcut open;
    private static Shortcut dragging;
    private static float dragOffX, dragOffY, dragX, dragY;
    private static boolean errorLogged;
    private static String shownAccountError;
    private static boolean accountsAsked;
    /** Le meilleur rang, tiré de la dernière lecture partagée de l'historique ({@link DuelHistory#cached()}). */
    private static DuelHistory.Rank bestRank;
    private static boolean anyRankedDuel;

    // Dessin de l'image en cours.
    private static float gs = 1f, sw, sh;
    private static int vw, vh;
    private static float mx, my;
    private static ShortcutLayout.Style style = ShortcutLayout.Style.MINECRAFT;

    /** Un raccourci posé pour cette image. */
    private static final class Item {
        final Shortcut s;
        float x, y, w, h;
        final boolean row;
        final String text;

        Item(Shortcut s, boolean row, String text) {
            this.s = s;
            this.row = row;
            this.text = text;
        }

        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    // ── Entrée ──────────────────────────────────────────────────────────────

    /** Appelé à chaque image où un écran du jeu est ouvert. Ne dessine que sur l'écran-titre et le menu Échap. */
    public static void render(UiRenderer renderer, UiInputPoller input, Object screen) {
        render(renderer, input, screen, kindOf(screen));
    }

    /** Même chose quand l'appelant sait déjà de quel écran il s'agit (l'aperçu de l'interface, qui n'a pas de jeu). */
    public static void render(UiRenderer renderer, UiInputPoller input, Object screen, ShortcutLayout.Screen kind) {
        if (screen != currentScreen) enterScreen(screen, kind);
        if (kind == null) return;
        try {
            frame(renderer, input, screen, kind);
        } catch (Throwable error) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[Raccourcis] dessin impossible", error);
            }
        }
    }

    private static void enterScreen(Object screen, ShortcutLayout.Screen kind) {
        if (editing) HudConfigStore.flush();
        currentScreen = screen;
        currentKind = kind;
        editing = false;
        open = null;
        dragging = null;
        panelDragging = false;
        // Relue à côté : le noyau renote chaque duel, ce qui figeait l'ouverture du menu Échap.
        if (kind != null) {
            DuelHistory.refreshIfOlderThan(10_000L);
            // Un pack de ressources activé depuis : boutons et objets relus.
            McTexture.checkPack();
        }
    }

    /** La lecture de l'historique d'où vient {@link #bestRank} ; une nouvelle lecture le refait. */
    private static int rankRevision = -1;

    private static void updateRank() {
        int revision = DuelHistory.revision();
        if (revision == rankRevision) return;
        rankRevision = revision;
        bestRank = null;
        anyRankedDuel = false;
        try {
            DuelHistory history = DuelHistory.cached();
            double bestKey = -1;
            for (int code : PvpScoreUi.MODE_CODES) {
                DuelHistory.Rank rank = history.rank(code);
                if (rank == null || rank.duels == 0) continue;
                anyRankedDuel = true;
                double key = (rank.placed ? 1e9 : 0) + PvpRank.cumulative(rank.division, rank.points) * 1e3 + rank.duels;
                if (key > bestKey) {
                    bestKey = key;
                    bestRank = rank;
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[Raccourcis] rang illisible : " + t);
        }
    }

    // ── Une image ───────────────────────────────────────────────────────────

    private static void frame(UiRenderer r, UiInputPoller in, Object screen, ShortcutLayout.Screen kind) {
        vw = in.fbWidth;
        vh = in.fbHeight;
        if (vw <= 0 || vh <= 0) return;
        gs = Math.max(1, ClientData.guiScale());
        sw = vw / gs;
        sh = vh / gs;
        mx = (float) in.mouseX / gs;
        my = (vh - (float) in.mouseY) / gs;
        style = ShortcutLayout.style();
        updateRank();

        List<ScreenButtons.Button> buttons = ScreenButtons.read(screen);
        ScreenButtons.Row row = ScreenButtons.findRow(buttons, sw, sh);
        keepDataFresh(kind);

        // ── Mise en place ──
        List<Item> rowItems = new ArrayList<>();
        List<Item> freeItems = new ArrayList<>();
        for (Shortcut s : Shortcut.values()) {
            ShortcutLayout.Place place = ShortcutLayout.place(kind, s);
            if (place.mode == ShortcutLayout.Mode.HIDDEN) continue;
            if (s == Shortcut.ACCOUNT && !GameAccounts.available()) continue;
            boolean inRow = place.mode == ShortcutLayout.Mode.ROW && s != dragging;
            Item item = new Item(s, inRow, inRow ? null : label(s));
            (inRow ? rowItems : freeItems).add(item);
        }
        float[] rowSpan = layoutRow(row, rowItems);
        List<float[]> obstacles = new ArrayList<>();
        for (ScreenButtons.Button b : buttons) obstacles.add(new float[]{ b.x, b.y, b.x + b.w, b.y + b.h });
        obstacles.addAll(decor(kind));
        for (Item it : rowItems) obstacles.add(new float[]{ it.x, it.y, it.x + it.w, it.y + it.h });
        // Le panneau de l'éditeur n'est pas un obstacle : s'il poussait les raccourcis, ils sauteraient à son ouverture.
        // Il se déplace par son titre.
        float[] panel = editing ? panelRect(buttons) : null;
        for (Item it : freeItems) {
            it.h = 20;
            it.w = freeWidth(it);
            if (it.s == dragging) {
                it.x = clamp(dragX, 0, sw - it.w);
                it.y = clamp(dragY, 0, sh - it.h);
                continue;
            }
            ShortcutLayout.Place place = ShortcutLayout.place(kind, it.s);
            float[] p = resolve(Math.round(place.x(sw, it.w)), Math.round(place.y(sh, it.h)), it.w, it.h, obstacles);
            it.x = p[0];
            it.y = p[1];
            obstacles.add(new float[]{ it.x, it.y, it.x + it.w, it.y + it.h });
        }
        List<Item> all = new ArrayList<>(rowItems);
        all.addAll(freeItems);

        Item hovered = null;
        for (Item it : all) if (it.contains(mx, my)) hovered = it;
        Popover pop = open == null ? null : popover(open, all);
        if (pop == null) open = null;
        ScreenButtons.setAllInactive(buttons, editing || open != null);

        // ── Verre : le flou n'est calculé que derrière nos formes ──
        if (style == ShortcutLayout.Style.GLASS) {
            List<float[]> glass = new ArrayList<>();
            for (Item it : all) glass.add(fb(it.x, it.y, it.w, it.h));
            if (pop != null) glass.add(fb(pop.x, pop.y, pop.w, pop.h));
            if (panel != null) glass.add(fb(panel[0], panel[1], panel[2] - panel[0], panel[3] - panel[1]));
            r.beginGlassFrame(4, vw, vh, glass.toArray(new float[0][]));
        }

        // ── Dessin ──
        if (editing) drawEditBackdrop(r, buttons, decor(kind), row, rowSpan);
        for (Item it : all) {
            if (it.s == dragging) continue;
            drawItem(r, it, it == hovered || it.s == open);
        }
        if (pop != null) drawPopover(r, pop);
        if (panel != null) drawPanel(r, panel, kind);
        for (Item it : all) {
            if (it.s == dragging) drawItem(r, it, true);
        }
        if (!editing && pop == null && hovered != null && hovered.row && dragging == null) drawTooltip(r, hovered);

        // ── Souris ──
        handleMouse(in, kind, screen, all, hovered, pop, panel, buttons, rowSpan);
    }

    private static void keepDataFresh(ShortcutLayout.Screen kind) {
        if (ShortcutLayout.place(kind, Shortcut.MISSIONS).mode != ShortcutLayout.Mode.HIDDEN) GameMissions.keepFresh();
        if (ShortcutLayout.place(kind, Shortcut.FRIENDS).mode != ShortcutLayout.Mode.HIDDEN) GameFriends.keepFresh();
        if (kind == ShortcutLayout.Screen.TITLE && GameAccounts.available()) {
            if (!accountsAsked) {
                accountsAsked = true;
                // La liste donne l'UUID du compte courant, donc sa tête.
                GameAccounts.refresh();
            }
            // Un changement de compte vient d'échouer : la liste se rouvre pour le dire.
            String error = GameAccounts.error();
            if (error != null && !error.equals(shownAccountError) && !GameAccounts.switching()) open = Shortcut.ACCOUNT;
            shownAccountError = error;
        }
    }

    /**
     * Ce que l'écran dessine sans en faire un bouton, donc invisible dans sa
     * liste : le logo de l'écran-titre et le titre du menu Échap. Aux places
     * du jeu ({@code y = 30} pour le logo, {@code 40} pour le titre, sur
     * toutes les versions) : un raccourci n'y reste pas plus que sur un
     * bouton. L'éditeur les marque comme les boutons.
     */
    private static List<float[]> decor(ShortcutLayout.Screen kind) {
        List<float[]> out = new ArrayList<>();
        float cx = sw / 2f;
        if (kind == ShortcutLayout.Screen.TITLE) out.add(new float[]{ cx - 137, 28, cx + 137, 78 });
        else out.add(new float[]{ cx - 50, 37, cx + 50, 50 });
        return out;
    }

    /** Place les icônes de la rangée et décale celles du jeu ; rend {gauche, haut, droite, bas} de la rangée entière. */
    private static float[] layoutRow(ScreenButtons.Row row, List<Item> items) {
        int n = items.size();
        int step = row.size + row.gap;
        float left;
        if (row.own) {
            float total = n == 0 ? 0 : n * row.size + (n - 1) * row.gap;
            left = Math.round((sw - total) / 2f);
            for (int i = 0; i < n; i++) place(items.get(i), left + i * step, row.y, row.size);
            return new float[]{ left, row.y, left + total, row.y + row.size };
        }
        int shift = n * step / 2;
        ScreenButtons.shiftRow(row, shift);
        left = row.left - shift;
        for (int i = 0; i < n; i++) place(items.get(i), row.right + row.gap - shift + i * step, row.y, row.size);
        return new float[]{ left, row.y, row.right - shift + n * step, row.y + row.size };
    }

    private static void place(Item it, float x, float y, float size) {
        it.x = x;
        it.y = y;
        it.w = size;
        it.h = size;
    }

    // ── Contenu des raccourcis ──────────────────────────────────────────────

    /** Le texte d'un raccourci libre. */
    private static String label(Shortcut s) {
        switch (s) {
            case ACCOUNT: {
                if (GameAccounts.switching()) return Lang.tr("Changement de compte...");
                String name = ClientData.username();
                return name == null || name.isEmpty() ? Lang.tr("Compte") : name;
            }
            case MENU: return "YuyuFrame";
            case MISSIONS: {
                int[] d = dailyMissions();
                return d[1] == 0 ? Lang.tr("Missions") : Lang.tr("Missions") + " " + d[0] + "/" + d[1];
            }
            case RANKED: return rankLabel();
            case MODULES: {
                int[] m = favoriteCount();
                return m[1] == 0 ? Lang.tr("Modules") : Lang.tr("Modules") + " " + m[0] + "/" + m[1];
            }
            case FRIENDS: {
                int online = onlineFriends();
                return online < 0 ? Lang.tr("Amis") : online + " " + Lang.tr("en ligne");
            }
            default: return Lang.tr(s.label);
        }
    }

    /** Ce que dit la bulle d'une icône de la rangée. */
    private static String tooltip(Shortcut s) {
        switch (s) {
            case MENU: return Lang.tr("Menu YuyuFrame");
            case RANKED: return Lang.tr("Compétitif") + " : " + rankLabel();
            case ACCOUNT: return Lang.tr("Compte") + " : " + label(s);
            default: return label(s);
        }
    }

    private static String rankLabel() {
        if (bestRank != null && bestRank.placed) return PvpRank.name(bestRank.division);
        if (bestRank != null) return Lang.tr("Placement") + " " + Math.min(10, bestRank.duels) + "/10";
        return Lang.tr("Non classé");
    }

    private static int[] dailyMissions() {
        int done = 0, total = 0;
        for (GameMissions.Mission m : GameMissions.missions()) {
            if (!"daily".equals(m.period)) continue;
            total++;
            if (m.done()) done++;
        }
        return new int[]{ done, total };
    }

    private static List<LauncherModule> favorites() {
        List<LauncherModule> out = new ArrayList<>();
        for (LauncherModule m : ModuleRegistry.all()) if (m.favorite) out.add(m);
        return out;
    }

    private static int[] favoriteCount() {
        int on = 0;
        List<LauncherModule> fav = favorites();
        for (LauncherModule m : fav) if (m.isEnabled()) on++;
        return new int[]{ on, fav.size() };
    }

    /** Amis en ligne, {@code -1} tant que la liste n'est pas arrivée. */
    private static int onlineFriends() {
        if (!GameFriends.loaded()) return -1;
        int n = 0;
        for (GameFriends.Friend f : GameFriends.friends()) if (!"offline".equals(f.state)) n++;
        return n;
    }

    private static float freeWidth(Item it) {
        float w = 3 + 16 + 4 + textWidth(it.text) + 6;
        if (it.s == Shortcut.ACCOUNT || it.s == Shortcut.MODULES) w += 10;
        return Math.round(Math.min(w, 200));
    }

    // ── Dessin d'un raccourci ───────────────────────────────────────────────

    private static void drawItem(UiRenderer r, Item it, boolean hover) {
        drawButton(r, it.x, it.y, it.w, it.h, hover, editing && it.s == dragging);
        float iconSize = 16;
        float ix = it.row ? it.x + (it.w - iconSize) / 2f : it.x + 3;
        float iy = it.y + (it.h - iconSize) / 2f;
        drawIcon(r, it.s, ix, iy, iconSize);
        if (it.row) return;
        float tx = ix + iconSize + 4;
        float maxW = it.x + it.w - tx - 5 - ((it.s == Shortcut.ACCOUNT || it.s == Shortcut.MODULES) ? 10 : 0);
        drawLabel(r, fitText(it.text, maxW), tx, it.y, it.h, textColor(hover));
        if (it.s == Shortcut.ACCOUNT || it.s == Shortcut.MODULES) {
            drawChevron(r, it.x + it.w - 12, it.y + it.h / 2f, it.s == open);
        }
    }

    private static void drawIcon(UiRenderer r, Shortcut s, float x, float y, float size) {
        switch (s) {
            case ACCOUNT: {
                GameAccounts.Account current = currentAccount();
                drawHead(r, current == null ? null : current.uuid, current != null && current.offline, x, y, size);
                return;
            }
            case MENU: image(r, "yf-logo", logo(), x, y, size, size, 1f); return;
            case MISSIONS: image(r, McTexture.key("experience_bottle"), McTexture.item("experience_bottle", "experience_bottle"), x, y, size, size, 1f); return;
            case RANKED: {
                float[] f = fb(x, y, size, size);
                if (bestRank != null && bestRank.placed) PvpRank.badge(r, bestRank.division, f[0], f[1], size * gs, 0f, 1f, vw, vh);
                else if (anyRankedDuel) PvpRank.placementBadge(r, f[0], f[1], size * gs, 1f, vw, vh);
                else image(r, McTexture.key("mace"), HomeIcons.mace(), x, y, size, size, 1f);
                return;
            }
            case MODULES: image(r, McTexture.key("redstone_torch"), McTexture.item("redstone_torch", "redstone_torch_on"), x, y, size, size, 1f); return;
            case FRIENDS: image(r, McTexture.key("face-steve"), McTexture.defaultFace(), x, y, size, size, 1f); return;
            default:
        }
    }

    /** La tête d'un compte : le service d'avatars, ou le skin gardé par le launcher pour un compte hors ligne, ou Steve. */
    private static void drawHead(UiRenderer r, String uuid, boolean offline, float x, float y, float size) {
        String key = null;
        BufferedImage head = null;
        if (uuid != null && !uuid.isEmpty()) {
            if (offline) {
                AccountFaces.Face face = AccountFaces.face(uuid);
                if (face != null) {
                    key = face.key;
                    head = face.image;
                }
            } else {
                key = "https://mc-heads.net/avatar/" + uuid.replace("-", "") + "/64";
                head = UiRemoteImage.get(key);
            }
        }
        if (head == null) {
            key = McTexture.key("face-steve");
            head = McTexture.defaultFace();
        }
        image(r, key, head, x, y, size, size, 1f);
    }

    private static GameAccounts.Account currentAccount() {
        for (GameAccounts.Account a : GameAccounts.accounts()) if (GameAccounts.isCurrent(a)) return a;
        return null;
    }

    private static BufferedImage logo;

    /** Le logo YuyuFrame en pixels (le même que celui des pseudos), agrandi net. */
    private static BufferedImage logo() {
        if (logo != null) return logo;
        int k = 16;
        BufferedImage img = new BufferedImage(14 * k, 14 * k, BufferedImage.TYPE_INT_ARGB);
        PlayerStyle.pixelRects((from, rowIndex, to, rgb) -> {
            for (int y = rowIndex * k; y < (rowIndex + 1) * k && y < img.getHeight(); y++) {
                for (int x = from * k; x < to * k && x < img.getWidth(); x++) img.setRGB(x, y, 0xFF000000 | rgb);
            }
        });
        logo = img;
        return img;
    }

    private static final String CHEVRON_DOWN = LauncherModule.icons8("expand-arrow");
    private static final String CHEVRON_UP = LauncherModule.icons8("collapse-arrow");

    private static void drawChevron(UiRenderer r, float x, float cy, boolean up) {
        String url = up ? CHEVRON_UP : CHEVRON_DOWN;
        BufferedImage arrow = UiRemoteImage.get(url);
        if (arrow != null) image(r, url, arrow, x, cy - 4, 8, 8, 0.9f);
    }

    // ── Les deux styles ─────────────────────────────────────────────────────

    private static final UiColor MC_TEXT = new UiColor(255, 255, 255, 255);
    private static final UiColor MC_TEXT_HOVER = new UiColor(255, 255, 160, 255);
    private static final UiColor MC_SHADOW = new UiColor(63, 63, 63, 255);
    private static final UiColor MC_MUTED = new UiColor(160, 160, 160, 255);
    private static final UiColor MC_GREEN = new UiColor(85, 255, 85, 255);
    private static final UiColor MC_RED = new UiColor(255, 85, 85, 255);
    private static final UiColor TOOLTIP_BG = new UiColor(16, 0, 16, 240);
    private static final UiColor TOOLTIP_BORDER = new UiColor(80, 0, 255, 120);

    private static boolean minecraft() {
        return style == ShortcutLayout.Style.MINECRAFT;
    }

    private static UiColor textColor(boolean hover) {
        if (minecraft()) return hover ? MC_TEXT_HOVER : MC_TEXT;
        return UiTheme.TEXT_PRIMARY;
    }

    /** Un bouton : la texture du jeu, ou une plaque de verre. */
    private static void drawButton(UiRenderer r, float x, float y, float w, float h, boolean hover, boolean lifted) {
        float[] f = fb(x, y, w, h);
        if (minecraft()) {
            // La texture du pack actif, découpée comme le jeu la découpe.
            if (!McTexture.drawButton(r, f[0], f[1], f[2] - f[0], f[3] - f[1], gs, hover, 1f, vw, vh)) {
                r.drawRoundedRect(f[0], f[1], f[2], f[3], 0f, hover ? new UiColor(140, 140, 160, 255) : new UiColor(110, 110, 110, 255), vw, vh);
            }
            if (lifted) outline(r, x, y, w, h, MC_TEXT);
            return;
        }
        float radius = 3 * gs;
        r.drawShadow(f[0], f[1], f[2], f[3], radius, 6 * gs, 0f, new UiColor(0, 0, 0, 100), vw, vh);
        UiColor bg = UiColor.lerp(UiTheme.PANEL_BG, UiTheme.ACCENT, hover ? 0.45f : 0f).withAlpha(1f);
        UiColor tint = UiColor.lerp(UiTheme.GLASS_TINT, UiTheme.ACCENT, hover ? 0.5f : 0f);
        r.drawGlassPanel(f[0], f[1], f[2], f[3], radius, tint, UiTheme.GLASS_STRENGTH_FIELD, bg, vw, vh);
        if (lifted) outline(r, x, y, w, h, UiTheme.accentLight());
    }

    /** Une plaque de liste ou de panneau : l'infobulle du jeu, ou du verre. */
    private static void drawPlate(UiRenderer r, float x, float y, float w, float h) {
        float[] f = fb(x, y, w, h);
        if (minecraft()) {
            r.drawRoundedRect(f[0], f[1], f[2], f[3], 0f, TOOLTIP_BG, vw, vh);
            outline(r, x + 1, y + 1, w - 2, h - 2, TOOLTIP_BORDER);
            return;
        }
        r.drawShadow(f[0], f[1], f[2], f[3], UiTheme.RADIUS_MD, 10 * gs, 0f, new UiColor(0, 0, 0, 110), vw, vh);
        r.drawGlassPanel(f[0], f[1], f[2], f[3], UiTheme.RADIUS_MD, UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL,
            UiTheme.PANEL_BG.withAlpha(1f), vw, vh);
    }

    private static UiFont font() {
        return minecraft() ? MinecraftFont.accent(false) : UiFont.REGULAR;
    }

    /** Échelle du texte : un pixel de la police du jeu vaut un pixel de GUI ({@code MinecraftFont} : 6 pixels d'atlas par pixel). */
    private static float textScale() {
        return minecraft() ? gs / (6f * UiFont.SIZE_CORRECTION) : 0.19f * gs;
    }

    private static float textWidth(String text) {
        return UiText.width(font(), text, textScale()) / gs;
    }

    private static String fitText(String text, float maxW) {
        return UiText.fit(font(), text, Math.max(0f, maxW) * gs, textScale());
    }

    /** Un texte centré verticalement dans la bande {@code [y, y + h]}. */
    private static void drawLabel(UiRenderer r, String text, float x, float y, float h, UiColor color) {
        UiFont font = font();
        float scale = textScale();
        if (minecraft()) {
            // Comme le jeu : le haut du texte à (h - 8) / 2, la ligne de base 7 pixels plus bas.
            float baseline = vh - (y + (h - 8) / 2f + 7) * gs;
            r.drawTextShadowed(font, text, x * gs, baseline, color, MC_SHADOW, gs, -gs, scale, vw, vh);
            return;
        }
        float[] f = fb(x, y, 1, h);
        r.drawText(font, text, x * gs, UiText.centeredBaseline(font, f[1], h * gs, scale), color, scale, vw, vh);
    }

    private static void drawTooltip(UiRenderer r, Item it) {
        String text = tooltip(it.s);
        float w = textWidth(text) + 8, h = 16;
        float x = clamp(mx + 8, 2, sw - w - 2);
        float y = clamp(my - h - 4, 2, sh - h - 2);
        drawPlate(r, x, y, w, h);
        drawLabel(r, text, x + 4, y, h, minecraft() ? MC_TEXT : UiTheme.TEXT_PRIMARY);
    }

    // ── Listes dépliées : comptes, modules favoris ──────────────────────────

    private static final class PopRow {
        String key;
        BufferedImage icon;
        String accountUuid;
        boolean accountOffline;
        String text;
        String right;
        UiColor rightColor;
        UiColor textColor;
        boolean selected;
        Runnable action;
    }

    private static final class Popover {
        float x, y, w, h;
        final float rowH = 20;
        final List<PopRow> rows = new ArrayList<>();
    }

    private static Popover popover(Shortcut which, List<Item> items) {
        Item anchor = null;
        for (Item it : items) if (it.s == which) anchor = it;
        if (anchor == null) return null;
        Popover p = new Popover();
        if (which == Shortcut.ACCOUNT) fillAccounts(p);
        else if (which == Shortcut.MODULES) fillModules(p);
        if (p.rows.isEmpty()) return null;
        float w = anchor.row ? 0 : anchor.w;
        for (PopRow row : p.rows) {
            float rw = 4 + 16 + 4 + textWidth(row.text) + (row.right == null ? 0 : 8 + textWidth(row.right)) + 6;
            w = Math.max(w, rw);
        }
        p.w = Math.round(Math.min(Math.max(w, 120), sw - 8));
        p.h = p.rows.size() * p.rowH + 4;
        p.x = clamp(anchor.x, 4, sw - p.w - 4);
        float below = anchor.y + anchor.h + 2;
        p.y = below + p.h <= sh - 2 ? below : Math.max(2, anchor.y - 2 - p.h);
        return p;
    }

    private static void fillAccounts(Popover p) {
        boolean switching = GameAccounts.switching();
        for (GameAccounts.Account account : GameAccounts.accounts()) {
            PopRow row = new PopRow();
            row.accountUuid = account.uuid;
            row.accountOffline = account.offline;
            row.text = account.name;
            boolean current = GameAccounts.isCurrent(account);
            row.selected = current;
            // « hors ligne » : ce compte ne rejoint pas les serveurs qui vérifient les comptes.
            if (account.offline) {
                row.right = Lang.tr("hors ligne");
                row.rightColor = minecraft() ? MC_MUTED : UiTheme.TEXT_MUTED;
            }
            if (!current && !switching) {
                row.action = () -> {
                    open = null;
                    GameAccounts.switchTo(account);
                };
            }
            p.rows.add(row);
        }
        String message = GameAccounts.error();
        boolean error = message != null;
        if (message == null && GameAccounts.accounts().isEmpty()) {
            message = GameAccounts.loading() ? "Chargement des comptes..." : "Aucun compte dans le launcher";
        }
        if (message != null) {
            PopRow row = new PopRow();
            row.text = Lang.tr(message);
            row.textColor = error ? (minecraft() ? MC_RED : UiTheme.DANGER) : (minecraft() ? MC_MUTED : UiTheme.TEXT_MUTED);
            p.rows.add(row);
        }
    }

    private static void fillModules(Popover p) {
        List<LauncherModule> fav = favorites();
        for (LauncherModule m : fav) {
            PopRow row = new PopRow();
            row.key = m.iconUrl;
            row.icon = m.iconUrl == null ? null : UiRemoteImage.get(m.iconUrl);
            row.text = m.name;
            boolean on = m.isEnabled();
            row.right = on ? Lang.tr("Activé") : Lang.tr("Désactivé");
            row.rightColor = on ? (minecraft() ? MC_GREEN : UiTheme.accentLight()) : (minecraft() ? MC_MUTED : UiTheme.TEXT_MUTED);
            row.action = () -> {
                m.setEnabled(!m.isEnabled());
                HudConfigStore.save();
            };
            p.rows.add(row);
        }
        if (fav.isEmpty()) {
            PopRow row = new PopRow();
            row.text = Lang.tr("Aucun module favori");
            row.textColor = minecraft() ? MC_MUTED : UiTheme.TEXT_MUTED;
            p.rows.add(row);
        }
    }

    private static void drawPopover(UiRenderer r, Popover p) {
        drawPlate(r, p.x, p.y, p.w, p.h);
        for (int i = 0; i < p.rows.size(); i++) {
            PopRow row = p.rows.get(i);
            float ry = p.y + 2 + i * p.rowH;
            boolean hover = row.action != null && mx >= p.x && mx < p.x + p.w && my >= ry && my < ry + p.rowH;
            if (row.selected || hover) {
                float[] f = fb(p.x + 2, ry, p.w - 4, p.rowH);
                UiColor fill = minecraft()
                    ? new UiColor(255, 255, 255, row.selected ? 40 : 24)
                    : (row.selected ? UiTheme.ACCENT_DIM : UiTheme.CARD_HOVER);
                r.drawRoundedRect(f[0], f[1], f[2], f[3], minecraft() ? 0f : UiTheme.RADIUS_SM, fill, vw, vh);
            }
            float tx = p.x + 6;
            if (row.accountUuid != null) {
                drawHead(r, row.accountUuid, row.accountOffline, tx, ry + 2, 16);
                tx += 20;
            } else if (row.icon != null) {
                image(r, row.key, row.icon, tx + 2, ry + 4, 12, 12, 1f);
                tx += 20;
            }
            float rightW = row.right == null ? 0 : textWidth(row.right);
            UiColor color = row.textColor != null ? row.textColor : textColor(hover);
            drawLabel(r, fitText(row.text, p.x + p.w - tx - rightW - 12), tx, ry, p.rowH, color);
            if (row.right != null) drawLabel(r, row.right, p.x + p.w - rightW - 6, ry, p.rowH, row.rightColor);
        }
    }

    // ── L'éditeur ───────────────────────────────────────────────────────────

    private static final float ROW_H = 22, HEAD_H = 18, MODE_W = 52;
    /** Largeur du panneau : la place libre à côté des boutons du milieu, entre 140 et 164. Choisie à l'ouverture. */
    private static float panelW = 164;

    private static float panelHeight() {
        return 6 + HEAD_H + Shortcut.values().length * ROW_H + 6 + 2 * ROW_H + 4;
    }

    /** Le panneau de l'éditeur : {gauche, haut, droite, bas}. */
    private static float[] panelRect(List<ScreenButtons.Button> buttons) {
        float h = Math.min(panelHeight(), sh - 4);
        float x = panelRight ? sw - panelW - 4 : 4;
        float y = Math.max(2, (sh - h) / 2f);
        if (!Float.isNaN(panelX)) {
            x = clamp(panelX, 0, sw - panelW);
            y = clamp(panelY, 0, sh - h);
        }
        return new float[]{ x, y, x + panelW, y + h };
    }

    /** Position du panneau déplacé par son titre, le temps de l'édition ; {@code NaN} = sa place par défaut. */
    private static float panelX = Float.NaN, panelY = Float.NaN;
    private static boolean panelDragging;

    /**
     * Le côté où le panneau gêne le moins (celui qui couvre le moins de
     * boutons), et sa largeur : la place libre de ce côté, sans descendre sous
     * 140 pixels. Choisis à l'ouverture de l'éditeur.
     */
    private static void choosePanelSide(List<ScreenButtons.Button> buttons) {
        float h = Math.min(panelHeight(), sh - 4);
        float y = Math.max(2, (sh - h) / 2f);
        float leftRoom = sw, rightRoom = sw;
        for (ScreenButtons.Button b : buttons) {
            if (b.y + b.h < y || b.y > y + h) continue;
            leftRoom = Math.min(leftRoom, b.x);
            rightRoom = Math.min(rightRoom, sw - b.x - b.w);
        }
        panelRight = rightRoom >= leftRoom;
        panelW = clamp((panelRight ? rightRoom : leftRoom) - 8, 140, 164);
        // Chaque ouverture repart de cette place : celle d'un autre écran n'a pas de sens ici.
        panelX = Float.NaN;
        panelY = Float.NaN;
    }

    private static void drawEditBackdrop(UiRenderer r, List<ScreenButtons.Button> buttons, List<float[]> decor,
                                         ScreenButtons.Row row, float[] rowSpan) {
        r.drawRoundedRect(0, 0, vw, vh, 0f, new UiColor(0, 0, 0, 110), vw, vh);
        // Les boutons du jeu et des autres mods, le logo, le titre : la place est prise.
        UiColor fill = new UiColor(255, 80, 80, 40), line = new UiColor(255, 110, 110, 150);
        List<float[]> taken = new ArrayList<>(decor);
        for (ScreenButtons.Button b : buttons) taken.add(new float[]{ b.x, b.y, b.x + b.w, b.y + b.h });
        for (float[] t : taken) {
            float[] f = fb(t[0], t[1], t[2] - t[0], t[3] - t[1]);
            r.drawRoundedRect(f[0], f[1], f[2], f[3], 0f, fill, vw, vh);
            outline(r, t[0], t[1], t[2] - t[0], t[3] - t[1], line);
        }
        // La rangée : là où un raccourci lâché devient une icône.
        float[] z = rowZone(rowSpan, row);
        outline(r, z[0], z[1], z[2] - z[0], z[3] - z[1], new UiColor(255, 255, 255, dragging != null ? 200 : 90));
    }

    /** La zone de la rangée, un peu élargie : y lâcher un raccourci l'y range. */
    private static float[] rowZone(float[] span, ScreenButtons.Row row) {
        float left = span[0], right = Math.max(span[2], span[0] + row.size);
        return new float[]{ left - 4, span[1] - 4, right + 4, span[3] + 4 };
    }

    private static void drawPanel(UiRenderer r, float[] panel, ShortcutLayout.Screen kind) {
        float x = panel[0], y = panel[1], w = panel[2] - panel[0];
        drawPlate(r, x, y, w, panel[3] - panel[1]);
        drawLabel(r, Lang.tr("Raccourcis") + " - " + Lang.tr(kind.label), x + 6, y + 4, HEAD_H, textColor(false));
        float ry = y + 6 + HEAD_H;
        for (Shortcut s : Shortcut.values()) {
            drawIcon(r, s, x + 6, ry + 3, 16);
            drawLabel(r, fitText(Lang.tr(s.label), w - 26 - MODE_W - 10), x + 26, ry, ROW_H, textColor(false));
            float[] b = modeButton(panel, ry);
            if (!s.allowedOn(kind)) {
                // Le compte : seulement sur l'écran-titre, que les joueurs appellent l'accueil.
                String only = fitText(Lang.tr("Accueil"), b[2]);
                drawLabel(r, only, b[0] + (b[2] - textWidth(only)) / 2f, ry, ROW_H, minecraft() ? MC_MUTED : UiTheme.TEXT_MUTED);
            } else {
                boolean hover = inside(b);
                drawButton(r, b[0], b[1], b[2], b[3], hover, false);
                String mode = Lang.tr(ShortcutLayout.place(kind, s).mode.label);
                drawLabel(r, mode, b[0] + (b[2] - textWidth(mode)) / 2f, b[1], b[3], textColor(hover));
            }
            ry += ROW_H;
        }
        for (int i = 0; i < 3; i++) {
            float[] b = footerButton(panel, i);
            boolean hover = inside(b);
            drawButton(r, b[0], b[1], b[2], b[3], hover, false);
            String text = footerText(i);
            drawLabel(r, fitText(text, b[2] - 6), b[0] + Math.max(3, (b[2] - textWidth(text)) / 2f), b[1], b[3], textColor(hover));
        }
    }

    /** {x, y, w, h} du bouton de mode d'une ligne du panneau. */
    private static float[] modeButton(float[] panel, float rowY) {
        return new float[]{ panel[2] - 6 - MODE_W, rowY + 1, MODE_W, 20 };
    }

    /** 0 : style (toute la largeur) ; 1 : réinitialiser ; 2 : terminé. */
    private static float[] footerButton(float[] panel, int i) {
        float x = panel[0] + 6, w = panel[2] - panel[0] - 12;
        float top = panel[1] + 6 + HEAD_H + Shortcut.values().length * ROW_H + 6;
        if (i == 0) return new float[]{ x, top, w, 20 };
        float half = (w - 4) / 2f;
        return new float[]{ i == 1 ? x : x + half + 4, top + ROW_H, half, 20 };
    }

    private static String footerText(int i) {
        if (i == 0) return Lang.tr("Style") + " : " + Lang.tr(ShortcutLayout.style().label);
        return i == 1 ? Lang.tr("Par défaut") : Lang.tr("Terminé");
    }

    // ── Souris ──────────────────────────────────────────────────────────────

    private static void handleMouse(UiInputPoller in, ShortcutLayout.Screen kind, Object screen, List<Item> items, Item hovered,
                                    Popover pop, float[] panel, List<ScreenButtons.Button> buttons, float[] rowSpan) {
        if (panelDragging) {
            if (!in.leftDown) {
                panelDragging = false;
            } else {
                panelX = mx - dragOffX;
                panelY = my - dragOffY;
            }
            return;
        }
        // Fin d'un glisser : le raccourci se range.
        if (dragging != null && !in.leftDown) {
            drop(kind, items, rowSpan, ScreenButtons.findRow(buttons, sw, sh));
            return;
        }
        if (dragging != null) {
            dragX = mx - dragOffX;
            dragY = my - dragOffY;
            return;
        }

        if (in.rightClicked) {
            if (editing) {
                leaveEditor();
            } else if (hovered != null || (pop == null && buttonAt(buttons) == null)) {
                editing = true;
                open = null;
                choosePanelSide(buttons);
            }
            return;
        }
        if (!in.leftClicked) return;

        if (editing) {
            if (panel != null && mx >= panel[0] && mx < panel[2] && my >= panel[1] && my < panel[3]) {
                if (my < panel[1] + 6 + HEAD_H) {
                    // Le titre : on emporte le panneau.
                    panelDragging = true;
                    dragOffX = mx - panel[0];
                    dragOffY = my - panel[1];
                    return;
                }
                clickPanel(panel, kind);
                return;
            }
            if (hovered != null) {
                dragging = hovered.s;
                dragOffX = mx - hovered.x;
                dragOffY = my - hovered.y;
                dragX = hovered.x;
                dragY = hovered.y;
            }
            return;
        }

        if (pop != null) {
            if (mx >= pop.x && mx < pop.x + pop.w && my >= pop.y && my < pop.y + pop.h) {
                int i = (int) ((my - pop.y - 2) / pop.rowH);
                if (i >= 0 && i < pop.rows.size() && pop.rows.get(i).action != null) pop.rows.get(i).action.run();
                return;
            }
            Shortcut was = open;
            open = null;
            if (hovered == null || hovered.s == was) return;
        }
        if (hovered != null) activate(hovered.s, screen);
    }

    private static void activate(Shortcut s, Object screen) {
        switch (s) {
            case ACCOUNT:
                if (GameAccounts.switching()) return;
                open = open == Shortcut.ACCOUNT ? null : Shortcut.ACCOUNT;
                // À chaque ouverture : un compte a pu être ajouté dans le launcher entre-temps.
                if (open != null) GameAccounts.refresh();
                return;
            case MODULES:
                open = open == Shortcut.MODULES ? null : Shortcut.MODULES;
                return;
            case MENU:
                ClientData.setScreen(new UiMainMenuScreen(screen));
                return;
            case MISSIONS:
                ClientData.setScreen(UiMainMenuScreen.openOn(screen, "missions"));
                return;
            case RANKED:
                ClientData.setScreen(UiMainMenuScreen.openOn(screen, "competitive"));
                return;
            case FRIENDS:
                ClientData.setScreen(UiMainMenuScreen.openOn(screen, "friends"));
                return;
            default:
        }
    }

    private static void clickPanel(float[] panel, ShortcutLayout.Screen kind) {
        float ry = panel[1] + 6 + HEAD_H;
        for (Shortcut s : Shortcut.values()) {
            float[] b = modeButton(panel, ry);
            if (s.allowedOn(kind) && inside(b)) {
                ShortcutLayout.Place place = ShortcutLayout.place(kind, s);
                place.mode = place.mode.next();
                ShortcutLayout.save(kind, s);
                return;
            }
            ry += ROW_H;
        }
        if (inside(footerButton(panel, 0))) {
            ShortcutLayout.setStyle(ShortcutLayout.style() == ShortcutLayout.Style.MINECRAFT
                ? ShortcutLayout.Style.GLASS : ShortcutLayout.Style.MINECRAFT);
        } else if (inside(footerButton(panel, 1))) {
            ShortcutLayout.reset(kind);
        } else if (inside(footerButton(panel, 2))) {
            leaveEditor();
        }
    }

    private static void leaveEditor() {
        editing = false;
        dragging = null;
        HudConfigStore.flush();
    }

    /** Lâché sur la rangée : il y entre. Ailleurs : il reste où il est, poussé hors des boutons. */
    private static void drop(ShortcutLayout.Screen kind, List<Item> items, float[] rowSpan, ScreenButtons.Row row) {
        Shortcut s = dragging;
        dragging = null;
        Item it = null;
        for (Item i : items) if (i.s == s) it = i;
        if (it == null) return;
        ShortcutLayout.Place place = ShortcutLayout.place(kind, s);
        float[] zone = rowZone(rowSpan, row);
        // C'est la souris qui compte, pas le centre du bouton : une icône tirée
        // de la rangée s'élargit (elle prend son texte), et un simple clic
        // dessus doit la laisser où elle est.
        if (mx >= zone[0] && mx <= zone[2] && my >= zone[1] && my <= zone[3]) {
            place.mode = ShortcutLayout.Mode.ROW;
        } else {
            place.moveTo(it.x, it.y, it.w, it.h, sw, sh);
        }
        ShortcutLayout.save(kind, s);
    }

    private static ScreenButtons.Button buttonAt(List<ScreenButtons.Button> buttons) {
        for (ScreenButtons.Button b : buttons) {
            if (mx >= b.x && mx < b.x + b.w && my >= b.y && my < b.y + b.h) return b;
        }
        return null;
    }

    // ── Place libre ─────────────────────────────────────────────────────────

    /**
     * La position libre la plus proche de {@code (x, y)} : la position elle-même
     * si rien ne la gêne, sinon contre le bord d'un obstacle, sinon en
     * tournant autour par pas de 4 pixels. Toujours dans l'écran.
     */
    private static float[] resolve(float x, float y, float w, float h, List<float[]> obstacles) {
        x = clamp(x, 2, sw - w - 2);
        y = clamp(y, 2, sh - h - 2);
        if (free(x, y, w, h, obstacles)) return new float[]{ x, y };
        float bestX = x, bestY = y, best = Float.MAX_VALUE;
        for (float[] o : obstacles) {
            float[][] candidates = {
                { o[2] + 2, y }, { o[0] - w - 2, y }, { x, o[3] + 2 }, { x, o[1] - h - 2 },
                { o[2] + 2, o[1] }, { o[0] - w - 2, o[1] }, { o[0], o[3] + 2 }, { o[0], o[1] - h - 2 },
            };
            for (float[] c : candidates) {
                float cx = clamp(c[0], 2, sw - w - 2), cy = clamp(c[1], 2, sh - h - 2);
                float d = (cx - x) * (cx - x) + (cy - y) * (cy - y);
                if (d < best && free(cx, cy, w, h, obstacles)) {
                    best = d;
                    bestX = cx;
                    bestY = cy;
                }
            }
        }
        if (best < Float.MAX_VALUE) return new float[]{ bestX, bestY };
        for (int ring = 1; ring < 80; ring++) {
            float d = ring * 4;
            for (int k = 0; k < 8 * ring; k++) {
                double a = Math.PI * 2 * k / (8 * ring);
                float cx = clamp(x + (float) Math.cos(a) * d, 2, sw - w - 2);
                float cy = clamp(y + (float) Math.sin(a) * d, 2, sh - h - 2);
                if (free(cx, cy, w, h, obstacles)) return new float[]{ cx, cy };
            }
        }
        return new float[]{ x, y };
    }

    private static boolean free(float x, float y, float w, float h, List<float[]> obstacles) {
        for (float[] o : obstacles) {
            if (x < o[2] + 1 && x + w > o[0] - 1 && y < o[3] + 1 && y + h > o[1] - 1) return false;
        }
        return true;
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    /** Rectangle de GUI (y vers le bas) en pixels de fenêtre (y vers le haut) : {x1, y1, x2, y2}. */
    private static float[] fb(float x, float y, float w, float h) {
        return new float[]{ x * gs, vh - (y + h) * gs, (x + w) * gs, vh - y * gs };
    }

    private static void image(UiRenderer r, String key, BufferedImage img, float x, float y, float w, float h, float alpha) {
        if (img == null || key == null) return;
        float[] f = fb(x, y, w, h);
        r.drawIcon(key, img, f[0], f[1], f[2] - f[0], f[3] - f[1], alpha, vw, vh);
    }

    /** Un cadre d'un pixel de GUI. */
    private static void outline(UiRenderer r, float x, float y, float w, float h, UiColor c) {
        float t = 1;
        fill(r, x, y, w, t, c);
        fill(r, x, y + h - t, w, t, c);
        fill(r, x, y + t, t, h - 2 * t, c);
        fill(r, x + w - t, y + t, t, h - 2 * t, c);
    }

    private static void fill(UiRenderer r, float x, float y, float w, float h, UiColor c) {
        float[] f = fb(x, y, w, h);
        r.drawRoundedRect(f[0], f[1], f[2], f[3], 0f, c, vw, vh);
    }

    private static boolean inside(float[] b) {
        return mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3];
    }

    private static float clamp(float v, float lo, float hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
