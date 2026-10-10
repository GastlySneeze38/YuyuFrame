package com.yuyuframe.launcheragent.runtime.module.hud;

import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;

import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.SingleHudModule;
import com.yuyuframe.launcheragent.runtime.game.LevelData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Position, orientation et biome du joueur — trois éléments que le joueur
 * garde ou retire un par un, la mise en page suivant ce qui reste :
 *
 * <ul>
 *   <li><b>Coordonnées</b> : trois lignes X/Y/Z. L'orientation, si elle est
 *       gardée, se loge à droite de la ligne Y, sous ses deux tirets ; le
 *       sens de marche suit chaque valeur, entre parenthèses — « (+) » vert
 *       si la coordonnée est en train d'augmenter, « (-) » rouge si elle
 *       diminue, rien à l'arrêt. Mesuré sur le déplacement réel, pas sur le
 *       regard : on peut reculer.</li>
 *   <li><b>Orientation seule</b> (sans les coordonnées) : une règle graduée
 *       qui défile avec le regard, les points cardinaux dessous, de longueur
 *       réglable.</li>
 *   <li><b>Biome</b> : toujours la dernière ligne, sous ce qui reste.</li>
 * </ul>
 *
 * Tout retirer n'aurait pas de sens (la carte du module sert à ça) : les
 * coordonnées sont alors affichées.
 *
 * <p>Rendu personnalisé (voir HudElement.CustomRenderer) plutôt que
 * ContentSource simple : la colonne de droite et la règle ne tiennent pas
 * dans le modèle « une ligne de texte alignée à gauche ».
 */
public final class CoordsModule extends SingleHudModule {

    public boolean showCoords = true;
    public boolean showFacing = true;
    public boolean showBiome = true;
    /** « + » / « - » à droite de X et Z : le sens dans lequel on avance sur chaque axe. */
    public boolean showDirection = true;
    /** Longueur de la règle graduée (orientation sans coordonnées) : plus longue, elle montre plus de degrés. */
    public float rulerLength = 140f;

    @Override
    protected void settings(SettingList s) {
        s.toggle("showCoords", "Afficher les coordonnées",
            "Les trois lignes X, Y et Z.",
            "Réglages", null, () -> showCoords, v -> showCoords = v);
        s.toggle("showDirection", "Afficher le sens de marche",
            "Un (+) vert ou un (-) rouge à côté de chaque valeur pendant le déplacement : la coordonnée augmente ou diminue.",
            "Réglages", null, () -> showDirection, v -> showDirection = v);
        s.toggle("showFacing", "Afficher l'orientation",
            "Le point cardinal regardé. Sans les coordonnées, il devient une règle graduée qui défile avec le regard.",
            "Réglages", null, () -> showFacing, v -> showFacing = v);
        s.slider("rulerLength", "Longueur de la règle",
            "La largeur de la règle graduée. Plus longue, elle montre une plus grande part de l'horizon.",
            "Réglages", 100f, 320f, 10f, () -> showFacing && !showCoords,
            () -> rulerLength, v -> rulerLength = v);
        s.toggle("showBiome", "Afficher le biome",
            "Le biome dans lequel se trouve le joueur, sur la dernière ligne.",
            "Réglages", null, () -> showBiome, v -> showBiome = v);
    }

    public CoordsModule() {
        this(new Renderer());
    }

    private CoordsModule(Renderer renderer) {
        super("coords", "Coordonnées", "Affiche la position, l'orientation et le biome du joueur", true,
            new HudElement("coords", "Coordonnées", HudAnchor.TOP_LEFT, 8f, 40f,
                (HudElement.CustomRenderer) renderer));
        renderer.module = this;
        iconUrl = icons8("map-marker");
    }

