package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Port de PvP-Mod CoordsConfig/CoordsHud — sa propre carte, comme dans la
 * référence, + ligne Biome (accesseur retrouvé : World.getBiome(BlockPos),
 * absent de la passe précédente) et direction regardée, façon capture de
 * référence fournie par l'utilisateur.
 *
 * Rendu personnalisé (voir HudElement.CustomRenderer) plutôt que ContentSource
 * simple : la direction regardée et le tiret décoratif s'alignent à DROITE
 * sur la même ligne que X/Y, ce que le modèle "une ligne de texte" de
 * ContentSource ne sait pas faire (toujours aligné à gauche).
 */
public final class CoordsModule extends SingleHudModule {
    public CoordsModule() {
        super("coords", "Coordonnées", "Affiche la position X/Y/Z du joueur", true,
            new HudElement("coords", "Coordonnées", HudAnchor.TOP_LEFT, 8f, 40f,
                (HudElement.CustomRenderer) new Renderer()));
    }

    private static final class Renderer implements HudElement.CustomRenderer {
        private static final float LINE_H = 16f;
        private static final float TEXT_SCALE = 0.4f;
        // Largeur du CONTENU seul (marge ajoutée par le moteur, voir HudElement.naturalSize).
        private static final float NATURAL_WIDTH = 140f;
        private static final UiColor BIOME_COLOR = new UiColor(120, 220, 140, 255);
        private static final UiColor ACCENT = new UiColor(100, 180, 255, 255);

        @Override
        public float[] naturalSize() {
            return new float[]{ NATURAL_WIDTH, 4 * LINE_H };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            float lineH = LINE_H * scale, textScale = TEXT_SCALE * scale;
            float rowX = x;
            float rightEdge = x + w;

            String xLine = "X: --", yLine = "Y: --", zLine = "Z: --", facing = null, biome = null;
            try {
                Object mc = McReflect.minecraftClient();
                if (mc != null) {
                    Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                    if (player != null) {
                        double px = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "x").getDouble(player);
                        double py = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "y").getDouble(player);
                        double pz = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "z").getDouble(player);
                        xLine = "X: " + (int) Math.floor(px);
                        yLine = "Y: " + (int) Math.floor(py);
                        zLine = "Z: " + (int) Math.floor(pz);

                        float yaw = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "yaw").getFloat(player);
                        facing = facingLetter(yaw);
                        biome = biomeName(player, (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
                    }
                }
            } catch (Throwable ignored) {}

