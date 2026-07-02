package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

/**
 * Port de PvP-Mod KeystrokesConfig/KeystrokesHud — sa propre carte, comme
 * dans la référence.
 *
 * {@code RENDERER} est STATIC (pas un champ d'instance passé par lambda
 * capturant {@code this}) : le renderer doit être construit et passé au
 * {@code super(...)} de {@link SingleHudModule} AVANT que le constructeur de
 * cette classe n'ait fini — javac interdit toute référence à {@code this}
 * (même via lambda) dans les arguments d'un appel super().
 */
public final class KeystrokesModule extends SingleHudModule {

    private static final Renderer RENDERER = new Renderer();

    @ConfigToggle(name = "Afficher la barre d'espace", category = "Éléments")
    public boolean showSpaceKey = true;

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", "Touches ZQSD/WASD + espace + CPS", false,
            new HudElement("keystrokes", "Keystrokes", HudAnchor.BOTTOM_LEFT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
    }

    @Override
    public void onConfigChanged() {
        RENDERER.showSpaceKey = showSpaceKey;
    }

    /**
     * Grille de touches ZQSD/WASD (lues dynamiquement — un joueur en ZQSD
     * voit ZQSD, pas "WASD" figé) + espace + compteurs CPS clic gauche/droit.
     *
     * CPS : lit l'état BRUT LWJGL (org.lwjgl.input.Mouse.isButtonDown) plutôt
     * que KeyBinding.isPressed() — cette dernière a une sémantique "à un seul
     * consommateur" (compteur interne décrémenté à chaque appel, déjà
     * consommé par la boucle d'attaque vanilla à 20/s) : l'appeler
     * nous-mêmes depuis un rendu à la fréquence des frames viderait ce
     * compteur avant que vanilla ne le lise, cassant les deux.
     */
    private static final class Renderer implements HudElement.CustomRenderer {
        private static final float BOX = 16f;
        private static final float GAP = 2f;
        // Rapprochées de la taille des touches WASD (16x16) — 38x24
        // (2.4x/1.5x plus grandes) faisait paraître les boîtes CPS
        // disproportionnées à côté des touches.
        private static final float CPS_W = 28f;
        private static final float CPS_H = 20f;
        private static final UiColor IDLE_BG = new UiColor(255, 255, 255, 30);
        private static final UiColor PRESSED_BG = new UiColor(255, 255, 255, 210);

        /** Mutable directement par KeystrokesModule.onConfigChanged(). */
        volatile boolean showSpaceKey = true;
        private final ArrayDeque<Long> leftClicks = new ArrayDeque<>();
        private final ArrayDeque<Long> rightClicks = new ArrayDeque<>();
        private boolean prevLeftDown, prevRightDown;

        /**
         * Espace occupé à scale=1 (CONTENU SEUL, la marge est ajoutée par le
         * moteur — voir HudElement.naturalSize()) — DOIT prendre la ligne la
         * plus LARGE des deux (grille WASD OU les 2 boîtes CPS côte à côte) :
         * les compter séparément sous-estimait la largeur réelle quand les
         * boîtes CPS sont plus larges que la grille (texte des boîtes CPS
         * débordant l'une sur l'autre, la boîte par défaut étant trop étroite).
         */
        private float naturalWidth() {
            float wasdRowW = 3 * BOX + 2 * GAP;
            float cpsRowW = 2 * CPS_W + GAP;
            return Math.max(wasdRowW, cpsRowW);
        }

        private float naturalHeight() {
            return 2 * BOX + 2 * GAP + (showSpaceKey ? BOX + GAP : 0) + CPS_H;
        }

        @Override
        public float[] naturalSize() {
            return new float[]{ naturalWidth(), naturalHeight() };
        }

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

                // w/h dérivent TOUJOURS de naturalSize()*scale (HudElement.setScale)
                // — aucun agrandissement automatique supplémentaire ici (l'appliquer
                // reviendrait à multiplier scale par lui-même, voir HudPanelRenderer).
                // x/y/w/h reçus sont DÉJÀ la zone de contenu (marge retirée par le
                // moteur, voir HudPanelRenderer.draw) — pas de padding à soustraire ici.
                float box = BOX * scale, gap = GAP * scale;
                float cpsW = CPS_W * scale, cpsH = CPS_H * scale;

                // Chaque ligne (grille WASD, barre ESPACE, boîtes CPS) est
                // CENTRÉE dans la largeur réellement disponible plutôt que
                // toujours alignée à gauche — nécessaire car les 2 boîtes CPS
                // côte à côte peuvent être plus larges que la grille WASD.
                float availableW = w;
                float wasdRowW = 3 * box + 2 * gap;
                float cpsRowW = 2 * cpsW + gap;
                float wasdX0 = x + (availableW - wasdRowW) / 2f;
                float cpsX0 = x + (availableW - cpsRowW) / 2f;

                float row1Y = y + h - box;
                float row2Y = row1Y - gap - box;

                drawKey(renderer, wasdX0 + box + gap, row1Y, box, box, keyLabel(forward), isDown(forward), scale, vpWidth, vpHeight);
                drawKey(renderer, wasdX0, row2Y, box, box, keyLabel(left), isDown(left), scale, vpWidth, vpHeight);
                drawKey(renderer, wasdX0 + box + gap, row2Y, box, box, keyLabel(back), isDown(back), scale, vpWidth, vpHeight);
                drawKey(renderer, wasdX0 + 2 * (box + gap), row2Y, box, box, keyLabel(right), isDown(right), scale, vpWidth, vpHeight);

                float nextY = row2Y - gap;
                if (showSpaceKey) {
                    nextY -= box;
                    drawKey(renderer, wasdX0, nextY, 3 * box + 2 * gap, box, "ESPACE", isDown(jump), scale, vpWidth, vpHeight);
                    nextY -= gap;
                }
                nextY -= cpsH;
                drawCpsBox(renderer, cpsX0, nextY, cpsW, cpsH, "LMB", leftClicks.size(), scale, vpWidth, vpHeight);
                drawCpsBox(renderer, cpsX0 + cpsW + gap, nextY, cpsW, cpsH, "RMB", rightClicks.size(), scale, vpWidth, vpHeight);
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
            float textScale = 0.32f * scale;
            float tw = renderer.textWidth(label, textScale);
            UiColor textColor = pressed ? new UiColor(10, 10, 10, 255) : UiTheme.TEXT_PRIMARY;
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 3f * scale, textColor, textScale, vpWidth, vpHeight);
        }

        /** Libellé (LMB/RMB) + compteur SUR 2 LIGNES SÉPARÉES — évite tout risque de débordement du texte hors de sa propre boîte. */
        private void drawCpsBox(UiRenderer renderer, float x, float y, float w, float h, String label, int cps, float scale, int vpWidth, int vpHeight) {
            renderer.drawRoundedRect(x, y, x + w, y + h, 3f, IDLE_BG, vpWidth, vpHeight);

            float labelScale = 0.28f * scale;
            float lw = renderer.textWidth(label, labelScale);
            renderer.drawText(label, x + (w - lw) / 2f, y + h * 0.62f, UiTheme.TEXT_SECONDARY, labelScale, vpWidth, vpHeight);

            String count = String.valueOf(cps);
            float countScale = 0.4f * scale;
            float cw = renderer.textWidth(count, countScale);
            renderer.drawText(count, x + (w - cw) / 2f, y + h * 0.2f, UiTheme.TEXT_PRIMARY, countScale, vpWidth, vpHeight);
        }
    }
}
