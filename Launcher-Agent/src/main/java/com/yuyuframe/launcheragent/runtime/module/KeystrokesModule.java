package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.apimixin.v26_1.core.KeyMappingAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.MinecraftAccessor261;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.apigraphic.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.lang.reflect.Field;
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

                // 26.1.2 sans réflexion — Options.keyUp/keyDown/keyLeft/keyRight/
                // keyJump (champs publics, voir stub Options) via
                // MinecraftAccessor261#la$options() (voir directOptions() plus
                // bas) — architecture apimixin, pas de cast/champ vanilla brut
                // dans les modules (2026-08-25, §19/§20).
                Options directOptions = directOptions();
                if (directOptions != null) {
                    forward = directOptions.keyUp;
                    left    = directOptions.keyLeft;
                    back    = directOptions.keyDown;
                    right   = directOptions.keyRight;
                    jump    = directOptions.keyJump;
                } else {
                    Object mc = McReflect.minecraftClient();
                    if (mc == null) return;
                    Object options = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "options").get(mc);
                    if (options == null) return;

                    // BUG TROUVÉ (audit modules, voir historique de session) :
                    // noms de champ "forwardKey"/"leftKey"/etc. (1.8.9) inversés
                    // en 1.16.5 — "keyForward"/"keyLeft"/etc. Essaie les deux.
                    // 26.1+ : "keyForward"/"keyBack" (Yarn 1.16.5+) sont eux-mêmes
                    // RENOMMÉS "keyUp"/"keyDown" côté Mojang réel (vérifié par javap
                    // sur Options.class du vrai jar 26.1.2) — "keyLeft"/"keyRight"/
                    // "keyJump" coïncident déjà, aucun repli nécessaire pour ceux-là.
                    forward = optionsFieldEither(options, "forwardKey", "keyForward", "keyUp");
                    left    = optionsFieldEither(options, "leftKey", "keyLeft", null);
                    back    = optionsFieldEither(options, "backKey", "keyBack", "keyDown");
                    right   = optionsFieldEither(options, "rightKey", "keyRight", null);
                    jump    = optionsFieldEither(options, "jumpKey", "keyJump", null);
                }

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

        /**
         * 26.1.2 sans réflexion — {@code MinecraftAccessor261#la$options()}
         * (architecture apimixin, voir sa javadoc). Try/catch dédié : {@code
         * Minecraft.getInstance()} référence le nom RÉEL, inexistant tel quel
         * sur les autres brackets (obfusqués) — {@code null} déclenche le
         * repli réflexion multi-bracket côté appelant.
         */
        private Options directOptions() {
            try {
                Object mc = Minecraft.getInstance();
                if (mc instanceof MinecraftAccessor261) return ((MinecraftAccessor261) mc).la$options();
            } catch (Throwable ignored) {}
            return null;
        }

        private Object optionsField(Object options, String yarnField, String realFieldFallback) {
            try {
                Field f = realFieldFallback != null
                    ? McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", yarnField, realFieldFallback)
                    : McReflect.field(options.getClass(), "net/minecraft/client/option/GameOptions", yarnField);
                return f == null ? null : f.get(options);
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * Essaie {@code oldName} (1.8.9) puis {@code newName} (1.13+, souvent
         * inchangé jusqu'en 26.1.2 aussi) puis, si fourni, {@code
         * realFieldFallback} (nom réel Mojang, requis quand 26.1.2 a ENCORE
         * renommé le champ par rapport au nom Yarn "named" — ex: {@code
         * keyForward}→{@code keyUp}, {@code keyBack}→{@code keyDown}, vérifiés
         * par javap sur le jar client 26.1.2 réel).
         */
        private Object optionsFieldEither(Object options, String oldName, String newName, String realFieldFallback) {
            if (com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry.hasFieldMapping("net/minecraft/client/option/GameOptions", oldName)) {
                Object v = optionsField(options, oldName, null);
                if (v != null) return v;
            }
            return optionsField(options, newName, realFieldFallback);
        }

        private boolean isDown(Object keyBinding) {
            if (keyBinding == null) return false;
            // 26.1.2 sans réflexion — KeyMappingAccessor261#la$isDown() (champ privé).
            if (keyBinding instanceof KeyMappingAccessor261) {
                try {
                    return ((KeyMappingAccessor261) keyBinding).la$isDown();
                } catch (Throwable ignored) {}
            }
            try {
                // 26.1+ : KeyBinding→KeyMapping, champ "pressed"→"isDown" (vérifié javap).
                return McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", "pressed", "isDown").getBoolean(keyBinding);
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
            if (keyBinding == null) return "?";
            // 26.1.2 sans réflexion — KeyMappingAccessor261#la$key() (champ
            // privé) + InputConstants.Key.getValue() (méthode publique).
            if (keyBinding instanceof KeyMappingAccessor261) {
                try {
                    InputConstants.Key key = ((KeyMappingAccessor261) keyBinding).la$key();
                    if (key != null) {
                        int code = key.getValue();
                        String name = com.yuyuframe.launcheragent.apigraphic.UiInputPollerModern.nameForKeyCode(code, keyBinding.getClass().getClassLoader());
                        return name == null || name.isEmpty() ? "?" : name;
                    }
                } catch (Throwable ignored) {}
            }
            try {
                Integer directCode = tryGetInt(keyBinding, "code");
                if (directCode != null) {
                    int code = directCode;
                    if (code < 0) return "M" + (-code - 100);
                    Class<?> keyboard = McReflect.rawClass("org.lwjgl.input.Keyboard");
                    Method getKeyName = McReflect.rawMethod(keyboard, "getKeyName", int.class);
                    if (getKeyName == null) return "?";
                    String name = (String) getKeyName.invoke(null, code);
                    return name == null || name.isEmpty() ? "?" : name;
                }

                // 26.1+ : champ "boundKey"→"key" (type déplacé vers
                // com.mojang.blaze3d.platform.InputConstants$Key), méthode
                // "getCode"→"getValue" (vérifiés par javap — InputConstants$Key
                // n'a PLUS de getCode() du tout, seulement getValue()).
                Object boundKey = McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", "boundKey", "key").get(keyBinding);
                if (boundKey == null) return "?";
                Method getCode = McReflect.noArgMethod(boundKey.getClass(), "net/minecraft/client/util/InputUtil$Key", "getCode", "getValue");
                if (getCode == null) return "?";
                int code = (int) getCode.invoke(boundKey);
                String name = com.yuyuframe.launcheragent.apigraphic.UiInputPollerModern.nameForKeyCode(code, keyBinding.getClass().getClassLoader());
                return name == null || name.isEmpty() ? "?" : name;
            } catch (Throwable t) {
                return "?";
            }
        }

        /**
         * {@code null} si le champ n'existe pas du tout (pas juste une
         * valeur négative) — distingue "pas ce champ" de "valeur -1".
         * Vérifie D'ABORD que le mapping Yarn existe vraiment (voir
         * historique de session, même piège que CoordsModule.tryField) :
         * sinon getObfFieldName retombe sur "code" tel quel, qui peut par
         * coïncidence matcher un vrai champ obfusqué sans rapport (1-2
         * lettres, faux positif silencieux).
         */
        private Integer tryGetInt(Object keyBinding, String yarnField) {
            if (!com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry.hasFieldMapping("net/minecraft/client/option/KeyBinding", yarnField)) {
                return null;
            }
            try {
                java.lang.reflect.Field f = McReflect.field(keyBinding.getClass(), "net/minecraft/client/option/KeyBinding", yarnField);
                if (f == null) return null;
                return f.getInt(keyBinding);
            } catch (Throwable t) {
                return null;
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
                com.yuyuframe.launcheragent.apigraphic.UiInputPollerModern modern =
                    com.yuyuframe.launcheragent.apigraphic.UiInputPollerModern.ACTIVE;
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
