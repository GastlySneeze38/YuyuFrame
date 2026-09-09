package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

import java.lang.reflect.Constructor;
import java.util.Optional;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

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
        iconUrl = icons8("map-marker");
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
                // Joueur, position et yaw par les ACCESSORS Mixin (PlayerData) —
                // zéro réflexion. Ce bloc résolvait auparavant le joueur
                // lui-même (accès direct + repli réflexif multi-bracket) ;
                // cette résolution n'existe plus qu'à un seul endroit.
                if (PlayerData.inGame()) {
                    double[] pos = PlayerData.position();
                    double px = pos[0], py = pos[1], pz = pos[2];
                    xLine = "X: " + (int) Math.floor(px);
                    yLine = "Y: " + (int) Math.floor(py);
                    zLine = "Z: " + (int) Math.floor(pz);

                    facing = facingLetter(PlayerData.yaw());
                    biome = cachedBiomeName((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
                }
            } catch (Throwable t) {
                // BUG TROUVÉ (1.20.4, test utilisateur) : ce catch était
                // silencieux ("catch (Throwable ignored) {}") — si playerYaw
                // (ou n'importe quelle ligne AVANT biome dans ce bloc) lève,
                // ni l'orientation NI le biome ne s'affichaient, et RIEN
                // n'apparaissait dans les logs pour l'expliquer (contrairement
                // à registryBiomeName/biomeName plus bas, qui ont leur propre
                // diagnostic MAIS ne sont jamais atteints si l'exception vient
                // d'avant). Log une seule fois par session pour ne plus
                // jamais reproduire ce trou noir de diagnostic.
                drawExceptionDiag(t);
            }

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

        /**
         * Dernier biome résolu, mémorisé par COORDONNÉES DE BLOC.
         *
         * <p>AUDIT PERF : {@link #biomeName} était appelé à CHAQUE FRAME, et
         * ce n'est pas une simple lecture de champ — c'est une chaîne complète
         * de résolution ({@code BiomeManager} -> construction réflexive d'un
         * {@code BlockPos} -> {@code Holder} -> {@code Optional} -> {@code
         * Identifier} -> {@code getPath} -> découpage/{@code StringBuilder} de
         * mise en forme), soit le module HUD le plus coûteux du lot. Or le
         * biome ne change qu'en franchissant une frontière : le mémoriser par
         * bloc le fait passer de ~60-200 résolutions/seconde à quelques-unes.
         *
         * <p>Clé = coordonnées ENTIÈRES (pas la position flottante) : c'est la
         * granularité réelle de la requête, donc aucune péremption possible —
         * le cache ne peut pas renvoyer le biome d'un autre bloc.
         *
         * <p>{@code cacheValid} distinct d'un test {@code == null} : un échec
         * de résolution renvoie légitimement {@code null}, et le retester à
         * chaque frame relancerait précisément le chemin le plus lourd.
         */
        private static int cacheBx = Integer.MIN_VALUE, cacheBy, cacheBz;
        private static boolean biomeCacheValid;
        private static String cachedBiome;

        private String cachedBiomeName(int bx, int by, int bz) {
            if (!biomeCacheValid || bx != cacheBx || by != cacheBy || bz != cacheBz) {
                cacheBx = bx; cacheBy = by; cacheBz = bz;
                cachedBiome = biomeDirect(bx, by, bz);
                biomeCacheValid = true;
            }
            return cachedBiome;
        }



        private static boolean DRAW_EXC_LOGGED = false;

        private void drawExceptionDiag(Throwable t) {
            if (DRAW_EXC_LOGGED) return;
            DRAW_EXC_LOGGED = true;
            com.yuyuframe.launcheragent.base.log.LauncherLog.err("[CoordsModule] draw() exception: " + t);
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



        /**
         * Monde par l'accessor Mixin ({@code PlayerData}) → {@code
         * getBiomeManager()} → {@code getBiome(BlockPos)} → {@code
         * Holder<Biome>} → {@code unwrapKey()} → {@code identifier()}.
         *
         * <p>BUG TROUVÉ (2026-08-27, signalé en jeu) : les deux derniers
         * maillons s'appelaient {@code getKey()} et {@code getValue()}, noms
         * repris de l'ancien chemin réflexif et jamais vérifiés pour 26.1.2 —
         * ils n'existent pas. Le {@code NoSuchMethodError} tombait dans le
         * {@code catch (Throwable)} muet ci-dessous, donc AUCUN log : le
         * biome ne s'affichait que grâce au repli réflexif, et sa suppression
         * a rendu la panne visible. Vrais noms lus dans {@code Holder.class}
         * et {@code ResourceKey.class} du jar client 26.1.2.
         */
        private String biomeDirect(int bx, int by, int bz) {
            try {
                ClientLevel level = PlayerData.level();
                if (level == null) return null;
                BiomeManager biomeManager = level.getBiomeManager();
                if (biomeManager == null) return null;
                Holder<Biome> holder = biomeManager.getBiome(new BlockPos(bx, by, bz));
                if (holder == null) return null;
                Optional<ResourceKey<Biome>> keyOpt = holder.unwrapKey();
                if (keyOpt == null || !keyOpt.isPresent()) return null;
                Identifier id = keyOpt.get().identifier();
                return id != null ? prettifyBiomePath(id.getPath()) : null;
            } catch (Throwable t) {
                // Ce catch était MUET, d'où le bug ci-dessus resté invisible.
                // Une fois par session : une erreur de signature de stub ne
                // doit plus jamais se cacher derrière un biome vide.
                if (!BIOME_EXC_LOGGED) {
                    BIOME_EXC_LOGGED = true;
                    com.yuyuframe.launcheragent.base.log.LauncherLog.err("[CoordsModule] biomeDirect: " + t);
                }
                return null;
            }
        }

        private static boolean BIOME_EXC_LOGGED = false;






        private String prettifyBiomePath(String path) {
            if (path == null) return null;
            StringBuilder sb = new StringBuilder();
            for (String word : path.split("_")) {
                if (word.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return sb.length() == 0 ? path : sb.toString();
        }


    }
}
