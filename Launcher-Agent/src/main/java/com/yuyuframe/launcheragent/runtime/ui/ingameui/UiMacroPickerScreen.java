package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.gameplay.MacroModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

/**
 * Palette de macros — un bouton par macro, ouverte en jeu par une touche.
 *
 * <h2>À quoi ça sert</h2>
 *
 * Demande de l'utilisateur : « quand on a beaucoup de macros on ne remplit pas
 * tout notre clavier ». Une macro n'a donc plus besoin d'une touche à elle —
 * une seule touche ouvre cette palette, on clique, ça s'exécute et l'écran se
 * ferme. Les macros QUI ONT une touche continuent de fonctionner comme avant :
 * les deux voies coexistent, la palette ne remplace rien.
 *
 * <p>Une macro sans touche assignée est parfaitement valide et n'apparaît
 * qu'ici — c'est même le cas d'usage principal.
 *
 * <h2>Pourquoi un vrai écran</h2>
 *
 * Il faut cliquer, donc il faut un curseur : dessiner par-dessus le HUD ne
 * suffirait pas, la souris reste capturée par le jeu tant qu'aucun écran n'est
 * ouvert. C'est un écran custom ({@code UiDrawable}), pris en charge par le
 * mixin global comme les autres.
 */
public final class UiMacroPickerScreen extends UiScreenBase {

    private static final UiColor OVERLAY = new UiColor(6, 6, 10, 80);
    private static final int GLASS_PASSES = 3;

    private final MacroModule module;
    private UiScrollContainer scroll;
    private float px, py, pw, ph;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;
    private int lastRevision = -1;

    public UiMacroPickerScreen(MacroModule module) {
        super("Macros");
        this.module = module;
        // Échap (et la fermeture par clic) renvoie AU JEU, pas à un écran
        // parent : cette palette s'ouvre depuis le jeu, jamais depuis un menu.
        this.escapeTarget = null;
    }

    @Override
    protected UiColor overlayColor() {
        return OVERLAY;
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        // La révision du module s'ajoute aux trois conditions habituelles —
        // même raison que dans UiModConfigScreen : la liste des macros peut
        // changer pendant que cet écran est ouvert (une commande client, un
        // autre écran) sans que la taille ni l'échelle ne bougent. Le compteur
        // existait déjà côté module, autant s'y brancher.
        if (screenWidth > 0 && screenHeight > 0
                && (screenWidth != lastW || screenHeight != lastH
                    || UiTheme.UI_SCALE != lastScale || module.settingsRevision != lastRevision)) {
            buildLayout();
            lastW = screenWidth; lastH = screenHeight; lastScale = UiTheme.UI_SCALE;
            lastRevision = module.settingsRevision;
        }
        UiRenderer renderer = UiRenderer.get(getClass().getClassLoader());
        renderer.beginGlassFrame(GLASS_PASSES, screenWidth, screenHeight);
        super.uiDraw(mouseX, mouseY);
        try {
            renderer.drawGlassPanel(px, py, px + pw, py + ph, UiTheme.RADIUS_MD,
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_FIELD, UiTheme.PANEL_BG,
                UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), screenWidth, screenHeight);

            float pad = UiTheme.scaled(18f);
            renderer.drawText(UiFont.BOLD, Lang.tr("Macros"), px + pad, py + ph - UiTheme.scaled(28f),
                UiTheme.TEXT_PRIMARY, UiTheme.scaled(0.58f), screenWidth, screenHeight);

            if (module.macros.isEmpty()) {
                renderer.drawText(Lang.tr("Aucune macro — ajoute-en dans les réglages du module."),
                    px + pad, py + ph / 2f, UiTheme.TEXT_MUTED, UiTheme.scaled(0.46f), screenWidth, screenHeight);
            } else if (scroll != null) {
                scroll.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiMacroPickerScreen] uiDraw: " + t);
        }
    }

    @Override
    public void uiPollInput(UiInputPoller input) {
        super.uiPollInput(input);
        if (scroll != null) scroll.pollInput(input);
    }

    private void buildLayout() {
        widgets.clear();

        // GRILLE et non liste (demande utilisateur) — une colonne gâchait
        // toute la largeur pour afficher des libellés courts, et allongeait
        // le panneau dès qu'on avait quelques macros.
        pw = Math.min(UiTheme.scaled(720f), screenWidth - UiTheme.scaled(80f));

        float pad = UiTheme.scaled(20f);
        float gap = UiTheme.scaled(8f);
        float cellH = UiTheme.scaled(36f);
        float vpW = pw - pad * 2f;
        float scrollbarReserve = UiTheme.scaled(16f);
        float usableW = vpW - scrollbarReserve;

        // Nombre de colonnes déduit d'une largeur de cellule CIBLE : la grille
        // s'adapte à la fenêtre au lieu d'imposer un compte fixe qui serait
        // trop serré en petit écran et trop étalé en grand.
        float targetCellW = UiTheme.scaled(200f);
        int columns = Math.max(1, (int) Math.floor((usableW + gap) / (targetCellW + gap)));
        int count = module.macros.size();
        int rows = count == 0 ? 0 : (count + columns - 1) / columns;

        float chrome = UiTheme.scaled(72f);
        float gridH = rows * cellH + Math.max(0, rows - 1) * gap;
        ph = Math.min(screenHeight - UiTheme.scaled(80f),
                      Math.max(UiTheme.scaled(140f), gridH + chrome));
        px = (screenWidth - pw) / 2f;
        py = (screenHeight - ph) / 2f;

        float vpX = px + pad;
        float vpY = py + pad;
        float vpH = ph - chrome;
        scroll = new UiScrollContainer(vpX, vpY, vpW, Math.max(1f, vpH));

        float cellW = (usableW - (columns - 1) * gap) / columns;
        for (int i = 0; i < count; i++) {
            final MacroModule.Macro macro = module.macros.get(i);
            int col = i % columns;
            int row = i / columns;
            float bx = vpX + col * (cellW + gap);
            float by = -(row * (cellH + gap)) - cellH;
            scroll.add(new UiButton(bx, by, cellW, cellH, macro.label(), () -> {
                // On FERME d'abord : la commande part vers le serveur, il n'y
                // a aucune raison de rester sur la palette, et le joueur veut
                // revenir au jeu immédiatement.
                closeTo(null);
                module.runMacro(macro);
            }));
        }
    }

    // Le libellé vient de Macro.label() : le NOM s'il est renseigné, sinon
    // la commande. C'était la demande — « qu'il n'y ait que le nom de la
    // macro qui s'affiche dans la palette ».

    /** Aucun widget modal ici — la palette EST le contenu. */
    @Override
    protected java.util.List<UiWidget> modalWidgets() {
        return null;
    }
}
