package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.KeyMappingAccessor261;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Options;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

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

    public boolean showSpaceKey = true;

    @Override
    protected void settings(SettingList s) {
        s.toggle("showSpaceKey", "Afficher la barre d'espace", "Éléments",
            () -> showSpaceKey, v -> showSpaceKey = v);
    }

    public KeystrokesModule() {
        super("keystrokes", "Keystrokes", "Touches ZQSD/WASD + espace + CPS", false,
            new HudElement("keystrokes", "Keystrokes", HudAnchor.BOTTOM_LEFT, 8f, 8f,
                (HudElement.CustomRenderer) RENDERER));
        iconUrl = icons8("keyboard");
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
        private static final UiColor ACCENT = new UiColor(100, 180, 255, 255);

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
                Object forward, left, back, right, jump;

                // Options par l'accessor Mixin (ClientData) puis champs
                // publics keyUp/keyDown/keyLeft/keyRight/keyJump (voir stub
                // Options) — zéro réflexion.
                //
                // Le repli réflexif multi-bracket a été supprimé le
                // 2026-08-27. Renommages à connaître pour un portage :
                // "forwardKey"/"leftKey"/… (1.8.9) → "keyForward"/"keyLeft"/…
                // (1.16.5), puis "keyForward"/"keyBack" → "keyUp"/"keyDown"
                // côté Mojang réel en 26.1 ("keyLeft"/"keyRight"/"keyJump"
                // coïncidant déjà).
                Options directOptions = ClientData.options();
                if (directOptions == null) return;
                forward = directOptions.keyUp;
                left    = directOptions.keyLeft;
                back    = directOptions.keyDown;
                right   = directOptions.keyRight;
                jump    = directOptions.keyJump;

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

                // FIX v402 ANNULÉ (mauvais diagnostic) : à l'époque, le texte
                // era E souffrait d'un bug d'axe Y (voir UiTextBlaze3D,
                // ensureProjectionBuffer) qui le faisait apparaître en miroir
                // par rapport aux rectangles (lesquels utilisent
                // drawRoundedRect, Y-UP, JAMAIS buggé) — l'ordre visuel
                // "compteurs en haut, W en bas" observé alors venait du TEXTE
                // mal positionné, pas des rectangles. Cet ordre-ci (row1 /
                // avance-W près de y+h = HAUT en Y-up, empilement DESCENDANT
                // jusqu'aux boîtes CPS près de y = BAS) était déjà correct
                // depuis le début. Restauré maintenant que le vrai bug (axe Y
                // du texte) est corrigé.
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




        /** {@code KeyMappingAccessor261#la$isDown()} (champ privé) — zéro réflexion. Repli supprimé le 2026-08-27 ; renommage à connaître : KeyBinding→KeyMapping, champ "pressed"→"isDown". */
        private boolean isDown(Object keyBinding) {
            if (!(keyBinding instanceof KeyMappingAccessor261)) return false;
            try {
                return ((KeyMappingAccessor261) keyBinding).la$isDown();
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * BUG TROUVÉ (audit modules, voir historique de session) : {@code
         * KeyBinding.code} (int direct) n'existe plus en 1.13+ — remplacé par
         * {@code KeyBinding.boundKey} (objet {@code InputUtil.Key}, voir
         * mappings 1.16.5 : champ {@code c I field_1665 code} appartient en
         * fait à {@code InputUtil.Key}, PAS à {@code KeyBinding} lui-même,
         * qui n'a que {@code Ldeo$a; f field_1654 boundKey}). Essaie d'abord
         * l'ancien champ direct (1.8.9, inchangé), sinon lit {@code
         * boundKey.getCode()}. Nommage : {@code org.lwjgl.input.Keyboard}
         * (LWJGL2) n'existe pas sous LWJGL3/GLFW — voir
         * {@code UiInputPollerModern#nameForKeyCode}.
         */
        private String keyLabel(Object keyBinding) {
            // KeyMappingAccessor261#la$key() (champ privé) +
            // InputConstants.Key.getValue() (méthode publique) — zéro
            // réflexion. Le repli multi-bracket a été supprimé le 2026-08-27 ;
            // renommages à connaître : KeyBinding.code (int) disparu en 1.13+,
            // puis champ "boundKey"→"key" (type déplacé vers
            // com.mojang.blaze3d.platform.InputConstants$Key) et méthode
            // "getCode"→"getValue" (InputConstants$Key n'a PLUS de getCode()).
            if (!(keyBinding instanceof KeyMappingAccessor261)) return "?";
            try {
                InputConstants.Key key = ((KeyMappingAccessor261) keyBinding).la$key();
                if (key == null) return "?";
                int code = key.getValue();
                String name = com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern.nameForKeyCode(code, keyBinding.getClass().getClassLoader());
                return name == null || name.isEmpty() ? "?" : name;
            } catch (Throwable t) {
                return "?";
            }
        }


        /**
         * BUG TROUVÉ (audit modules) : passait TOUJOURS par {@code
         * org.lwjgl.input.Mouse} (LWJGL2) — inexistant sous LWJGL3/GLFW
         * (1.13+), CPS toujours à 0 sur ces versions. Utilise désormais
         * l'état déjà pollé chaque frame par l'instance {@code
         * UiInputPollerModern} active (champs {@code leftDown}/{@code
         * rightDown} de la classe de base {@code UiInputPoller}) quand
         * disponible, sinon retombe sur LWJGL2 (1.8.9, inchangé).
         */
        private void trackClicks() {
            try {
                com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern modern =
                    com.yuyuframe.launcheragent.apigraphic.input.UiInputPollerModern.ACTIVE;
                boolean leftDown, rightDown;
                if (modern != null) {
                    leftDown = modern.leftDown;
                    rightDown = modern.rightDown;
                } else {
                    Class<?> mouse = McReflect.rawClass("org.lwjgl.input.Mouse");
                    Method isButtonDown = McReflect.rawMethod(mouse, "isButtonDown", int.class);
                    if (isButtonDown == null) return;
                    leftDown = (boolean) isButtonDown.invoke(null, 0);
                    rightDown = (boolean) isButtonDown.invoke(null, 1);
                }

                if (leftDown && !prevLeftDown) leftClicks.addLast(System.currentTimeMillis());
                prevLeftDown = leftDown;

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
            // drawRoundedRectHud (pas drawRoundedRect direct) : reste synchronisé
            // avec le texte différé d'une frame sur era E — voir sa javadoc.
            renderer.drawRoundedRectHud(x, y, x + w, y + h, 3f, pressed ? PRESSED_BG : IDLE_BG, vpWidth, vpHeight);
            float textScale = 0.32f * scale;
            float tw = renderer.textWidth(label, textScale);
            UiColor textColor = pressed ? new UiColor(10, 10, 10, 255) : UiTheme.TEXT_PRIMARY;
            renderer.drawText(label, x + (w - tw) / 2f, y + h / 2f - 3f * scale, textColor, textScale, vpWidth, vpHeight);
        }

        /** Libellé (LMB/RMB) + compteur SUR 2 LIGNES SÉPARÉES — évite tout risque de débordement du texte hors de sa propre boîte. */
        private void drawCpsBox(UiRenderer renderer, float x, float y, float w, float h, String label, int cps, float scale, int vpWidth, int vpHeight) {
            renderer.drawRoundedRectHud(x, y, x + w, y + h, 3f, IDLE_BG, vpWidth, vpHeight);

            float labelScale = 0.28f * scale;
            float lw = renderer.textWidth(label, labelScale);
            renderer.drawText(label, x + (w - lw) / 2f, y + h * 0.62f, ACCENT, labelScale, vpWidth, vpHeight);

            // 0.4 (même échelle que le label du dessus, en plus grand) faisait
            // largement déborder le compteur de la petite boîte CPS (28x20) —
            // ramené sous celle du label pour rester DANS la boîte.
            String count = String.valueOf(cps);
            float countScale = 0.24f * scale;
            float cw = renderer.textWidth(count, countScale);
            renderer.drawText(count, x + (w - cw) / 2f, y + h * 0.2f, UiTheme.TEXT_PRIMARY, countScale, vpWidth, vpHeight);
        }
    }
}
