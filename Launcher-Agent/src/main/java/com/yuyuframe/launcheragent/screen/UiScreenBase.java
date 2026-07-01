package com.yuyuframe.launcheragent.screen;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.screen.ScreenHelper;
import com.yuyuframe.launcheragent.runtime.ui.UiDrawable;
import com.yuyuframe.launcheragent.runtime.ui.UiInputPoller;
import com.yuyuframe.launcheragent.runtime.ui.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.UiTheme;
import com.yuyuframe.launcheragent.runtime.ui.UiWidget;
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
            for (UiWidget w : widgets) {
                w.draw(renderer, mouseX, mouseY, screenWidth, screenHeight);
            }
        } catch (Throwable t) {
            LauncherLog.err("[UiScreenBase] uiDraw: " + t);
        }
    }

    /** Ferme cet écran — renvoie à lastScreen si fourni par la sous-classe (voir UiMainMenuScreen). */
    protected void closeTo(Object lastScreen) {
        ScreenHelper.navigate(this, lastScreen);
    }
}
