package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiDrawable;
import com.yuyuframe.launcheragent.apigraphic.core.UiFocusable;
import com.yuyuframe.launcheragent.apigraphic.core.UiHitTest;
import com.yuyuframe.launcheragent.apigraphic.anim.UiEasing;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiTransition;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiKeybindButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
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

    /**
     * Widgets EXCLUSIFS pendant qu'un modal est actif (voir tiroir de filtres,
     * {@code ModrinthContentScreen}) — quand non-null, {@link #dispatchClick}
     * et {@link #uiPollInput} n'utilisent QUE cette liste (widgets normaux du
     * fond ignorés pour le clic/le survol continu), pour qu'un clic sur un
     * bouton d'arrière-plan ne traverse jamais le voile du modal. Nécessaire
     * car {@code widgets} est une seule liste PLATE testée dans l'ORDRE
     * D'INSERTION pour le clic ET le dessin (voir dispatchClick/uiDraw) — les
     * deux ordres ne peuvent pas être satisfaits simultanément pour un calque
     * qui doit être "dessiné en dernier" (par-dessus) mais "testé en premier"
     * (prioritaire au clic) si tout partage la même liste. Le DESSIN, lui,
     * N'EST PAS concerné par ce hook (widgets du fond toujours dessinés
     * normalement ci-dessous, dimés par le voile du modal que la sous-classe
     * dessine ELLE-MÊME par-dessus, après son propre contenu — voir
     * ModrinthContentScreen.uiDraw) : seule la découpe clic/survol est
     * générique ici, le z-order visuel reste piloté par la sous-classe.
     * {@code null} par défaut = comportement normal (widgets standards).
     */
    protected List<UiWidget> modalWidgets() { return null; }

    @Override
    public void uiPollInput(UiInputPoller input) {
        lastInput = input;
        screenWidth = input.fbWidth;
        screenHeight = input.fbHeight;

        // Continu — CHAQUE widget, chaque frame, indépendamment du clic (drag
        // de slider, capture de touche en cours...). Le clic lui-même est
        // géré par le VRAI mouseClicked() ci-dessous, plus ici — voir sa
        // javadoc et celle de la classe.
        List<UiWidget> active = modalWidgets();
        for (UiWidget w : (active != null ? active : widgets)) {
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
        return dispatchClick(button, false); // pas de doubleClick sur cette signature historique (bracket pré-1.21.11)
    }

    /**
     * Équivalent 1.21.11+ (Blaze3D) de {@link #mouseClicked(double, double, int)}
     * ci-dessus — sur cette version, {@code Element.mouseClicked} ne prend
     * plus {@code (double, double, int)} mais un type record {@code Click}
     * (voir stub {@code net.minecraft.client.gui.Click}, patché par
     * ScreenStubPatcher comme {@code Screen}/{@code Component}) : notre
     * ancienne surcharge ne correspond alors plus à AUCUN override réel côté
     * JVM (nom+descripteur exacts requis) et reste une méthode inerte — EXACT
     * même symptôme ("boutons pas cliquables") déjà rencontré et réglé pour
     * le bracket 1.13-1.16.x, mais cette fois-ci le changement porte sur le
     * DESCRIPTEUR entier de la méthode, pas juste son nom obfusqué. Ignore le
     * 2e paramètre ({@code boolean}, "focused"/dispatch interne vanilla —
     * jamais eu besoin de le lire). Coordonnées : {@link #lastInput} comme
     * partout ailleurs, pas {@code click.x()}/{@code click.y()} (même espace
     * "GUI scaled" que l'ancien mouseX/mouseY, voir javadoc ci-dessus).
     */
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubleClick) {
        return dispatchClick(click.button(), doubleClick);
    }

    /**
     * Équivalent 26.1+ de {@link #mouseClicked(net.minecraft.client.gui.Click, boolean)}
     * ci-dessus — MÊME symptôme ("boutons pas cliquables"), MÊME cause,
     * nouvelle version : sur 26.1.2, l'interface {@code Element} a été
     * RENOMMÉE {@code GuiEventListener} (déplacée dans {@code gui.components.
     * events}) ET son {@code mouseClicked} prend désormais un type record
     * {@code MouseButtonEvent} (package {@code net.minecraft.client.input},
     * pas {@code gui}) au lieu de {@code Click} — vérifié par javap sur le
     * jar client 26.1.2 réel : {@code mouseClicked(MouseButtonEvent, boolean)}.
     * Coordonnées : {@link #lastInput} comme partout ailleurs (voir javadoc
     * de {@link #mouseClicked(double, double, int)}), pas {@code event.x()}/
     * {@code event.y()}.
     */
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return dispatchClick(event.button(), doubleClick);
    }

    /**
     * Roadmap Phase 5.6 (BUG TROUVÉ : ignorait le bouton — seul {@code
     * button==0} passait le garde-fou du haut, gauche uniquement — ET le
     * paramètre {@code doubleClick}, fourni gratuitement par vanilla sur les
     * 2 signatures récentes, jamais lu). Fix : gère les 3 boutons (gauche/
     * droit/milieu — au-delà, {@code button>=3} ignoré, aucun widget de ce
     * moteur n'a de sens pour les boutons latéraux souris), propage
     * {@code doubleClick} au widget ciblé via {@link UiWidget#onClick(int, boolean)}
     * — comportement de CHAQUE widget existant qui ne surcharge que l'ancien
     * {@link UiWidget#onClick()} reste 100% inchangé (voir sa javadoc).
     */
    private boolean dispatchClick(int button, boolean doubleClick) {
        if (button < 0 || button > 2 || lastInput == null) return false;
        List<UiWidget> active = modalWidgets();
        List<UiWidget> targets = active != null ? active : widgets;
        // Hit-test centralisé (roadmap Phase 5.6, retour utilisateur : "c'est
        // ce qui est visible qui doit être cliquable") — voir UiHitTest pour
        // la règle exacte (plus petite aire gagne, PAS l'ordre d'insertion).
        //
        // BUG ÉVITÉ (pas rencontré en jeu, repéré à l'écriture) : {@code
        // widgets} est un CHAMP mutable, vidé+repeuplé EN PLACE par
        // rebuildAll() (même référence avant/après, ex: à chaque frappe dans
        // la recherche) — le cache par ÉGALITÉ DE RÉFÉRENCE de UiHitTest ne
        // verrait donc PAS un rebuild survenu entre deux clics à la même
        // position pixel (scénario réaliste : cliquer une carte, taper un
        // caractère qui rebuild la grille, cliquer au même endroit où une
        // AUTRE carte est maintenant affichée). invalidate() explicite ici
        // garantit un résultat toujours frais — coût nul en pratique (un
        // clic est un événement rare, pas un test par frame, la mise en
        // cache ne visait de toute façon pas ce point d'appel précis).
        UiHitTest.invalidate();
        UiWidget clicked = UiHitTest.find(targets, lastInput.mouseX, lastInput.mouseY);
        // Perte de focus au clic EXTÉRIEUR — barre de recherche moderne : un
        // champ texte reste focus indéfiniment sinon (pas de mécanisme
        // d'exclusivité ailleurs), curseur clignotant en fond même après avoir
        // cliqué sur un bouton/une carte à côté.
        for (UiWidget w : targets) {
            if (w instanceof UiTextField && w != clicked) ((UiTextField) w).setFocused(false);
        }
        if (clicked != null) {
            try {
                clicked.onClick(button, doubleClick);
            } catch (Throwable t) {
                LauncherLog.err("[UiScreenBase] onClick: " + t);
            }
            return true; // un seul widget cliqué, "handled" — pas de double dispatch vanilla derrière
        }
        return false;
    }

    /**
     * PAS de {@code @Override} — même raison que {@link #mouseClicked(double, double, int)}.
     * Échap ferme désormais via ce VRAI callback (routé vers l'exact même
     * {@link #closeTo} que le bouton "Retour"/"Fermer"), et non plus via un
     * sondage GLFW en parallèle (voir historique de session — un ancien
     * sondage d'Échap, `escapeKeyPressed`, a été essayé puis retiré pour la
     * même raison qu'expliquée dans la javadoc de classe : éviter le double
     * traitement d'une même touche).
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return dispatchKeyPressed(keyCode);
    }

    /**
     * Équivalent 1.21.11+ de {@link #keyPressed(int, int, int)} — même
     * raison que {@link #mouseClicked(net.minecraft.client.gui.Click, boolean)} :
     * {@code Element.keyPressed} prend désormais un type record
     * {@code KeyInput} (stub {@code net.minecraft.client.input.KeyInput}).
     */
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        return dispatchKeyPressed(input.key());
    }

    /**
     * Équivalent 26.1+ de {@link #keyPressed(net.minecraft.client.input.KeyInput)}
     * ci-dessus — même cause que {@link #mouseClicked(net.minecraft.client.input.MouseButtonEvent, boolean)} :
     * {@code GuiEventListener.keyPressed} prend désormais un type record
     * {@code KeyEvent} (un seul paramètre) — vérifié par javap sur le jar
     * client 26.1.2 réel.
     */
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        return dispatchKeyPressed(event.key());
    }

    private boolean dispatchKeyPressed(int keyCode) {
        if (keyCode == 256) { // GLFW_KEY_ESCAPE — constante GLFW publique stable, pas d'obfuscation
            handleEscape();
            return true;
        }
        if (keyCode == 258) { // GLFW_KEY_TAB — roadmap Phase 5.6, navigation clavier/focus
            boolean shift = lastInput != null && lastInput.shiftDown;
            return dispatchTab(shift);
        }
        return false;
    }

    /**
     * Cycle le focus entre les widgets {@link UiFocusable} de l'écran
     * (ex: {@code UiTextField}) — {@code backward} = Shift+Tab (sens
     * inverse). Ne parcourt QUE {@link #widgets}/{@link #modalWidgets()}
     * (même portée que {@link #dispatchClick}) — le contenu d'un {@code
     * UiScrollContainer} séparé (ex: la grille de mods de
     * UiMainMenuScreen) n'est pas encore inclus, aucun widget focusable n'y
     * vit actuellement (juste des cartes/toggles, pas de champ de texte).
     * {@code false} (non consommé, laissé à vanilla) si l'écran n'a aucun
     * widget focusable — comportement Tab par défaut du jeu inchangé dans
     * ce cas plutôt que d'avaler la touche pour rien.
     */
    private boolean dispatchTab(boolean backward) {
        List<UiWidget> active = modalWidgets();
        List<UiWidget> targets = active != null ? active : widgets;
        List<UiFocusable> focusables = new ArrayList<>();
        int currentIndex = -1;
        for (UiWidget w : targets) {
            if (w instanceof UiFocusable) {
                if (((UiFocusable) w).focused()) currentIndex = focusables.size();
                focusables.add((UiFocusable) w);
            }
        }
        if (focusables.isEmpty()) return false;
        int nextIndex;
        if (currentIndex < 0) {
            nextIndex = backward ? focusables.size() - 1 : 0;
        } else {
            nextIndex = backward ? (currentIndex - 1 + focusables.size()) % focusables.size()
                                  : (currentIndex + 1) % focusables.size();
        }
        if (currentIndex >= 0) focusables.get(currentIndex).setFocused(false);
        focusables.get(nextIndex).setFocused(true);
        return true;
    }

    /**
     * Premier Échap = juste retirer le focus d'un champ texte en cours de
     * saisie (n'importe où sur l'écran), SANS fermer l'écran — comportement
     * barre de recherche moderne (ex: GitHub) : Échap ne doit pas surprendre
     * l'utilisateur en train de taper en fermant tout d'un coup. Deuxième
     * Échap (aucun champ focus) : comportement historique inchangé, ferme
     * vers {@link #escapeTarget}.
     */
    private void handleEscape() {
        // CAPTURE DE TOUCHE en cours : Échap lui appartient, il sert à
        // n'assigner AUCUNE touche (voir UiKeybindButton.pollContinuous).
        //
        // BUG TROUVÉ (retour utilisateur 2026-08-31 : « quand on ne veut pas
        // bind de touche sur les macros il faut mettre Échap pour que le
        // composant le détecte comme none »). Le widget gérait déjà ce cas,
        // mais il ne voyait jamais la touche : Échap était intercepté ICI en
        // premier — fermant la modale de création, ou l'écran — bien avant que
        // la capture n'ait la main.
        if (UiKeybindButton.captureInProgress()) return;

        // Modal actif (voir modalWidgets()) : Échap le ferme, RIEN d'autre —
        // ne doit ni faire perdre le focus d'un champ de l'écran DERRIÈRE le
        // modal (inaccessible tant qu'il est ouvert) ni fermer l'écran entier
        // d'un coup (surprise pour l'utilisateur qui voulait juste fermer le
        // tiroir de filtres).
        if (modalWidgets() != null) {
            onModalEscape();
            return;
        }
        for (UiWidget w : widgets) {
            if (w instanceof UiTextField && ((UiTextField) w).focused()) {
                ((UiTextField) w).setFocused(false);
                return;
            }
        }
        closeTo(escapeTarget);
    }

    /** Appelé par Échap quand {@link #modalWidgets()} est actif — la sous-classe ferme SON modal ici (voir ModrinthContentScreen.toggleFilters). No-op par défaut. */
    protected void onModalEscape() {}

    /**
     * Équivalent 1.8.9 (LWJGL2/pré-refonte-Element) de {@link #mouseClicked}
     * ci-dessus — signature RÉELLE historique de {@code GuiScreen} (int, pas
     * double ; pas de valeur de retour).
     *
     * BUG TROUVÉ (retour utilisateur : "en 1.8.9 on ne peut plus cliquer sur
     * les boutons — même pas le bouton fermer", aucune exception nulle part)
     * — CORRIGÉ mais laissé en trace ici : contrairement à ce qu'affirmait
     * l'ancienne version de ce commentaire ("jamais renommée depuis, mappings
     * MCP publics stables"), le nom déclaré ici ("mouseClicked") n'a JAMAIS
     * correspondu au nom RÉEL en bytecode obfusqué de cette méthode sur
     * GuiScreen 1.8.9 — confondait la stabilité du nom MCP/Yarn NAMED
     * (lisible par un humain, effectivement stable) avec le nom OBFUSQUÉ
     * (celui que la JVM utilise pour lier un override, complètement
     * différent — "a", confirmé via mappings/mappings-1.8.9.tiny). Résolu
     * comme les autres surcharges au-dessus (voir {@code ScreenStubPatcher},
     * renommage ASM dynamique via {@code MappingsRegistry.getObfMethodName}).
     */
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        dispatchClick(mouseButton, false); // pas de doubleClick sur cette signature historique (1.8.9)
    }

    /**
     * Équivalent 1.8.9 de {@link #keyPressed} — GuiScreen.keyTyped(char,int),
     * keyCode 1 = Keyboard.KEY_ESCAPE (LWJGL2). MÊME correctif ASM que {@link
     * #mouseClicked(int, int, int)} ci-dessus (même cause, même fix) — nommé
     * "keyPressed" côté Yarn même en 1.8.9 (descripteur {@code (CI)V}),
     * malgré le nom "keyTyped" gardé ici pour rester fidèle à la convention
     * MCP historique du reste du module.
     */
    public void keyTyped(char typedChar, int keyCode) {
        if (keyCode == 1) handleEscape();
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
        // Filet de sécurité : si un champ de texte était encore focus au
        // moment de la fermeture (ex: clic sur "Retour" en pleine frappe,
        // sans passer par Échap qui, lui, retire déjà le focus le premier
        // coup — voir handleEscape), UiInputPoller.textInputActive resterait
        // sinon bloqué à true pour toute la session, et le callback caractère
        // GLFW continuerait à bufferiser les touches tapées en jeu (voir sa
        // javadoc) — remis à false ICI, inconditionnellement, à CHAQUE
        // fermeture d'écran custom, quel que soit l'état de focus au moment.
        UiInputPoller.textInputActive = false;

        // Les changements de configuration sont écrits en différé (voir
        // HudConfigStore.save) : on force l'écriture ICI, sinon fermer l'écran
        // moins d'une demi-seconde après la dernière frappe — ce qui est le
        // geste normal — perdrait cette dernière modification.
        com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.flush();

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
