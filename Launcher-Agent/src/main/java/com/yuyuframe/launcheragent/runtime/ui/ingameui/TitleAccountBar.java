package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.account.GameAccounts;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * La barre de compte de l'écran-titre de Minecraft, en haut à gauche : la
 * tête et le pseudo du compte avec lequel on joue, la liste des autres comptes
 * du launcher quand on clique dessus, et à côté un bouton qui ouvre le menu
 * YuyuFrame — seul accès à ce menu hors d'un monde.
 *
 * <p>Elle est posée ICI et pas dans le menu YuyuFrame : changer de compte n'a
 * de sens que hors d'un monde (une fois connecté, l'identité est liée à la
 * connexion), et l'écran-titre est justement l'endroit où l'on n'y est pas.
 *
 * <p>Ce n'est pas un écran : elle est dessinée par-dessus celui du jeu, à
 * chaque image ({@code AgentBridge.renderScreenOverlay}), et lit la souris
 * elle-même. L'écran-titre est reconnu par sa classe, relevée quand il
 * s'initialise ({@link #titleScreenSeen}) — son nom change d'une version à
 * l'autre, sa classe se compare sans le connaître.
 *
 * <p>Repère : pixels de la fenêtre, y vers le HAUT. Le panneau des comptes se
 * déplie donc vers les y décroissants.
 */
public final class TitleAccountBar {

    private TitleAccountBar() {
    }

    private static volatile Class<?> titleClass;

    /** L'écran-titre vient de s'initialiser : c'est l'écran courant, on retient sa classe. */
    public static void titleScreenSeen() {
        Object screen = ClientData.screenObject();
        if (screen != null && !(screen instanceof UiDrawable)) {
            titleClass = screen.getClass();
        } else if (titleClass == null) {
            // Tout premier écran-titre : l'accès à l'écran courant peut ne pas être encore lié.
            // Le premier écran du jeu dessiné ensuite sera celui-là.
            pending = true;
        }
    }

    private static volatile boolean pending;

    private static boolean expanded;
    /** La liste a-t-elle déjà été demandée une fois ? Elle donne l'UUID du compte courant, donc sa tête. */
    private static boolean listAsked;
    /** La dernière erreur déjà montrée : une nouvelle rouvre le panneau. */
    private static String shownError;
    private static final UiAnimatedFloat cardHover = new UiAnimatedFloat(0f, 16f);
    private static final UiAnimatedFloat gearHover = new UiAnimatedFloat(0f, 16f);
    private static boolean errorLogged;

