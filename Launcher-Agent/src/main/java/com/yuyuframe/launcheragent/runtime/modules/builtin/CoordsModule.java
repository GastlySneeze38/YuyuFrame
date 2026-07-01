package com.yuyuframe.launcheragent.runtime.modules.builtin;

import com.yuyuframe.launcheragent.runtime.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiColor;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;

import java.lang.reflect.Constructor;
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
        private static final float PADDING = 5f;
        private static final float LINE_H = 16f;
        private static final float TEXT_SCALE = 0.4f;
        private static final float NATURAL_WIDTH = 150f;
        private static final UiColor BIOME_COLOR = new UiColor(120, 220, 140, 255);

        @Override
        public float[] naturalSize() {
            return new float[]{ NATURAL_WIDTH, 4 * LINE_H + 2 * PADDING };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            float padding = PADDING * scale, lineH = LINE_H * scale, textScale = TEXT_SCALE * scale;
            float rowX = x + padding;
            float rightEdge = x + w - padding;

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

            float ty = y + h - padding - textScale * 24f; // première ligne, sous le padding haut
            renderer.drawText(xLine, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            drawRightAligned(renderer, "–", rightEdge, ty, UiTheme.TEXT_MUTED, textScale, vpWidth, vpHeight); // tiret décoratif
            ty -= lineH;

            renderer.drawText(yLine, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            if (facing != null) drawRightAligned(renderer, facing, rightEdge, ty, UiTheme.TEXT_MUTED, textScale, vpWidth, vpHeight);
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

        /** World.getBiome(BlockPos) — accesseur retrouvé dans mappings-1.8.9.tiny (absent de la passe précédente, qui avait conclu à tort qu'aucun chemin n'existait). */
        private String biomeName(Object player, int bx, int by, int bz) {
            try {
                Object world = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "world").get(player);
                if (world == null) return null;

                Class<?> blockPosClass = McReflect.yarnClass("net/minecraft/util/math/BlockPos");
                if (blockPosClass == null) return null;
                Constructor<?> ctor = blockPosClass.getConstructor(int.class, int.class, int.class);
                Object pos = ctor.newInstance(bx, by, bz);

                Method getBiome = McReflect.oneArgMethod(world.getClass(), "net/minecraft/world/World", "getBiome", blockPosClass);
                if (getBiome == null) return null;
                Object biome = getBiome.invoke(world, pos);
                if (biome == null) return null;

                Object name = McReflect.field(biome.getClass(), "net/minecraft/world/biome/Biome", "name").get(biome);
                return name == null ? null : name.toString();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
