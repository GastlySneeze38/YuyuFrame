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
 * "pourquoi" — pas de Screen.render/mouseClicked/keyPressed surchargés, types
 * record sans stub compilable en 1.21+).
 *
 * Compile contre le stub Screen/Component comme les autres écrans custom
 * (ResourcePackSearchScreen etc.) — patché au chargement par ScreenStubPatcher
 * (voir LauncherMixinTransformerWrapper.STUB_PATCHED_SCREENS, y ajouter cette
 * classe). N'override AUCUNE méthode Screen à risque : sert uniquement de
 * marqueur "un écran est ouvert" pour les effets de bord vanilla normaux
 * (pause, curseur libéré) — tout le reste passe par GlobalUiRenderMixin.
 */
public abstract class UiScreenBase extends Screen implements UiDrawable {

    protected final List<UiWidget> widgets = new ArrayList<>();
    protected int screenWidth, screenHeight; // pixels framebuffer, mis à jour chaque frame — voir uiPollInput()

    private boolean navigationRequested;
    private Object navigationTarget;

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

    @Override
    public void uiPollInput(UiInputPoller input) {
        screenWidth = input.fbWidth;
        screenHeight = input.fbHeight;

        // Continu — CHAQUE widget, chaque frame, indépendamment du clic (drag
        // de slider, capture de touche en cours...). Séparé du dispatch de
        // clic ci-dessous : pollContinuous ne référence jamais onClick.
        for (UiWidget w : widgets) {
            try {
                w.pollContinuous(input);
            } catch (Throwable t) {
                LauncherLog.err("[UiScreenBase] pollContinuous: " + t);
            }
        }

        if (!input.leftClicked) return;
        for (UiWidget w : widgets) {
            if (w.contains(input.mouseX, input.mouseY)) {
                try {
                    w.onClick();
                } catch (Throwable t) {
                    LauncherLog.err("[UiScreenBase] onClick: " + t);
                }
                return; // un seul widget cliqué par frame, le premier trouvé
            }
        }
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