    private static final class Renderer implements HudElement.CustomRenderer {
        private static final float LINE_H = 16f;
        private static final float TEXT_SCALE = 0.4f;
        // Largeurs du CONTENU seul (marge ajoutée par le moteur, voir HudElement.naturalSize).
        private static final float WIDTH_FULL = 140f;
        /** Les trois lignes X/Y/Z seules, sans colonne de droite ni biome : rien ne justifie la pleine largeur. */
        private static final float WIDTH_COORDS_ONLY = 90f;
        /** Hauteur de la règle graduée : graduations au-dessus, points cardinaux dessous. */
        private static final float RULER_H = 2f * LINE_H;
        /** Degrés visibles d'un bord à l'autre de la règle. */
        private static final float RULER_SPAN = 150f;
        private static final UiColor BIOME_COLOR = new UiColor(120, 220, 140, 255);
        private static final UiColor ACCENT = new UiColor(100, 180, 255, 255);
        private static final UiColor PLUS = new UiColor(110, 220, 130, 255);
        private static final UiColor MINUS = new UiColor(240, 100, 100, 255);
        /** Posé par le constructeur du module, juste après {@code super(...)}. */
        CoordsModule module;

        // Ce qui est réellement dessiné : jamais rien du tout (voir la javadoc de classe).
        private boolean coords() {
            CoordsModule m = module;
            return m == null || m.showCoords || (!m.showFacing && !m.showBiome);
        }

        private boolean facing() {
            return module == null || module.showFacing;
        }

        private boolean biome() {
            return module == null || module.showBiome;
        }

        private boolean direction() {
            return module == null || module.showDirection;
        }

        /** L'orientation seule, sans les coordonnées pour la porter : c'est la règle graduée. */
        private boolean ruler() {
            return facing() && !coords();
        }

        @Override
        public float[] naturalSize() {
            float height = 0f;
            if (coords()) height += 3f * LINE_H;
            if (ruler()) height += RULER_H;
            if (biome()) height += LINE_H;
            float width;
            if (ruler()) {
                width = module == null ? WIDTH_FULL : Math.max(100f, Math.min(320f, module.rulerLength));
            } else {
                width = biome() || (coords() && (facing() || direction())) ? WIDTH_FULL : WIDTH_COORDS_ONLY;
            }
            return new float[]{ width, height };
        }