    /** Appelé à chaque image où un écran du jeu est ouvert. Ne dessine que sur l'écran-titre. */
    public static void render(UiRenderer renderer, UiInputPoller input, Object screen) {
        if (pending && screen != null) {
            pending = false;
            if (titleClass == null) {
                titleClass = screen.getClass();
            }
        }
        if (screen == null || screen.getClass() != titleClass) {
            expanded = false;
            return;
        }
        try {
            draw(renderer, input, screen);
        } catch (Throwable error) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[TitleAccountBar] dessin impossible", error);
            }
        }
    }

    private static void draw(UiRenderer renderer, UiInputPoller input, Object screen) {
        int vw = input.fbWidth, vh = input.fbHeight;
        if (vw <= 0 || vh <= 0) {
            return;
        }
        // La barre suit la fenêtre : pleine taille à 1280×720, plus petite
        // dans une petite fenêtre (où elle mordait sur le logo du jeu). Au-delà
        // elle ne grandit presque plus — un quart de ce que gagne la fenêtre,
        // 15 % au plus : en plein écran elle devenait énorme (retour
        // utilisateur, 2026-10-10).
        float ratio = Math.min(vw / 1280f, vh / 720f);
        fit = ratio <= 1f ? Math.max(0.6f, ratio) : Math.min(1.15f, 1f + (ratio - 1f) * 0.25f);
        float margin = px(14f);
        float cardH = px(54f);
        float gap = px(8f);
        boolean accounts = GameAccounts.available();
        float cardW = accounts ? px(232f) : 0f;
        float cardX = margin, cardY = vh - margin - cardH;
        float gearX = accounts ? cardX + cardW + gap : margin;
        double mx = input.mouseX, my = input.mouseY;

        if (accounts) {
            if (!listAsked) {
                listAsked = true;
                GameAccounts.refresh();
            }
            boolean switching = GameAccounts.switching();
            if (switching) {
                expanded = false;
            } else {
                // Un changement vient d'échouer : le panneau se rouvre pour le dire, il ne reste pas muet.
                String error = GameAccounts.error();
                if (error != null && !error.equals(shownError)) {
                    expanded = true;
                }
                shownError = error;
            }

            boolean overCard = inside(mx, my, cardX, cardY, cardW, cardH);
            cardHover.setTarget(overCard ? 1f : 0f);
            drawCard(renderer, cardX, cardY, cardW, cardH, switching, vw, vh);

            float panelTop = cardY - px(6f);
            float rowH = px(40f);
            List<String> message = expanded ? messageLines(renderer, cardW) : new ArrayList<>();
            float lineH = px(12f);
            float messageH = message.isEmpty() ? 0f : message.size() * lineH + px(12f);
            float panelBottom = panelTop - GameAccounts.accounts().size() * rowH - messageH;
            if (expanded) {
                drawPanel(renderer, cardX, panelBottom, cardW, panelTop, rowH, message, lineH, mx, my, vw, vh);
            }

            if (input.leftClicked && !switching) {
                if (overCard) {
                    expanded = !expanded;
                    if (expanded) {
                        // À chaque ouverture : un compte a pu être ajouté dans le launcher entre-temps.
                        GameAccounts.refresh();
                    }
                } else if (expanded && inside(mx, my, cardX, panelBottom, cardW, panelTop - panelBottom)) {
                    List<GameAccounts.Account> list = GameAccounts.accounts();
                    int row = (int) ((panelTop - my) / rowH);
                    if (row >= 0 && row < list.size()) {
                        expanded = false;
                        if (!GameAccounts.isCurrent(list.get(row))) {
                            GameAccounts.switchTo(list.get(row));
                        }
                    }
                } else {
                    expanded = false;
                }
            }
        }

        boolean overGear = inside(mx, my, gearX, cardY, cardH, cardH);
        gearHover.setTarget(overGear ? 1f : 0f);
        drawGear(renderer, gearX, cardY, cardH, vw, vh);
        if (overGear && input.leftClicked) {
            expanded = false;
            // L'écran-titre en « écran précédent » : fermer le menu y ramène.
            ClientData.setScreen(new UiMainMenuScreen(screen));
        }
    }

    /** Rapport de la fenêtre à la taille de référence, recalculé à chaque image. */
    private static float fit = 1f;

    /** Une longueur de la barre : le réglage « Taille de l'interface », puis la taille de la fenêtre. */
    private static float px(float value) {
        return UiTheme.scaled(value) * fit;
    }

    private static boolean inside(double mx, double my, float x, float y, float w, float h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    // ── La carte du compte courant ───────────────────────────────────────────

    private static void drawCard(UiRenderer renderer, float x, float y, float w, float h, boolean switching, int vw, int vh) {
        float hover = cardHover.get();
        renderer.drawShadow(x, y, x + w, y + h, UiTheme.RADIUS_MD, px(12f), 0f, new UiColor(0, 0, 0, 110), vw, vh);
        UiColor bg = UiColor.lerp(UiTheme.PANEL_BG.withAlpha(0.86f), UiTheme.PANEL_BG_ALT.withAlpha(0.94f), hover);
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_MD, expanded ? UiTheme.PANEL_BG_ALT.withAlpha(0.96f) : bg, vw, vh);
        // Liseré d'accent à gauche, comme l'entrée active de la navigation du menu.
        float edge = px(3f);
        renderer.drawGradientRect(x, y + px(10f), x + edge, y + h - px(10f), edge / 2f,
            UiTheme.ACCENT, UiTheme.accentLight(), vw, vh);

        float pad = px(9f);
        float avatar = h - pad * 2f;
        GameAccounts.Account current = currentAccount();
        String name = ClientData.username();
        drawAvatar(renderer, current == null ? null : current.uuid, name, x + pad + edge, y + pad, avatar, vw, vh);

        float textX = x + pad + edge + avatar + px(10f);
        // Un vrai chevron, pas la lettre « v » : même famille d'icônes que l'engrenage d'à côté.
        float arrowW = px(13f);
        float textMax = x + w - textX - arrowW - pad * 2f;
        float nameScale = px(0.5f);
        String title = switching ? Lang.tr("Changement de compte...") : name.isEmpty() ? Lang.tr("Compte") : name;
        renderer.drawText(UiFont.BOLD, fit(renderer, UiFont.BOLD, title, textMax, nameScale), textX, y + h / 2f + px(1f),
            UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
        String sub = current == null ? Lang.tr("Compte Minecraft")
            : current.offline ? Lang.tr("Compte hors ligne") : Lang.tr("Compte Microsoft");
        renderer.drawText(sub, textX, y + h / 2f - px(13f), UiTheme.TEXT_MUTED, px(0.34f), vw, vh);
        String chevron = expanded ? CHEVRON_UP : CHEVRON_DOWN;
        BufferedImage arrow = UiRemoteImage.get(chevron);
        if (arrow != null) {
            // Blanc dans l'image : l'opacité lui donne le ton du texte secondaire, plus franc au survol.
            renderer.drawIcon(chevron, arrow, x + w - arrowW - pad - px(3f), y + (h - arrowW) / 2f, arrowW, arrowW,
                0.6f + 0.4f * hover, vw, vh);
        }
    }

    private static GameAccounts.Account currentAccount() {
        for (GameAccounts.Account account : GameAccounts.accounts()) {
            if (GameAccounts.isCurrent(account)) {
                return account;
            }
        }
        return null;
    }

    /**
     * La tête du skin, demandée au même service d'avatars que le launcher. Le
     * temps qu'elle arrive — ou si elle n'arrive pas — une pastille à
     * l'initiale du pseudo tient sa place.
     */
    private static void drawAvatar(UiRenderer renderer, String uuid, String name, float x, float y, float size, int vw, int vh) {
        float radius = px(4f);
        BufferedImage head = null;
        String url = null;
        if (uuid != null && !uuid.isEmpty()) {
            url = "https://mc-heads.net/avatar/" + uuid.replace("-", "") + "/64";
            head = UiRemoteImage.get(url);
        }
        if (head != null) {
            renderer.drawIcon(url, head, x, y, size, size, vw, vh);
            return;
        }
        renderer.drawRoundedRect(x, y, x + size, y + size, radius, UiTheme.ACCENT_DIM, vw, vh);
        String initial = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        float scale = size / px(38f) * px(0.56f);
        float tw = renderer.textWidth(UiFont.BOLD, initial, scale);
        renderer.drawText(UiFont.BOLD, initial, x + (size - tw) / 2f, y + size / 2f - size * 0.14f, UiTheme.TEXT_PRIMARY, scale, vw, vh);
    }

    // ── Le panneau des comptes ───────────────────────────────────────────────

    /** Les lignes du message affiché sous les comptes (chargement, erreur), coupées à la largeur du panneau. */
    private static List<String> messageLines(UiRenderer renderer, float width) {
        String message = GameAccounts.error();
        if (message == null && GameAccounts.accounts().isEmpty()) {
            message = GameAccounts.loading() ? "Chargement des comptes..." : "Aucun compte dans le launcher";
        }
        List<String> lines = new ArrayList<>();
        if (message == null) {
            return lines;
        }
        float scale = px(0.36f);
        float max = width - px(24f);
        StringBuilder line = new StringBuilder();
        for (String word : Lang.tr(message).split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && renderer.textWidth(candidate, scale) > max) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static void drawPanel(UiRenderer renderer, float x, float bottom, float w, float top, float rowH,
                                  List<String> message, float lineH, double mx, double my, int vw, int vh) {
        renderer.drawShadow(x, bottom, x + w, top, UiTheme.RADIUS_MD, px(12f), 0f, new UiColor(0, 0, 0, 110), vw, vh);
        renderer.drawRoundedRect(x, bottom, x + w, top, UiTheme.RADIUS_MD, UiTheme.PANEL_BG.withAlpha(0.95f), vw, vh);

        List<GameAccounts.Account> list = GameAccounts.accounts();
        float pad = px(7f);
        float inset = px(3f);
        for (int i = 0; i < list.size(); i++) {
            GameAccounts.Account account = list.get(i);
            float rowTop = top - i * rowH;
            float rowBottom = rowTop - rowH;
            boolean current = GameAccounts.isCurrent(account);
            boolean hovered = inside(mx, my, x, rowBottom, w, rowH);
            if (current || hovered) {
                renderer.drawRoundedRect(x + inset, rowBottom + inset, x + w - inset, rowTop - inset, UiTheme.RADIUS_SM,
                    current ? UiTheme.ACCENT_DIM : UiTheme.CARD_HOVER, vw, vh);
            }
            float avatar = rowH - pad * 2f;
            drawAvatar(renderer, account.uuid, account.name, x + pad + inset, rowBottom + pad, avatar, vw, vh);
            float textX = x + pad + inset + avatar + px(9f);
            // « hors ligne » à droite, en petit : ce compte ne rejoint pas les serveurs qui vérifient les comptes.
            String tag = account.offline ? Lang.tr("hors ligne") : "";
            float tagScale = px(0.34f);
            float tagW = tag.isEmpty() ? 0f : renderer.textWidth(tag, tagScale);
            float nameScale = px(0.42f);
            renderer.drawText(fit(renderer, UiFont.REGULAR, account.name, x + w - textX - tagW - pad * 2f, nameScale), textX,
                rowBottom + rowH / 2f - px(4f), UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
            if (!tag.isEmpty()) {
                renderer.drawText(tag, x + w - tagW - pad - inset, rowBottom + rowH / 2f - px(4f), UiTheme.TEXT_MUTED, tagScale, vw, vh);
            }
        }

        float lineTop = top - list.size() * rowH - px(6f);
        UiColor color = GameAccounts.error() != null ? UiTheme.DANGER : UiTheme.TEXT_MUTED;
        for (int i = 0; i < message.size(); i++) {
            renderer.drawText(message.get(i), x + px(12f), lineTop - (i + 1) * lineH + px(2f), color,
                px(0.36f), vw, vh);
        }
    }

    /** Le texte, raccourci par « ... » s'il dépasse la largeur donnée. */
    private static String fit(UiRenderer renderer, UiFont font, String text, float max, float scale) {
        if (renderer.textWidth(font, text, scale) <= max) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && renderer.textWidth(font, cut + "...", scale) > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    // ── Le bouton du menu YuyuFrame ──────────────────────────────────────────

    private static final String GEAR_ICON = LauncherModule.icons8("settings");
    private static final String CHEVRON_DOWN = LauncherModule.icons8("expand-arrow");
    private static final String CHEVRON_UP = LauncherModule.icons8("collapse-arrow");

    private static void drawGear(UiRenderer renderer, float x, float y, float size, int vw, int vh) {
        float hover = gearHover.get();
        renderer.drawShadow(x, y, x + size, y + size, UiTheme.RADIUS_MD, px(12f), 0f, new UiColor(0, 0, 0, 110), vw, vh);
        UiColor bg = UiColor.lerp(UiTheme.PANEL_BG.withAlpha(0.86f), UiTheme.ACCENT.withAlpha(0.55f), hover);
        renderer.drawRoundedRect(x, y, x + size, y + size, UiTheme.RADIUS_MD, bg, vw, vh);
        BufferedImage icon = UiRemoteImage.get(GEAR_ICON);
        if (icon != null) {
            // Grossit un peu au survol : seul retour visuel sur un bouton sans libellé.
            float iconSize = size * (0.46f + 0.06f * hover);
            float off = (size - iconSize) / 2f;
            renderer.drawIcon(GEAR_ICON, icon, x + off, y + off, iconSize, iconSize, vw, vh);
            return;
        }
        // L'icône n'est pas (encore) arrivée : le bouton dit ce qu'il ouvre.
        String label = Lang.tr("Menu");
        float scale = px(0.36f);
        float tw = renderer.textWidth(label, scale);
        renderer.drawText(label, x + (size - tw) / 2f, y + size / 2f - px(4f), UiTheme.TEXT_PRIMARY, scale, vw, vh);
    }
}