            float ty = y + h - textScale * 24f; // première ligne, en haut de la zone de contenu
            renderer.drawText(xLine, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            // "--" (2 tirets ASCII), PAS le caractère tiret cadratin "–"
            // (U+2013) : hors de la plage de glyphes générée par UiFont (32-126
            // + 160-255 + Œ/œ, voir UiFont.java) — retombait sur le glyphe de
            // repli, affiché comme un point d'interrogation.
            drawRightAligned(renderer, "--", rightEdge, ty, ACCENT, textScale, vpWidth, vpHeight); // tiret décoratif
            ty -= lineH;

            renderer.drawText(yLine, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            // Valeur d'orientation (N/S/E/O) EN ACCENT — contrairement à FPS/ms
            // où seule l'ÉTIQUETTE (FPS/ms) prend la couleur, jamais la VALEUR
            // numérique : ici c'est l'inverse, c'est justement la valeur
            // (la lettre de direction) qui doit ressortir.
            if (facing != null) drawRightAligned(renderer, facing, rightEdge, ty, ACCENT, textScale, vpWidth, vpHeight);
            ty -= lineH;

            renderer.drawText(zLine, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            ty -= lineH;

            String biomeLabel = "Biome: ";
            renderer.drawText(biomeLabel, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            if (biome != null) {
                float labelW = renderer.textWidth(biomeLabel, textScale);
                renderer.drawText(biome, rowX + labelW, ty, BIOME_COLOR, textScale, vpWidth, vpHeight);
            }
        }

        private void drawRightAligned(UiRenderer renderer, String text, float rightEdge, float ty, UiColor color, float scale, int vpW, int vpH) {
            float tw = renderer.textWidth(text, scale);
            renderer.drawText(text, rightEdge - tw, ty, color, scale, vpW, vpH);
        }

        /** Yaw vanilla : 0°=Sud, 90°=Ouest, 180°=Nord, 270°=Est (sens horaire vu du dessus) — 4 secteurs de 90°. */
        private String facingLetter(float yaw) {
            float normalized = ((yaw % 360f) + 360f) % 360f;
            if (normalized >= 315f || normalized < 45f) return "S";
            if (normalized < 135f) return "O";
            if (normalized < 225f) return "N";
            return "E";
        }

        private static boolean DIAG_LOGGED = false;
        private static Method cachedGetBiome;
        private static boolean getBiomeResolveAttempted;

        /**
         * World.getBiome(BlockPos) — le nom Yarn "getBiome" ne se résolvait PAS
         * via McReflect.oneArgMethod (diagnostic : getObfMethodName renvoyait
         * le nom Yarn tel quel, signe que la source de mappings réellement
         * chargée à l'exécution (JAR Yarn auto-détecté ou argument yarn=, voir
         * IsolatedBootstrap.loadYarnMappings) n'indexe pas cette entrée-là,
         * MÊME SI mappings/mappings-1.8.9.tiny du repo la contient bien —
         * cette source n'est pas forcément celle réellement chargée). La
         * méthode existe pourtant bel et bien à l'exécution (confirmé par le
         * diagnostic précédent : "adm.b(cj)" listé parmi les candidats à 1
         * paramètre) — on la retrouve donc SANS dépendre du nom Yarn : seule
         * méthode à 1 paramètre BlockPos dont le type de retour est Biome, ce
         * qui l'identifie de façon unique parmi les ~30 candidats "cj" de World.
         */
        private Method resolveGetBiome(Object world, Class<?> blockPosClass, Class<?> biomeClass) {
            if (cachedGetBiome != null) return cachedGetBiome;
            if (getBiomeResolveAttempted) return null;
            getBiomeResolveAttempted = true;

            Method m = McReflect.oneArgMethod(world.getClass(), "net/minecraft/world/World", "getBiome", blockPosClass);
            if (m == null) {
                Class<?> c = world.getClass();
                outer:
                while (c != null) {
                    for (Method cand : c.getDeclaredMethods()) {
                        if (cand.getParameterCount() == 1
                                && cand.getParameterTypes()[0].isAssignableFrom(blockPosClass)
                                && biomeClass.isAssignableFrom(cand.getReturnType())) {
                            cand.setAccessible(true);
                            m = cand;
                            break outer;
                        }
                    }
                    c = c.getSuperclass();
                }
                diag(m != null
                    ? "getBiome retrouvé par repli type-retour : " + m
                    : "getBiome introuvable même par repli type-retour (world class=" + world.getClass() + ")");
            }
            cachedGetBiome = m;
            return m;
        }

        private String biomeName(Object player, int bx, int by, int bz) {
            try {
                Object world = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "world").get(player);
                if (world == null) { diag("world == null"); return null; }

                Class<?> blockPosClass = McReflect.yarnClass("net/minecraft/util/math/BlockPos");
                if (blockPosClass == null) { diag("blockPosClass == null"); return null; }
                Class<?> biomeClass = McReflect.yarnClass("net/minecraft/world/biome/Biome");
                if (biomeClass == null) { diag("biomeClass == null"); return null; }
                Constructor<?> ctor = blockPosClass.getConstructor(int.class, int.class, int.class);
                Object pos = ctor.newInstance(bx, by, bz);

                Method getBiome = resolveGetBiome(world, blockPosClass, biomeClass);
                if (getBiome == null) return null;
                Object biome = getBiome.invoke(world, pos);
                if (biome == null) { diag("biome == null"); return null; }

                Field nameField = McReflect.field(biome.getClass(), "net/minecraft/world/biome/Biome", "name");
                if (nameField == null) { diag("name field == null (biome class=" + biome.getClass() + ")"); return null; }
                Object name = nameField.get(biome);
                diag("OK, name=" + name);
                return name == null ? null : name.toString();
            } catch (Throwable t) {
                diag("exception: " + t);
                return null;
            }
        }

        private void diag(String msg) {
            if (DIAG_LOGGED) return;
            DIAG_LOGGED = true;
            com.yuyuframe.launcheragent.runtime.log.LauncherLog.info("[CoordsModule] biomeName diag: " + msg);
        }
    }
}
