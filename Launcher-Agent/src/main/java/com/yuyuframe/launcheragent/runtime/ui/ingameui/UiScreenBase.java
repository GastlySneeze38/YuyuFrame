package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiEasing;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiTransition;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTooltip;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * Base pour tout écran custom 100% dessiné à la main (voir UiDrawable pour le
 * "pourquoi" — pas de Screen.render() surchargé, types record sans stub
 * compilable en 1.21+ ; le dessin reste entièrement piloté par
 * GlobalUiRenderMixin, jamais par render()).
 *
 * Compile contre le stub Screen/Component comme les autres écrans custom
 * (ResourcePackSearchScreen etc.) — patché au chargement par ScreenStubPatcher
 * (voir LauncherMixinTransformerWrapper.STUB_PATCHED_SCREENS, y ajouter cette
 * classe).
 *
 * {@code mouseClicked}/{@code keyPressed} SONT overridés (voir plus bas) —
 * ce sont des méthodes "feuille" de {@code Element} (stables depuis 1.13,
 * mêmes signatures jusqu'en 1.21+), contrairement à des points d'entrée de
 * CYCLE DE VIE comme {@code onClose()}/{@code init()}/{@code tick()} : un
 * override de {@code onClose()} a été tenté puis abandonné (voir son
 * historique) après avoir provoqué un crash, du fait que d'autres chemins
 * internes du jeu peuvent en attendre un effet synchrone. Les méthodes
 * d'entrée utilisateur pure (clic, touche) n'ont pas ce risque : elles ne
 * sont appelées QUE par le dispatch d'input, jamais par un autre système
 * interne — les overrider laisse Minecraft nous notifier directement au lieu
 * qu'on sonde nous-mêmes l'état brut GLFW/LWJGL2 en parallèle (ce qui causait
 * un double traitement du même clic/de la même touche, source de plusieurs
 * bugs de cette session).
 */
public abstract class UiScreenBase extends Screen implements UiDrawable {

    protected final List<UiWidget> widgets = new ArrayList<>();
    protected int screenWidth, screenHeight; // pixels framebuffer, mis à jour chaque frame — voir uiPollInput()

    private boolean navigationRequested;
    private Object navigationTarget;

    /**
     * Cible de retour pour une fermeture par la touche Échap — même valeur que
     * celle utilisée par le bouton "Retour"/"Fermer" de chaque écran (voir
     * constructeurs des sous-classes). Renseignée par la sous-classe, {@code
     * null} par défaut (Échap ferme complètement, comme au niveau racine du
     * menu).
     */
    protected Object escapeTarget;

    /**
     * "Rideau" qui se lève à l'apparition de CET écran — pas une vraie
     * transition croisée entre l'ancien et le nouveau Screen (le nouveau
     * Screen ne devient "courant" qu'à la frame SUIVANTE, voir closeTo()/
     * GlobalUiRenderMixin : les deux ne sont jamais dessinés simultanément,
     * donc pas de fondu enchaîné possible sans retarder le vrai
     * mc.setScreen(), un changement bien plus risqué sur du code Mixin
     * partagé par les deux pipelines). À la place : tout l'écran (fond +
     * widgets) est dessiné normalement dès la première frame, puis recouvert
     * d'un voile opaque qui s'estompe en {@link #uiDraw} — l'écran semble
     * "se révéler" au lieu d'apparaître d'un coup sec. Générique ici (PAS
     * dans UiMainMenuScreen) : chaque écran custom (config d'un module, HUD
     * editor...) en profite automatiquement, aucun changement par écran.
     */
    private final UiTransition enterAnim = new UiTransition(0.22f, 0f, UiEasing.EASE_OUT_CUBIC);

    /**
     * Dernier écran custom effectivement dessiné, TOUS types confondus — sert
     * UNIQUEMENT à détecter une RÉ-activation d'un écran déjà existant (ex:
     * "Retour" vers l'instance de UiMainMenuScreen passée en lastScreen à
     * l'ouverture d'un écran de config, voir closeTo()) : cette instance n'est
     * jamais reconstruite, donc son enterAnim (déjà à 1.0 depuis longtemps) ne
     * rejouerait jamais sans ça — l'écran de retour semblait apparaître d'un
     * coup sec, sans transition, contrairement à un écran fraîchement ouvert.
     */
    private static UiScreenBase lastActiveScreen;

    /**
     * Constructeur no-arg de Screen (PAS Screen(Component title)) — ce dernier
     * n'existe pas forcément sur toutes les versions (ex: absent en 1.8.9,
     * observé via NoSuchMethodError: axu.<init>(Leu;)V lors du premier test en
     * jeu). Notre écran ne s'appuie de toute façon jamais sur le rendu de
     * titre intégré de Screen — {@code title} n'est conservé ici que pour un
     * usage éventuel (logs, debug), jamais transmis à la superclasse.
     */
    protected UiScreenBase(String title) {
        super();
        enterAnim.show();
    }

    /**
     * Dernier {@code UiInputPoller} vu — mis à jour à CHAQUE frame par
     * {@link #uiPollInput}, lu par {@link #mouseClicked} (appelé par le VRAI
     * dispatch d'input de Minecraft, PAS par notre propre boucle, donc sans
     * accès direct à l'input de cette frame autrement). Un décalage d'au plus
     * une frame entre la position mémorisée ici et la position réelle au
     * moment du clic est sans conséquence pratique (la souris ne "téléporte"
     * pas d'une frame à l'autre) — voir javadoc de {@link #mouseClicked} pour
     * pourquoi on n'utilise JAMAIS les coordonnées passées par Minecraft lui-même.
     */
    private UiInputPoller lastInput;

    @Override
    public void uiPollInput(UiInputPoller input) {
        lastInput = input;
        screenWidth = input.fbWidth;
        screenHeight = input.fbHeight;

        // Continu — CHAQUE widget, chaque frame, indépendamment du clic (drag
        // de slider, capture de touche en cours...). Le clic lui-même est
        // géré par le VRAI mouseClicked() ci-dessous, plus ici — voir sa
        // javadoc et celle de la classe.
        for (UiWidget w : widgets) {
            try {
                w.pollContinuous(input);
            } catch (Throwable t) {
                LauncherLog.err("[UiScreenBase] pollContinuous: " + t);
            }
        }
    }

    /**
     * PAS de {@code @Override} : le stub de compilation {@code Screen}
     * (src/stubs) ne déclare pas cette méthode (déclarée sur l'interface
     * {@code Element} du vrai jeu) — la JVM la reconnaît quand même comme un
     * override réel une fois la superclasse patchée par bytecode (voir
     * javadoc de classe et historique de session — même motif que partout
     * ailleurs dans ce module pour les méthodes héritées du vrai jeu).
     *
     * N'utilise JAMAIS {@code mouseX}/{@code mouseY} passés en paramètre par
     * Minecraft : cette méthode réelle les fournit dans l'espace "GUI scaled"
     * (Screen.width/height), alors que TOUT notre système de widgets travaille
     * en pixels FRAMEBUFFER bruts (voir UiInputPollerModern/Legacy, mêmes
     * unités que gl_FragCoord) — les deux espaces ne coïncident qu'à un
     * facteur d'échelle GUI de 1. Utilise {@link #lastInput} à la place
     * (mêmes coordonnées que le hover/dessin, cohérence garantie).
     */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        LauncherLog.info("[LauncherAgent] DIAG-116: mouseClicked() appelé sur " + getClass().getSimpleName()
            + " button=" + button + " param=(" + mouseX + "," + mouseY + ") lastInput="
            + (lastInput == null ? "null" : "(" + lastInput.mouseX + "," + lastInput.mouseY + ")")
            + " widgets=" + widgets.size());
        if (button != 0 || lastInput == null) return false;
        for (UiWidget w : widgets) {
            if (w.contains(lastInput.mouseX, lastInput.mouseY)) {
                try {
                    w.onClick();
                } catch (Throwable t) {
                    LauncherLog.err("[UiScreenBase] onClick: " + t);
                }
                return true; // un seul widget cliqué, "handled" — pas de double dispatch vanilla derrière
            }
        }
        return false;
    }

    /**
     * PAS de {@code @Override} — même raison que {@link #mouseClicked}.
     * Échap ferme désormais via ce VRAI callback (routé vers l'exact même
     * {@link #closeTo} que le bouton "Retour"/"Fermer"), et non plus via un
     * sondage GLFW en parallèle (voir historique de session — un ancien
     * sondage d'Échap, `escapeKeyPressed`, a été essayé puis retiré pour la
     * même raison qu'expliquée dans la javadoc de classe : éviter le double
     * traitement d'une même touche).
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        LauncherLog.info("[LauncherAgent] DIAG-116: keyPressed() appelé sur " + getClass().getSimpleName() + " keyCode=" + keyCode);
        if (keyCode == 256) { // GLFW_KEY_ESCAPE — constante GLFW publique stable, pas d'obfuscation
            closeTo(escapeTarget);
            return true;
        }
        return false;
    }

    /**
     * Équivalent 1.8.9 (LWJGL2/pré-refonte-Element) de {@link #mouseClicked}
     * ci-dessus — signature RÉELLE historique de {@code GuiScreen} (int, pas
     * double ; pas de valeur de retour ; jamais renommée depuis, mappings MCP
     * publics stables). Coexiste sans conflit avec la version "moderne" :
     * chaque version n'active RÉELLEMENT que celle dont la signature
     * correspond à son vrai Screen (l'autre reste une méthode inerte, jamais
     * appelée par le jeu). Voir javadoc de {@link #mouseClicked} pour pourquoi
     * on ignore les coordonnées passées en paramètre au profit de
     * {@link #lastInput}.
     */
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || lastInput == null) return;
        for (UiWidget w : widgets) {
            if (w.contains(lastInput.mouseX, lastInput.mouseY)) {
                try {
                    w.onClick();
                } catch (Throwable t) {
                    LauncherLog.err("[UiScreenBase] onClick: " + t);
                }
                return;
            }
        }
    }

    /** Équivalent 1.8.9 de {@link #keyPressed} — GuiScreen.keyTyped(char,int), keyCode 1 = Keyboard.KEY_ESCAPE (LWJGL2). */
    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) closeTo(escapeTarget);
    }

    @Override
    public void uiDraw(double mouseX, double mouseY) {
        try {
            // Détecte une (RÉ)activation de CET écran — soit tout frais, soit
            // un "Retour" vers une instance déjà existante (voir javadoc de
            // lastActiveScreen) — et relance le rideau depuis 0 dans le second
            // cas (le premier l'a déjà, enterAnim.show() du constructeur
            // suffit alors, replay() est juste redondant/sans effet visible).
            if (lastActiveScreen != this) {
                lastActiveScreen = this;
                enterAnim.replay();
            }

            UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
            renderer.drawRoundedRect(0, 0, screenWidth, screenHeight, 0, overlayColor(), screenWidth, screenHeight);

            String hoveredTooltip = null;
            for (UiWidget w : widgets) {
                w.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
                if (w.tooltip != null && w.contains(mouseX, mouseY)) hoveredTooltip = w.tooltip;
            }
            if (hoveredTooltip != null) UiTooltip.draw(renderer, hoveredTooltip, mouseX, mouseY, screenWidth, screenHeight);

            drawRevealVeil(renderer);
        } catch (Throwable t) {
            LauncherLog.err("[UiScreenBase] uiDraw: " + t);
        }
    }

    /**
     * Voile de révélation — voir javadoc d'{@code enterAnim}. Opaque au
     * premier instant (masque tout), s'estompe ensuite pour "révéler" l'écran
     * déjà entièrement dessiné dessous. Appelé automatiquement en fin de
     * {@link #uiDraw} ci-dessus, MAIS aussi ré-appelable explicitement par une
     * sous-classe qui rajoute son propre contenu APRÈS {@code super.uiDraw()}
     * (titres/logos par-dessus tout, voir UiMainMenuScreen) : sans ce second
     * appel, ce contenu additionnel apparaîtrait d'un coup sec, jamais couvert
     * par le voile puisque dessiné après lui.
     */
    protected void drawRevealVeil(UiRenderer renderer) {
        float reveal = 1f - Math.max(0f, Math.min(1f, enterAnim.eased()));
        if (reveal > 0.001f) {
            renderer.drawRoundedRect(0, 0, screenWidth, screenHeight, 0,
                new UiColor(8, 8, 12, 255).multiplyAlpha(reveal), screenWidth, screenHeight);
        }
    }

    /**
     * Fond derrière les widgets — quasi-opaque par défaut (nos menus classiques,
     * jeu figé visuellement même si le monde continue en fait de tourner
     * derrière). UiHudEditorScreen surcharge avec un fond quasi-transparent :
     * le jeu (monde + HUD vanilla) est DÉJÀ rendu en direct sous nos écrans
     * (nos Screen custom ne sont pas le menu pause vanilla, qui seul stoppe la
     * simulation en solo) — seul cet overlay presque opaque le masquait.
     */
    protected UiColor overlayColor() {
        return UiTheme.OVERLAY_BG;
    }

    /**
     * Ferme cet écran — renvoie à lastScreen si fourni par la sous-classe
     * (voir UiMainMenuScreen), ou ferme vers le jeu si null. N'appelle PAS
     * mc.setScreen() directement : ScreenHelper (runtime.screen) code en dur
     * les noms obfusqués "official" de 1.21 (ex: MinecraftClient="gfj") — noms
     * qui n'existent tout simplement pas sur 1.8.9 (obfuscation complètement
     * différente, Mojang n'a jamais publié de mappings officiels pour cette
     * version), d'où un ClassNotFoundException("gfj") silencieux observé en
     * jeu : chaque clic "ouvrir/fermer" ne faisait rien sur 1.8.9.
     *
     * La navigation est donc juste ENREGISTRÉE ici ; c'est le Mixin global de
     * CHAQUE version (GlobalUiRenderMixin en 1.21+, GlobalUiRenderMixin189 en
     * 1.8.9 — seuls endroits qui résolvent déjà correctement mc + setScreen
     * pour LEUR version, voir leurs champs CLS_MC respectifs) qui l'applique
     * réellement, juste après avoir appelé uiDraw() cette frame.
     */
    protected void closeTo(Object lastScreen) {
        navigationTarget = lastScreen;
        navigationRequested = true;
    }

    /** Consommé par le Mixin global de chaque version — true si closeTo() a été appelé cette frame. */
    public boolean hasPendingNavigation() { return navigationRequested; }

    /** Cible demandée (peut être null = fermer vers le jeu) — remet le flag à false. */
    public Object consumePendingNavigation() {
        navigationRequested = false;
        return navigationTarget;
    }

}
