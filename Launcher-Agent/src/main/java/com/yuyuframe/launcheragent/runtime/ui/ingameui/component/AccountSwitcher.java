package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.account.GameAccounts;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * La liste déroulante des comptes Minecraft, en haut de la barre latérale du
 * client : le compte avec lequel on joue, et, dépliée, ceux du launcher.
 * Cliquer un compte y fait passer le jeu, sans relancer
 * ({@link GameAccounts}).
 *
 * <p>Fermée en jeu : une fois dans un monde, l'identité est liée à la
 * connexion. Le bouton reste là, il dit seulement pourquoi il ne s'ouvre pas.
 *
 * <p>Le panneau déplié est dessiné par ce widget lui-même, dans
 * {@link #draw} : l'écran ne fait pas de second passage pour ses widgets
 * directs. Deux conséquences, tenues par l'écran qui le pose : il doit être
 * ajouté EN DERNIER (ordre d'ajout = ordre de peinture, le panneau recouvre
 * les entrées de navigation en dessous), et {@link #contains} couvre le
 * panneau quand il est ouvert, pour que le clic ne tombe pas sur ce qu'il
 * recouvre.
 *
 * <p>Repère de l'écran : y vers le HAUT. Le panneau se déplie donc vers les y
 * décroissants.
 */
public final class AccountSwitcher extends UiWidget {

    private final float rowH = UiTheme.scaled(24f);
    private final float panelPad = UiTheme.scaled(4f);
    private final float textScale = UiTheme.scaled(0.4f);
    private final float smallScale = UiTheme.scaled(0.34f);
    private final float textPad = UiTheme.scaled(10f);
    private final float textVOff = UiTheme.scaled(4f);
    private final float lineH = UiTheme.scaled(11f);

    private boolean expanded;
    private double lastMouseX, lastMouseY;
    /** La dernière erreur déjà montrée : une nouvelle rouvre le panneau. */
    private String shownError;
    private final UiAnimatedFloat hoverAnim = new UiAnimatedFloat(0f, 16f);

    public AccountSwitcher() {
        super(0f, 0f, 0f, UiTheme.scaled(26f));
    }

    /** En jeu, ou pendant qu'un changement est en cours : la liste ne s'ouvre pas. */
    private static boolean locked() {
        return PlayerData.inGame() || GameAccounts.switching();
    }

    // ── Géométrie du panneau ─────────────────────────────────────────────────

    /** Les lignes du message affiché sous les comptes (chargement, erreur), coupées à la largeur du panneau. */
    private List<String> messageLines(UiRenderer renderer) {
        String message = GameAccounts.error();
        if (message == null && GameAccounts.accounts().isEmpty()) {
            message = GameAccounts.loading() ? "Chargement des comptes..." : "Aucun compte dans le launcher";
        }
        List<String> lines = new ArrayList<>();
        if (message == null || renderer == null) {
            return lines;
        }
        float max = w - textPad * 2f;
        StringBuilder line = new StringBuilder();
        for (String word : Lang.tr(message).split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && renderer.textWidth(candidate, smallScale) > max) {
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

    /** Hauteur du message mesurée au dernier dessin : {@link #contains} n'a pas de renderer sous la main. */
    private float messageH;

    private float panelTop() {
        return y - panelPad;
    }

    private float panelBottom() {
        return panelTop() - GameAccounts.accounts().size() * rowH - messageH;
    }

    @Override
    public boolean contains(double mx, double my) {
        if (super.contains(mx, my)) {
            return true;
        }
        return expanded && mx >= x && mx <= x + w && my >= panelBottom() && my <= panelTop();
    }

    // ── Dessin ───────────────────────────────────────────────────────────────

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        boolean inGame = PlayerData.inGame();
        if (locked()) {
            expanded = false;
        } else {
            // Un changement vient d'échouer : le panneau se rouvre pour le dire, il ne reste pas muet.
            String error = GameAccounts.error();
            if (error != null && !error.equals(shownError)) {
                expanded = true;
            }
            shownError = error;
        }
        // Dit pourquoi le bouton ne s'ouvre pas, plutôt que de rester muet.
        tooltip = inGame ? Lang.tr("Quittez le monde pour changer de compte") : null;

        boolean overHeader = super.contains(mouseX, mouseY);
        hoverAnim.setTarget(overHeader && !inGame ? 1f : 0f);
        UiColor bg = expanded ? UiTheme.ACCENT_DIM : UiColor.lerp(UiTheme.PANEL_BG_ALT, UiTheme.CARD_HOVER, hoverAnim.get());
        renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, bg, vpWidth, vpHeight);

        String name = ClientData.username();
        String label = GameAccounts.switching() ? Lang.tr("Changement de compte...") : name.isEmpty() ? Lang.tr("Compte") : name;
        String arrow = inGame ? "" : expanded ? "^" : "v";
        float arrowW = arrow.isEmpty() ? 0f : renderer.textWidth(arrow, textScale);
        renderer.drawText(fit(renderer, label, w - textPad * 3f - arrowW, textScale), x + textPad, y + h / 2f - textVOff,
            inGame ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
        if (!arrow.isEmpty()) {
            renderer.drawText(arrow, x + w - arrowW - textPad, y + h / 2f - textVOff, UiTheme.TEXT_SECONDARY, textScale, vpWidth, vpHeight);
        }

        if (expanded) {
            drawPanel(renderer, mouseX, mouseY, vpWidth, vpHeight);
        }
    }

    private void drawPanel(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        List<GameAccounts.Account> accounts = GameAccounts.accounts();
        List<String> message = messageLines(renderer);
        messageH = message.isEmpty() ? 0f : message.size() * lineH + panelPad * 2f;

        float top = panelTop();
        float bottom = panelBottom();
        renderer.drawShadow(x, bottom, x + w, top, UiTheme.RADIUS_SM, 8f, 0f, new UiColor(0, 0, 0, 90), vpWidth, vpHeight);
        renderer.drawRoundedRect(x, bottom, x + w, top, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT, vpWidth, vpHeight);

        float rowInset = UiTheme.scaled(2f), rowRadius = UiTheme.scaled(2f);
        for (int i = 0; i < accounts.size(); i++) {
            GameAccounts.Account account = accounts.get(i);
            float rowTop = top - i * rowH;
            float rowBottom = rowTop - rowH;
            boolean current = GameAccounts.isCurrent(account);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= rowBottom && mouseY <= rowTop;
            if (current || hovered) {
                renderer.drawRoundedRect(x + rowInset, rowBottom + 1, x + w - rowInset, rowTop - 1, rowRadius,
                    current ? UiTheme.ACCENT_DIM : UiTheme.CARD_HOVER, vpWidth, vpHeight);
            }
            // « hors ligne » à droite, en petit : ce compte ne rejoint pas les serveurs qui vérifient les comptes.
            String tag = account.offline ? Lang.tr("hors ligne") : "";
            float tagW = tag.isEmpty() ? 0f : renderer.textWidth(tag, smallScale);
            renderer.drawText(fit(renderer, account.name, w - textPad * 3f - tagW, textScale), x + textPad,
                rowBottom + rowH / 2f - textVOff, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            if (!tag.isEmpty()) {
                renderer.drawText(tag, x + w - tagW - textPad, rowBottom + rowH / 2f - textVOff, UiTheme.TEXT_MUTED, smallScale, vpWidth, vpHeight);
            }
        }

        float lineTop = top - accounts.size() * rowH - panelPad;
        UiColor messageColor = GameAccounts.error() != null ? UiTheme.DANGER : UiTheme.TEXT_MUTED;
        for (int i = 0; i < message.size(); i++) {
            renderer.drawText(message.get(i), x + textPad, lineTop - (i + 1) * lineH + UiTheme.scaled(2f), messageColor, smallScale, vpWidth, vpHeight);
        }
    }

    /** Le texte, raccourci par « … » s'il dépasse la largeur donnée. */
    private static String fit(UiRenderer renderer, String text, float max, float scale) {
        if (renderer.textWidth(text, scale) <= max) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && renderer.textWidth(cut + "...", scale) > max) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    // ── Clics ────────────────────────────────────────────────────────────────

    @Override
    public void onClick() {
        if (locked()) {
            return;
        }
        if (super.contains(lastMouseX, lastMouseY)) {
            expanded = !expanded;
            if (expanded) {
                // À chaque ouverture : un compte a pu être ajouté dans le launcher entre-temps.
                GameAccounts.refresh();
            }
            return;
        }
        if (!expanded) {
            return;
        }
        List<GameAccounts.Account> accounts = GameAccounts.accounts();
        int row = (int) ((panelTop() - lastMouseY) / rowH);
        if (row >= 0 && row < accounts.size()) {
            GameAccounts.Account account = accounts.get(row);
            expanded = false;
            if (!GameAccounts.isCurrent(account)) {
                GameAccounts.switchTo(account);
            }
        }
    }

    /** Un clic ailleurs referme le panneau. */
    @Override
    public void pollContinuous(UiInputPoller input) {
        if (expanded && input.leftClicked && !contains(input.mouseX, input.mouseY)) {
            expanded = false;
        }
    }
}
