package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
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
     * Constructeur no-arg de Screen (PAS Screen(Component title)) — ce dernier
     * n'existe pas forcément sur toutes les versions (ex: absent en 1.8.9,
     * observé via NoSuchMethodError: axu.<init>(Leu;)V lors du premier test en
     * jeu). Notre écran ne s'appuie de toute façon jamais sur le rendu de
     * titre intégré de Screen — {@code title} n'est conservé ici que pour un
     * usage éventuel (logs, debug), jamais transmis à la superclasse.
     */
    protected UiScreenBase(String title) {
        super();
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
            UiRenderer renderer = UiRenderer.get(this.getClass().getClassLoader());
            renderer.drawRoundedRect(0, 0, screenWidth, screenHeight, 0, UiTheme.OVERLAY_BG, screenWidth, screenHeight);
            String hoveredTooltip = null;
            for (UiWidget w : widgets) {
                w.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
                if (w.tooltip != null && w.contains(mouseX, mouseY)) hoveredTooltip = w.tooltip;
            }
            if (hoveredTooltip != null) UiTooltip.draw(renderer, hoveredTooltip, mouseX, mouseY, screenWidth, screenHeight);
        } catch (Throwable t) {
            LauncherLog.err("[UiScreenBase] uiDraw: " + t);
        }
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