        @Override
        public void draw(UiRenderer renderer, float x, float y, float w, float h, float scale, int vpWidth, int vpHeight) {
            float lineH = LINE_H * scale, textScale = TEXT_SCALE * scale;
            float rowX = x;
            float rightEdge = x + w;

            String xLine = "X: --", yLine = "Y: --", zLine = "Z: --", biome = null;
            boolean known = false;
            float yaw = 0f;
            try {
                // Joueur, position et yaw par les ACCESSORS Mixin (PlayerData) —
                // zéro réflexion. Ce bloc résolvait auparavant le joueur
                // lui-même (accès direct + repli réflexif multi-bracket) ;
                // cette résolution n'existe plus qu'à un seul endroit.
                if (!PlayerData.inGame() && PlayerData.previewing()) {
                    // Éditeur de HUD ouvert hors d'un monde : une position d'exemple, pour voir l'élément plein.
                    double[] pos = PlayerData.position();
                    xLine = "X: " + (int) Math.floor(pos[0]);
                    yLine = "Y: " + (int) Math.floor(pos[1]);
                    zLine = "Z: " + (int) Math.floor(pos[2]);
                    yaw = PlayerData.yaw();
                    known = true;
                    // Un exemple de déplacement aussi, pour que l'éditeur montre les signes.
                    moving[0] = 1; moving[1] = 0; moving[2] = -1;
                    hasLastPos = false;
                    biome = Lang.tr("Plaines");
                } else if (PlayerData.inGame()) {
                    double[] pos = PlayerData.position();
                    double px = pos[0], py = pos[1], pz = pos[2];
                    xLine = "X: " + (int) Math.floor(px);
                    yLine = "Y: " + (int) Math.floor(py);
                    zLine = "Z: " + (int) Math.floor(pz);
                    yaw = PlayerData.yaw();
                    trackMovement(pos);
                    known = true;
                    // Résolu seulement s'il est affiché : c'est la lecture la plus chère du module.
                    if (biome()) {
                        biome = cachedBiomeName((int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
                    }
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

            float top = y + h; // bord haut de ce qui reste à dessiner, descend à chaque bloc

            if (coords()) {
                boolean signs = known && direction();
                int xDir = signs ? moving[0] : 0, yDir = signs ? moving[1] : 0, zDir = signs ? moving[2] : 0;

                float ty = top - textScale * 24f; // première ligne, en haut de la zone de contenu
                drawValue(renderer, xLine, xDir, rowX, ty, textScale, vpWidth, vpHeight);
                // "--" (2 tirets ASCII), PAS le tiret cadratin (U+2013) : hors de la
                // plage de glyphes de UiFont, il retombait sur le glyphe de repli.
                // Décor de l'orientation : il part avec elle.
                if (facing()) drawRightAligned(renderer, "--", rightEdge, ty, ACCENT, textScale, vpWidth, vpHeight);
                ty -= lineH;

                drawValue(renderer, yLine, yDir, rowX, ty, textScale, vpWidth, vpHeight);
                // Valeur d'orientation EN ACCENT — contrairement à FPS/ms où
                // seule l'ÉTIQUETTE prend la couleur, jamais la VALEUR
                // numérique : ici c'est justement la valeur (le point
                // cardinal) qui doit ressortir.
                if (known && facing()) {
                    drawRightAligned(renderer, facingLetter(yaw), rightEdge, ty, ACCENT, textScale, vpWidth, vpHeight);
                }
                ty -= lineH;

                drawValue(renderer, zLine, zDir, rowX, ty, textScale, vpWidth, vpHeight);
                top -= 3f * lineH;
            }

            if (ruler()) {
                drawRuler(renderer, x, top, w, scale, yaw, vpWidth, vpHeight);
                top -= RULER_H * scale;
            }

            if (biome()) {
                float ty = top - textScale * 24f;
                String biomeLabel = "Biome: ";
                renderer.drawText(biomeLabel, rowX, ty, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
                if (biome != null) {
                    float labelW = renderer.textWidth(biomeLabel, textScale);
                    renderer.drawText(biome, rowX + labelW, ty, BIOME_COLOR, textScale, vpWidth, vpHeight);
                }
            }
        }

        /**
         * La règle graduée : une graduation tous les 15°, plus longue tous les
         * 45° avec son point cardinal dessous ; le repère du milieu est la
         * direction regardée. Elle défile comme une boussole — ce qui est à
         * droite du joueur est à droite du repère.
         *
         * <p>Repère : {@code top} est le bord HAUT de la règle (y vers le haut).
         * Rien n'est découpé : une graduation s'efface en approchant d'un bord,
         * et un point cardinal qui dépasserait n'est pas dessiné.
         */
        private void drawRuler(UiRenderer renderer, float x, float top, float w, float scale, float yaw,
                               int vpWidth, int vpHeight) {
            float textScale = TEXT_SCALE * scale;
            float half = w / 2f, cx = x + half;
            // Pas fixe : une règle plus longue montre plus de degrés, elle n'étire pas les graduations.
            float perDegree = WIDTH_FULL / RULER_SPAN * scale;
            float fade = 25f * scale;
            float tickTop = top - 3f * scale;
            float thin = Math.max(1f, scale);
            float normalized = ((yaw % 360f) + 360f) % 360f;

            for (int mark = 0; mark < 360; mark += 15) {
                // Écart au regard, ramené dans [-180, 180[ : le yaw croît en tournant à droite.
                float delta = ((mark - normalized + 540f) % 360f) - 180f;
                float dx = delta * perDegree;
                float room = half - Math.abs(dx);
                if (room <= 0f) continue;
                float alpha = Math.min(1f, room / fade);

                boolean major = mark % 45 == 0;
                boolean cardinal = mark % 90 == 0;
                float length = (major ? 9f : 5f) * scale;
                UiColor tick = (cardinal ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED).multiplyAlpha(alpha);
                float tx = cx + dx;
                renderer.drawRoundedRect(tx - thin / 2f, tickTop - length, tx + thin / 2f, tickTop, 0f, tick,
                    vpWidth, vpHeight);

                if (!major) continue;
                String label = compassLabel(mark);
                float tw = renderer.textWidth(label, textScale);
                if (Math.abs(dx) + tw / 2f > half) continue;
                UiColor color = (cardinal ? ACCENT : UiTheme.TEXT_SECONDARY).multiplyAlpha(alpha);
                renderer.drawText(label, tx - tw / 2f, tickTop - 13f * scale - textScale * 24f, color, textScale,
                    vpWidth, vpHeight);
            }

            // Le repère : la direction regardée, par-dessus les graduations.
            float marker = Math.max(2f, 2f * scale);
            renderer.drawRoundedRect(cx - marker / 2f, tickTop - 12f * scale, cx + marker / 2f, top, 0f, ACCENT,
                vpWidth, vpHeight);
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

        // ── Sens du déplacement ────────────────────────────────────────────
        // Mesuré sur la position elle-même, d'une image à l'autre : le regard
        // ne dit rien du sens (on recule, on marche de côté, on tombe).

        /** En dessous, la coordonnée n'a pas bougé (bruit de flottant, joueur immobile). */
        private static final double MOVE_EPSILON = 1.0e-4;
        /** Un signe reste affiché ce temps après le dernier mouvement sur son axe. La position n'avance
         *  que 20 fois par seconde : sans cette tenue il clignoterait entre deux ticks. */
        private static final long SIGN_HOLD_NANOS = 250_000_000L;

        private final double[] lastPos = new double[3];
        private boolean hasLastPos;
        /** Par axe (X, Y, Z) : +1 la coordonnée augmente, -1 elle diminue, 0 immobile. */
        private final int[] moving = new int[3];
        private final long[] movedAt = new long[3];

        private void trackMovement(double[] pos) {
            long now = System.nanoTime();
            for (int axis = 0; axis < 3; axis++) {
                double delta = hasLastPos ? pos[axis] - lastPos[axis] : 0;
                if (Math.abs(delta) > MOVE_EPSILON) {
                    moving[axis] = delta > 0 ? 1 : -1;
                    movedAt[axis] = now;
                } else if (moving[axis] != 0 && now - movedAt[axis] > SIGN_HOLD_NANOS) {
                    moving[axis] = 0;
                }
                lastPos[axis] = pos[axis];
            }
            hasLastPos = true;
        }

        /**
         * Une ligne « X: 123 », suivie de « (+) » en vert si la coordonnée est
         * en train d'augmenter, de « (-) » en rouge si elle diminue, de rien si
         * elle ne bouge pas ({@code direction} : +1, -1 ou 0). ASCII seulement :
         * le signe moins typographique n'est pas dans la police (voir UiFont).
         */
        private void drawValue(UiRenderer renderer, String line, int direction, float x, float ty,
                               float textScale, int vpW, int vpH) {
            renderer.drawText(line, x, ty, UiTheme.TEXT_PRIMARY, textScale, vpW, vpH);
            if (direction == 0) return;
            boolean plus = direction > 0;
            float after = x + renderer.textWidth(line + " ", textScale);
            renderer.drawText(plus ? "(+)" : "(-)", after, ty, plus ? PLUS : MINUS, textScale, vpW, vpH);
        }

        /** Yaw vanilla : 0°=Sud, 90°=Ouest, 180°=Nord, 270°=Est (sens horaire vu du dessus) — 4 secteurs de 90°. */
        private String facingLetter(float yaw) {
            float normalized = ((yaw % 360f) + 360f) % 360f;
            if (normalized >= 315f || normalized < 45f) return Lang.tr("S");
            if (normalized < 135f) return Lang.tr("O");
            if (normalized < 225f) return Lang.tr("N");
            return Lang.tr("E");
        }

        /** Le point cardinal d'une graduation de la règle ({@code mark} : yaw multiple de 45°). */
        private static String compassLabel(int mark) {
            String s = Lang.tr("S"), o = Lang.tr("O"), n = Lang.tr("N"), e = Lang.tr("E");
            if (mark == 0) return s;
            if (mark == 45) return s + o;
            if (mark == 90) return o;
            if (mark == 135) return n + o;
            if (mark == 180) return n;
            if (mark == 225) return n + e;
            if (mark == 270) return e;
            return s + e;
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
                // Identifiant COMPLET ("minecraft:plains") par le point
                // d'accès : les deux versions n'ont aucune étape commune pour
                // l'obtenir (voir AccessPoint.LEVEL_BIOME_ID). Ne reste ici que
                // la mise en forme, qui ne dépend d'aucune version.
                String id = LevelData.biomeId(bx, by, bz);
                if (id == null) return null;
                int sep = id.indexOf(':');
                return prettifyBiomePath(sep < 0 ? id : id.substring(sep + 1));
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
