package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

/**
 * Bouton de fermeture d'un écran : un {@link UiIconButton} et sa croix. Fermer
 * n'est pas destructeur : il s'éclaircit au survol, il ne rougit pas.
 */
public final class UiCloseButton extends UiIconButton {

    public static final String ICON = UiIcon.named("multiply");

    public UiCloseButton(Runnable action) {
        super(ICON, action);
    }
}
