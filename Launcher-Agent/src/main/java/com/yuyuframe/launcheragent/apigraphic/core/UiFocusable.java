package com.yuyuframe.launcheragent.apigraphic.core;

/**
 * Widget qui peut recevoir le focus clavier (roadmap Phase 5.6, navigation
 * Tab/Shift+Tab — voir {@code UiScreenBase#dispatchTab}). Actuellement
 * implémenté seulement par {@code UiTextField} (seul type de widget
 * "focusable" existant dans ce moteur) — un futur widget interactif
 * (dropdown, slider au clavier...) l'implémente pour rejoindre le cycle Tab
 * sans aucun changement dans {@code UiScreenBase}.
 */
public interface UiFocusable {
    boolean focused();
    void setFocused(boolean focused);
}
