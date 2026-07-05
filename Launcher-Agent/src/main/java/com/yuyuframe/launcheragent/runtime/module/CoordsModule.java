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
                        double[] pos = playerPos(player);
                        double px = pos[0], py = pos[1], pz = pos[2];
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

        private static Method cachedGetX, cachedGetY, cachedGetZ;
        private static boolean posResolveAttempted;

        /**
         * BUG TROUVÉ (audit modules, voir historique de session) : {@code
         * Entity.x}/{@code y}/{@code z} n'existent plus comme CHAMPS depuis
         * un remaniement Mojang antérieur à la 1.16.5 — remplacés par les
         * méthodes {@code getX()}/{@code getY()}/{@code getZ()} (mappings
         * 1.16.5 : {@code ()D cD method_23317 getX}, etc.). Essaie l'ancien
         * chemin (champs directs, 1.8.9) d'abord, sinon les méthodes.
         */
        private double[] playerPos(Object player) throws Exception {
            Field xf = tryField(player, "x"), yf = tryField(player, "y"), zf = tryField(player, "z");
            if (xf != null && yf != null && zf != null) {
                return new double[]{ xf.getDouble(player), yf.getDouble(player), zf.getDouble(player) };
            }
            if (!posResolveAttempted) {
                posResolveAttempted = true;
                cachedGetX = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getX");
                cachedGetY = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getY");
                cachedGetZ = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getZ");
            }
            if (cachedGetX == null || cachedGetY == null || cachedGetZ == null) return new double[]{ 0, 0, 0 };
            return new double[]{ (double) cachedGetX.invoke(player), (double) cachedGetY.invoke(player), (double) cachedGetZ.invoke(player) };
        }

        private Field tryField(Object obj, String yarnField) {
            // Vérifie que le mapping Yarn existe VRAIMENT (voir historique de
            // session) — sinon getObfFieldName retombe sur "x"/"y"/"z" tel
            // quel, qui peut par coïncidence matcher un vrai champ obfusqué
            // sans rapport (noms réels = 1-2 lettres, faux positif possible).
            if (!com.yuyuframe.launcheragent.runtime.mapping.MappingsRegistry.hasFieldMapping("net/minecraft/entity/Entity", yarnField)) {
                return null;
            }
            try {
                return McReflect.field(obj.getClass(), "net/minecraft/entity/Entity", yarnField);
            } catch (Throwable t) {
                return null;
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
                while (m == null && c != null) {
                    m = findByShape(c.getDeclaredMethods(), blockPosClass, biomeClass);
                    c = c.getSuperclass();
                }
            }
            if (m == null) {
                // Repli supplémentaire : méthodes PUBLIQUES héritées/d'interface
                // (getMethods(), pas juste getDeclaredMethods() en remontant les
                // classes CONCRÈTES) — couvre le cas où getBiome ne serait
                // qu'une méthode DEFAULT d'interface (ex: WorldView/BlockView)
                // jamais redéclarée concrètement dans la hiérarchie de classes,
                // donc invisible pour le repli ci-dessus.
                m = findByShape(world.getClass().getMethods(), blockPosClass, biomeClass);
            }
            if (m != null) m.setAccessible(true);
            diag(m != null
                ? "getBiome retrouvé par repli type-retour : " + m
                : "getBiome introuvable même par repli type-retour (world class=" + world.getClass() + ")");
            cachedGetBiome = m;
            return m;
        }

        /**
         * Comparaison par NOM de classe (getName()) plutôt que isAssignableFrom
         * — immunisée contre un éventuel écart d'IDENTITÉ de classe entre deux
         * classloaders différents (BlockPos/Biome résolus via yarnClass() d'un
         * côté, vs le type réel du paramètre/retour de la méthode candidate de
         * l'autre) : deux Class distincts avec le MÊME nom binaire échouent un
         * test isAssignableFrom même s'ils représentent conceptuellement le
         * même type, alors qu'une comparaison de nom reste correcte.
         */
        private Method findByShape(Method[] methods, Class<?> blockPosClass, Class<?> biomeClass) {
            String blockPosName = blockPosClass.getName();
            String biomeName = biomeClass.getName();
            for (Method cand : methods) {
                if (cand.getParameterCount() == 1
                        && cand.getParameterTypes()[0].getName().equals(blockPosName)
                        && cand.getReturnType().getName().equals(biomeName)) {
                    return cand;
                }
            }
            return null;
        }

        private String biomeName(Object player, int bx, int by, int bz) {
            try {
                Object world = McReflect.field(player.getClass(), "net/minecraft/entity/Entity", "world").get(player);
                if (world == null) { diagBiome("world == null"); return null; }

                Class<?> blockPosClass = McReflect.yarnClass("net/minecraft/util/math/BlockPos");
                if (blockPosClass == null) { diagBiome("blockPosClass == null"); return null; }
                Class<?> biomeClass = McReflect.yarnClass("net/minecraft/world/biome/Biome");
                if (biomeClass == null) { diagBiome("biomeClass == null"); return null; }
                Constructor<?> ctor = blockPosClass.getConstructor(int.class, int.class, int.class);
                Object pos = ctor.newInstance(bx, by, bz);

                Method getBiome = resolveGetBiome(world, blockPosClass, biomeClass);
                if (getBiome == null) { diagBiome("resolveGetBiome a renvoyé null"); return null; }
                Object biome = getBiome.invoke(world, pos);
                if (biome == null) { diagBiome("biome == null"); return null; }

                String name = registryBiomeName(world, biome);
                diagBiome("OK, name=" + name);
                return name;
            } catch (Throwable t) {
                diagBiome("exception: " + t);
                return null;
            }
        }

        /**
         * BUG TROUVÉ (audit modules, voir historique de session) : {@code
         * Biome.name} (champ String) n'existe plus en 1.16.5 — les biomes
         * sont passés à un vrai REGISTRE dynamique ({@code
         * DynamicRegistryManager}, introduit par la Mise à jour Nether pour
         * les biomes personnalisables), sans nom embarqué sur l'objet
         * lui-même. Chemin : {@code World.getRegistryManager()} (déclaré sur
         * l'interface {@code RegistryWorldView}) → {@code
         * DynamicRegistryManager.get(Registry.BIOME_KEY)} → {@code
         * Registry.getId(biome)} (Identifier) → {@code getPath()} ("plains",
         * "frozen_ocean"...), mis en forme ("Plains", "Frozen Ocean").
         */
        private String registryBiomeName(Object world, Object biome) {
            try {
                Method getRegistryManager = McReflect.noArgMethod(world.getClass(), "net/minecraft/world/RegistryWorldView", "getRegistryManager");
                if (getRegistryManager == null) { diag2("getRegistryManager introuvable, world=" + world.getClass()); return null; }
                Object registryManager = getRegistryManager.invoke(world);
                if (registryManager == null) { diag2("registryManager == null"); return null; }

                Class<?> registryClass = McReflect.yarnClass("net/minecraft/util/registry/Registry");
                if (registryClass == null) { diag2("registryClass == null"); return null; }
                Field biomeKeyField = McReflect.field(registryClass, "net/minecraft/util/registry/Registry", "BIOME_KEY");
                if (biomeKeyField == null) { diag2("champ BIOME_KEY introuvable sur " + registryClass); return null; }
                Object biomeKey = biomeKeyField.get(null);
                if (biomeKey == null) { diag2("biomeKey == null"); return null; }

                Method get = McReflect.oneArgMethod(registryManager.getClass(), "net/minecraft/util/registry/DynamicRegistryManager", "get", biomeKey.getClass());
                if (get == null) { diag2("get(RegistryKey) introuvable sur " + registryManager.getClass() + " (biomeKey class=" + biomeKey.getClass() + ")"); return null; }
                Object biomeRegistry = get.invoke(registryManager, biomeKey);
                if (biomeRegistry == null) { diag2("biomeRegistry == null"); return null; }

                Method getId = McReflect.oneArgMethod(biomeRegistry.getClass(), "net/minecraft/util/registry/Registry", "getId", Object.class);
                if (getId == null) { diag2("getId introuvable sur " + biomeRegistry.getClass()); return null; }
                Object identifier = getId.invoke(biomeRegistry, biome);
                if (identifier == null) { diag2("identifier == null (biome class=" + biome.getClass() + ")"); return null; }

                Method getPath = McReflect.noArgMethod(identifier.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (getPath == null) { diag2("getPath introuvable sur " + identifier.getClass()); return null; }
                String path = (String) getPath.invoke(identifier);
                diag2("OK, path=" + path);
                return prettifyBiomePath(path);
            } catch (Throwable t) {
                diag2("exception: " + t);
                return null;
            }
        }

        private static boolean DIAG2_LOGGED = false;

        private void diag2(String msg) {
            if (DIAG2_LOGGED) return;
            DIAG2_LOGGED = true;
            com.yuyuframe.launcheragent.runtime.log.LauncherLog.info("[CoordsModule] registryBiomeName diag: " + msg);
        }

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

        private void diag(String msg) {
            if (DIAG_LOGGED) return;
            DIAG_LOGGED = true;
            com.yuyuframe.launcheragent.runtime.log.LauncherLog.info("[CoordsModule] biomeName diag: " + msg);
        }

        private static boolean BIOME_DIAG_LOGGED = false;

        /**
         * BUG TROUVÉ : ce message et celui de resolveGetBiome() (diag() ci-dessus)
         * partageaient le MÊME flag one-shot (DIAG_LOGGED) — dès que
         * resolveGetBiome loggait son message (1ère frame), plus AUCUN message
         * de biomeName() (dont "OK, name=..." ou "exception: ...") ne pouvait
         * jamais s'afficher pour le reste de la session. Flag séparé ici pour
         * voir le résultat final indépendamment du message de résolution.
         */
        private void diagBiome(String msg) {
            if (BIOME_DIAG_LOGGED) return;
            BIOME_DIAG_LOGGED = true;
            com.yuyuframe.launcheragent.runtime.log.LauncherLog.info("[CoordsModule] biomeName result: " + msg);
        }
    }
}
