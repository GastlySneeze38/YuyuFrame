package com.yuyuframe.launcheragent.runtime.hud;

import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

/**
 * Grille de touches ZQSD/WASD (lues dynamiquement — un joueur en ZQSD voit
 * ZQSD, pas "WASD" figé) + espace + compteurs CPS clic gauche/droit — port de
 * PvP-Mod KeystrokesHud.
 *
 * CPS : lit l'état BRUT LWJGL ({@code org.lwjgl.input.Mouse.isButtonDown})
 * plutôt que {@code KeyBinding.isPressed()} — cette dernière a une sémantique
 * "à un seul consommateur" (compteur interne décrémenté à chaque appel, déjà
 * consommé par la boucle d'attaque vanilla à 20/s) : l'appeler nous-mêmes
 * depuis un rendu à la fréquence des frames viderait ce compteur avant que
 * vanilla ne le lise, cassant les deux. Même piège déjà documenté et évité
 * dans le mod de référence.
 *
 * Rendu personnalisé (voir HudElement.CustomRenderer) : une grille de boîtes
 * ne rentre pas dans le modèle "une ligne de texte" de ContentSource.
 */
public final class KeystrokesHudRenderer implements HudElement.CustomRenderer {

    private static final float BOX = 16f;
    private static final float GAP = 2f;
    private static final float CPS_W = 32f;
    private static final float CPS_H = 22f;
    private static final float PADDING = 8f;
    private static final UiColor IDLE_BG = new UiColor(255, 255, 255, 30);
    private static final UiColor PRESSED_BG = new UiColor(255, 255, 255, 210);

    /** Mutable directement par KeystrokesModule.onConfigChanged() — pas de BooleanSupplier capturant `this` : ce renderer est construit AVANT que le module qui le possède ait fini son propre constructeur (voir super(...) dans KeystrokesModule). */
    public volatile boolean showSpaceKey = true;
    private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
    private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
    private boolean prevLeftDown, prevRightDown;

    @Override
    public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
            if (options == null) return;

            Object forward = optionsField(options, "forwardKey");
            Object left    = optionsField(options, "leftKey");
            Object back    = optionsField(options, "backKey");
            Object right   = optionsField(options, "rightKey");
            Object jump    = optionsField(options, "jumpKey");

            trackClicks();

            float box = BOX * scale, gap = GAP * scale, padding = PADDING * scale;
            float cpsW = CPS_W * scale, cpsH = CPS_H * scale;

            float x0 = x + padding;
            float row1Y = y + h - padding - box;
            float row2Y = row1Y - gap - box;

            drawKey(renderer, x0 + box + gap, row1Y, box, box, keyLabel(forward), isDown(forward), scale, vpWidth, vpHeight);
            drawKey(renderer, x0, row2Y, box, box, keyLabel(left), isDown(left), scale, vpWidth, vpHeight);
            drawKey(renderer, x0 + box + gap, row2Y, box, box, keyLabel(back), isDown(back), scale, vpWidth, vpHeight);
            drawKey(renderer, x0 + 2 * (box + gap), row2Y, box, box, keyLabel(right), isDown(right), scale, vpWidth, vpHeight);

            float nextY = row2Y - gap;
            if (showSpaceKey) {
                nextY -= box;
                drawKey(renderer, x0, nextY, 3 * box + 2 * gap, box, "ESPACE", isDown(jump), scale, vpWidth, vpHeight);
                nextY -= gap;
            }
            nextY -= cpsH;
            drawCpsBox(renderer, x0, nextY, cpsW, cpsH, "LMB", leftClicks.size(), scale, vpWidth, vpHeight);
            drawCpsBox(renderer, x0 + cpsW + gap, nextY, cpsW, cpsH, "RMB", rightClicks.size(), scale, vpWidth, vpHeight);
        } catch (Throwable ignored) {}
    }

    private Object optionsField(Object options, String yarnField) {
        try {
            return McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", yarnField).get(options);
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isDown(Object keyBinding) {
        if (keyBinding == null) return false;
        try {
            return McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", "pressed").getBoolean(keyBinding);
        } catch (Throwable t) {
            return false;
        }
    }

    private String keyLabel(Object keyBinding) {
        if (keyBinding == null) return "?";
        try {
            int code = McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", "code").getInt(keyBinding);
            if (code < 0) return "M" + (-code - 100);
            Class<?> keyboard = McReflect.rawClass("org.lwjgl.input.Keyboard");
            Method getKeyName = McReflect.rawMethod(keyboard, "getKeyName", int.class);
            if (getKeyName == null) return "?";
            String name = (String) getKeyName.invoke(null, code);
            return name == null || name.isEmpty() ? "?" : name;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void trackClicks() {
        try {
            Class<?> mouse = McReflect.rawClass("org.lwjgl.input.Mouse");
            Method isButtonDown = McReflect.rawMethod(mouse, "isButtonDown", int.class);
            if (isButtonDown == null) return;

            boolean leftDown = (boolean) isButtonDown.invoke(null, 0);
            if (leftDown && !prevLeftDown) leftClicks.addLast(System.currentTimeMillis());
            prevLeftDown = leftDown;

            boolean rightDown = (boolean) isButtonDown.invoke(null, 1);
            if (rightDown && !prevRightDown) rightClicks.addLast(System.currentTimeMillis());
            prevRightDown = rightDown;
        } catch (Throwable ignored) {}

        long now = System.currentTimeMillis();
        trimOld(leftClicks, now);
        trimOld(rightClicks, now);
    }

    private void trimOld(ArrayDeque<Long> store, long now) {
        while (!store.isEmpty() && now - store.peekFirst() > 1000) store.pollFirst();
    }

    private void drawKey(UiRenderer renderer, float x, float y, float w, float h, String label, boolean pressed, float scale, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, 3f, pressed ? PRESSED_BG : IDLE_BG, vpWidth, vpHeight);
        float textScale = 0.3f * scale;
        float tw = renderer.textWidth(label, textScale);
        UiColor textColor = pressed ? new UiColor(10, 10, 10, 255) : UiTheme.TEXT_PRIMARY;
        renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 3f * scale, textColor, textScale, vpWidth, vpHeight);
    }

    private void drawCpsBox(UiRenderer renderer, float x, float y, float w, float h, String label, int cps, float scale, int vpWidth, int vpHeight) {
        renderer.drawRoundedRect(x, y, x + w, y + h, 3f, IDLE_BG, vpWidth, vpHeight);
        String text = cps + " " + label;
        float textScale = 0.3f * scale;
        float tw = renderer.textWidth(text, textScale);
        renderer.drawText(text, x + (w - tw) / 2f, y + h / 2f - 3f * scale, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
    }
}
