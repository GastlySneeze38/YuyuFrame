package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.ui.hud.HudAnchor;
import com.yuyuframe.launcheragent.runtime.ui.hud.HudElement;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.apigraphic.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

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
                // 26.1.2 sans réflexion — Minecraft.player (champ public).
                // Try/catch dédié : Minecraft.getInstance() référence le nom
                // RÉEL, inexistant tel quel sur les autres brackets (obfusqués).
                Object mc = null, playerDirect = null;
                try {
                    Minecraft directMc = Minecraft.getInstance();
                    if (directMc != null && directMc.player != null) { mc = directMc; playerDirect = directMc.player; }
                } catch (Throwable ignored) {}
                if (playerDirect == null) mc = McReflect.minecraftClient();
                if (mc != null) {
                    Object player = playerDirect != null ? playerDirect
                        : McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                    if (player != null) {
                        double[] pos = playerPos(player);
                        double px = pos[0], py = pos[1], pz = pos[2];
                        xLine = "X: " + (int) Math.floor(px);
                        yLine = "Y: " + (int) Math.floor(py);
                        zLine = "Z: " + (int) Math.floor(pz);

                        float yaw = playerYaw(player);
                        facing = facingLetter(yaw);
                        biome = biomeName(player, (int) Math.floor(px), (int) Math.floor(py), (int) Math.floor(pz));
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
            // 26.1.2 sans réflexion — getX()/getY()/getZ() (méthodes publiques,
            // voir stub LocalPlayer). Try/catch dédié : instanceof contre un nom
            // de classe RÉEL, inexistant tel quel sur les autres brackets.
            try {
                if (player instanceof LocalPlayer) {
                    LocalPlayer lp = (LocalPlayer) player;
                    return new double[]{ lp.getX(), lp.getY(), lp.getZ() };
                }
            } catch (Throwable ignored) {}
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

        private static Method cachedGetYaw;
        private static boolean yawResolveAttempted;

        /**
         * BUG TROUVÉ (1.20.4, log confirmé après premier fix) : {@code
         * tryField(player, "yaw")} levait {@code IllegalArgumentException:
         * Attempt to get bka field "bml.aG" with illegal data type conversion
         * to float} — "aG" EST bien le vrai nom obfusqué de {@code
         * Entity.yaw} (mapping Yarn authentique, pas un repli hasardeux), mais
         * la résolution par remontée de hiérarchie depuis la classe RUNTIME du
         * JOUEUR s'arrêtait au PREMIER champ littéralement nommé "aG" — une
         * classe intermédiaire ({@code bml}, entre la classe du joueur et
         * Entity) définit SA PROPRE "aG" totalement différente (type {@code
         * bka}, pas float), plus proche dans la hiérarchie que le vrai champ
         * d'Entity. Fix : {@link McReflect#fieldOnClass} résout directement
         * sur la classe {@code Entity} elle-même, jamais en remontant depuis
         * la classe runtime du joueur.
         */
        private float playerYaw(Object player) throws Exception {
            // 26.1.2 sans réflexion — getYRot() (méthode publique).
            try {
                if (player instanceof LocalPlayer) return ((LocalPlayer) player).getYRot();
            } catch (Throwable ignored) {}
            Field yf = McReflect.fieldOnClass("net/minecraft/entity/Entity", "yaw");
            if (yf != null) return yf.getFloat(player);
            if (!yawResolveAttempted) {
                yawResolveAttempted = true;
                // 26.1+ : getYaw→getYRot (vérifié par javap sur le jar client 26.1.2 réel).
                cachedGetYaw = McReflect.noArgMethod(player.getClass(), "net/minecraft/entity/Entity", "getYaw", "getYRot");
            }
            return cachedGetYaw != null ? (float) cachedGetYaw.invoke(player) : 0f;
        }

        private static boolean DRAW_EXC_LOGGED = false;

        private void drawExceptionDiag(Throwable t) {
            if (DRAW_EXC_LOGGED) return;
            DRAW_EXC_LOGGED = true;
            com.yuyuframe.launcheragent.runtime.log.LauncherLog.err("[CoordsModule] draw() exception: " + t);
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

        private static boolean modernGetBiomeAttempted;
        private static Method cachedGetBiomeManager, cachedModernGetBiome;

        /**
         * 26.1+ : {@code World.getBiome(BlockPos)} n'existe plus DU TOUT sur
         * {@code Level} (vérifié par javap sur le jar client 26.1.2 réel,
         * aucune méthode de cette forme) — remplacé par {@code
         * Level.getBiomeManager()} → {@code BiomeManager.getBiome(BlockPos)},
         * qui renvoie directement un {@code Holder<Biome>}. Tenté ICI en
         * premier ; {@code null} proprement si absent (repli sur {@link
         * #resolveGetBiome} pour les brackets antérieurs à 26.1).
         */
        private Object modernGetBiome(Object world, Class<?> blockPosClass, Object pos) {
            try {
                if (!modernGetBiomeAttempted) {
                    modernGetBiomeAttempted = true;
                    cachedGetBiomeManager = McReflect.noArgMethod(world.getClass(), "net/minecraft/world/World", "getBiomeManager");
                    if (cachedGetBiomeManager != null) {
                        Object biomeManager = cachedGetBiomeManager.invoke(world);
                        if (biomeManager != null) {
                            cachedModernGetBiome = McReflect.oneArgMethod(biomeManager.getClass(),
                                "net/minecraft/world/biome/source/BiomeManager", "getBiome", blockPosClass);
                        }
                    }
                }
                if (cachedGetBiomeManager == null || cachedModernGetBiome == null) return null;
                Object biomeManager = cachedGetBiomeManager.invoke(world);
                return biomeManager != null ? cachedModernGetBiome.invoke(biomeManager, pos) : null;
            } catch (Throwable t) {
                return null;
            }
        }

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
        private Method resolveGetBiome(Object world, Class<?> blockPosClass, Class<?> biomeClass, Class<?> registryEntryClass) {
            if (cachedGetBiome != null) return cachedGetBiome;
            if (getBiomeResolveAttempted) return null;
            getBiomeResolveAttempted = true;

            Method m = McReflect.oneArgMethod(world.getClass(), "net/minecraft/world/World", "getBiome", blockPosClass);
            if (m == null) {
                Class<?> c = world.getClass();
                while (m == null && c != null) {
                    m = findByShape(c.getDeclaredMethods(), blockPosClass, biomeClass, registryEntryClass);
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
                m = findByShape(world.getClass().getMethods(), blockPosClass, biomeClass, registryEntryClass);
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
         *
         * BUG TROUVÉ (1.20.4, test utilisateur) : le type de retour attendu
         * n'est plus forcément {@code Biome} directement — depuis le passage
         * aux "Holders"/RegistryEntry (~1.19+), {@code World.getBiome(BlockPos)}
         * renvoie un {@code RegistryEntry<Biome>} (vérifié : {@code (Lhx;)Lih;
         * t method_23753 getBiome}, {@code ih} = {@code
         * net.minecraft.registry.entry.RegistryEntry}), donc AUCUNE méthode ne
         * matchait plus le filtre "retour == Biome" strict d'avant. Accepte
         * maintenant les DEUX types de retour possibles (Biome direct, comme
         * en 1.16.5, OU RegistryEntry, comme en 1.20.4).
         */
        private Method findByShape(Method[] methods, Class<?> blockPosClass, Class<?> biomeClass, Class<?> registryEntryClass) {
            String blockPosName = blockPosClass.getName();
            String biomeName = biomeClass.getName();
            String registryEntryName = registryEntryClass != null ? registryEntryClass.getName() : null;
            for (Method cand : methods) {
                if (cand.getParameterCount() != 1 || !cand.getParameterTypes()[0].getName().equals(blockPosName)) continue;
                String retName = cand.getReturnType().getName();
                if (retName.equals(biomeName) || (registryEntryName != null && retName.equals(registryEntryName))) {
                    return cand;
                }
            }
            return null;
        }

        /**
         * 26.1.2 sans réflexion — {@code Minecraft.level} (champ public) →
         * {@code getBiomeManager()} → {@code getBiome(BlockPos)} → {@code
         * Holder<Biome>} (méthodes publiques vérifiées par javap, voir
         * javadoc de {@link #modernGetBiome}). {@code Holder.getKey()}/{@code
         * ResourceKey.getValue()} reprennent EXACTEMENT les mêmes noms que
         * l'ancien chemin réflexif ({@link #registryEntryKeyPath}, jamais
         * revérifié pour 26.1.2 spécifiquement mais repris par cohérence
         * plutôt que de deviner un autre nom — voir stub {@code Holder}/
         * {@code ResourceKey}).
         */
        private String biomeDirect(int bx, int by, int bz) {
            try {
                ClientLevel level = Minecraft.getInstance().level;
                if (level == null) return null;
                BiomeManager biomeManager = level.getBiomeManager();
                if (biomeManager == null) return null;
                Holder<Biome> holder = biomeManager.getBiome(new BlockPos(bx, by, bz));
                if (holder == null) return null;
                Optional<ResourceKey<Biome>> keyOpt = holder.getKey();
                if (keyOpt == null || !keyOpt.isPresent()) return null;
                Identifier id = keyOpt.get().getValue();
                return id != null ? prettifyBiomePath(id.getPath()) : null;
            } catch (Throwable t) {
                return null;
            }
        }

        private String biomeName(Object player, int bx, int by, int bz) {
            try {
                if (player instanceof LocalPlayer) {
                    String direct = biomeDirect(bx, by, bz);
                    if (direct != null) return direct;
                }
            } catch (Throwable ignored) {}
            try {
                // BUG TROUVÉ (1.20.4, même cause que playerYaw) : l'ancienne
                // résolution (McReflect.field(player.getClass(), ...), qui
                // remonte la hiérarchie RUNTIME du joueur) tombait sur un champ
                // "t" SANS RAPPORT déclaré sur la classe intermédiaire {@code
                // bml} (type {@code agm<Byte>}, une TrackedData interne) au
                // lieu du vrai champ {@code world} d'Entity, plus haut dans la
                // hiérarchie — d'où "world class=class agm" dans les logs.
                // fieldOnClass résout directement sur Entity, jamais en
                // remontant depuis la classe runtime du joueur.
                // 26.1+ : Entity déplacé vers net.minecraft.world.entity.Entity,
                // champ "world"→"level" (vérifiés par javap sur le jar client
                // 26.1.2 réel).
                Field worldField = McReflect.fieldOnClass("net/minecraft/entity/Entity", "net.minecraft.world.entity.Entity", "world", "level");
                if (worldField == null) { diagBiome("worldField == null"); return null; }
                Object world = worldField.get(player);
                if (world == null) { diagBiome("world == null"); return null; }

                Class<?> blockPosClass = McReflect.yarnClass("net/minecraft/util/math/BlockPos", "net.minecraft.core.BlockPos");
                if (blockPosClass == null) { diagBiome("blockPosClass == null"); return null; }
                Class<?> biomeClass = McReflect.yarnClass("net/minecraft/world/biome/Biome", "net.minecraft.world.level.biome.Biome");
                if (biomeClass == null) { diagBiome("biomeClass == null"); return null; }
                Class<?> registryEntryClass = McReflect.yarnClass("net/minecraft/registry/entry/RegistryEntry", "net.minecraft.core.Holder");
                Constructor<?> ctor = blockPosClass.getConstructor(int.class, int.class, int.class);
                Object pos = ctor.newInstance(bx, by, bz);

                // 26.1+ : World.getBiome(BlockPos) N'EXISTE PLUS DU TOUT (vérifié
                // par javap) — remplacé par Level.getBiomeManager().getBiome(pos),
                // renvoyant directement un Holder<Biome>. Tenté EN PREMIER (répond
                // null proprement si absent, repli sur resolveGetBiome pour <26.1).
                Object biome = modernGetBiome(world, blockPosClass, pos);
                if (biome == null) {
                    Method getBiome = resolveGetBiome(world, blockPosClass, biomeClass, registryEntryClass);
                    if (getBiome == null) { diagBiome("resolveGetBiome a renvoyé null"); return null; }
                    biome = getBiome.invoke(world, pos);
                }
                if (biome == null) { diagBiome("biome == null"); return null; }

                // BUG TROUVÉ (1.20.4, log confirmé après le fix world) :
                // World.getRegistryManager() introuvable sur le vrai monde
                // 1.20.4 ("registryBiomeName diag: getRegistryManager
                // introuvable") — l'API DynamicRegistryManager a encore bougé
                // entre 1.16.5 et 1.20.4. Mais puisque getBiome renvoie
                // maintenant un RegistryEntry (voir plus haut), on peut
                // COURT-CIRCUITER tout ce chemin : RegistryEntry.getKey()
                // (Yarn named, class_6880) renvoie DIRECTEMENT un
                // Optional<RegistryKey<Biome>> — RegistryKey.getValue()
                // donne l'Identifier sans jamais passer par
                // World.getRegistryManager()/Registry.getId(). Essayé EN
                // PREMIER (avant le déballage) ; repli sur l'ancien chemin
                // registre (registryBiomeName, via Biome déballé) seulement
                // si ce nouveau chemin échoue.
                String name = null;
                if (registryEntryClass != null && registryEntryClass.isInstance(biome)) {
                    name = registryEntryKeyPath(biome);
                }
                if (name == null) {
                    Object rawBiome = biome;
                    if (registryEntryClass != null && registryEntryClass.isInstance(rawBiome)) {
                        Method value = McReflect.noArgMethod(rawBiome.getClass(),
                            "net/minecraft/registry/entry/RegistryEntry", "value");
                        rawBiome = value != null ? value.invoke(rawBiome) : null;
                    }
                    if (rawBiome != null) name = registryBiomeName(world, rawBiome);
                }
                diagBiome("OK, name=" + name);
                return name;
            } catch (Throwable t) {
                diagBiome("exception: " + t);
                return null;
            }
        }

        /**
         * Chemin COURT (1.20.4+) : {@code RegistryEntry.getKey()} →
         * {@code Optional<RegistryKey<Biome>>} → {@code
         * RegistryKey.getValue()} → {@code Identifier} → {@code getPath()} —
         * ne dépend JAMAIS de {@code World.getRegistryManager()} (voir
         * registryBiomeName ci-dessous, qui lui en dépend et échoue sur ce
         * bracket). {@code null} si une étape échoue (entrée "directe" sans
         * clé, méthode introuvable...) — l'appelant replie alors sur
         * l'ancien chemin registre.
         */
        private String registryEntryKeyPath(Object registryEntry) {
            try {
                Method getKey = McReflect.noArgMethod(registryEntry.getClass(),
                    "net/minecraft/registry/entry/RegistryEntry", "getKey");
                if (getKey == null) { diag2("RegistryEntry.getKey() introuvable"); return null; }
                Object optional = getKey.invoke(registryEntry);
                if (!(optional instanceof java.util.Optional) || !((java.util.Optional<?>) optional).isPresent()) {
                    diag2("RegistryEntry.getKey() vide (entrée directe, sans clé)");
                    return null;
                }
                Object registryKey = ((java.util.Optional<?>) optional).get();

                Method getValue = McReflect.noArgMethod(registryKey.getClass(),
                    "net/minecraft/registry/RegistryKey", "getValue");
                if (getValue == null) { diag2("RegistryKey.getValue() introuvable"); return null; }
                Object identifier = getValue.invoke(registryKey);
                if (identifier == null) { diag2("RegistryKey.getValue() == null"); return null; }

                Method getPath = McReflect.noArgMethod(identifier.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (getPath == null) { diag2("Identifier.getPath() introuvable"); return null; }
                String path = (String) getPath.invoke(identifier);
                diag2("OK (chemin RegistryEntry.getKey()), path=" + path);
                return prettifyBiomePath(path);
            } catch (Throwable t) {
                diag2("registryEntryKeyPath exception: " + t);
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
        /**
         * 26.1+ : chemin complètement redessiné par rapport à {@link
         * #registryBiomeName} (vérifié par javap sur le jar client 26.1.2 réel) :
         * {@code World.getRegistryManager()}→{@code Level.registryAccess()}
         * (renvoie {@code RegistryAccess}, plus {@code DynamicRegistryManager}) ;
         * le champ statique {@code Registry.BIOME_KEY} a été DÉPLACÉ vers une
         * classe séparée {@code net.minecraft.core.registries.Registries.BIOME}
         * (jamais eu d'équivalent Yarn, résolu en dur via {@code rawClass}) ;
         * {@code DynamicRegistryManager.get(key)}→{@code RegistryAccess.
         * lookup(key)} (renvoie désormais {@code Optional<Registry<T>>} au lieu
         * du registre directement) ; et SURTOUT {@code Registry.getId(T)} ne
         * renvoie PLUS un {@code Identifier} mais un ENTIER — le comportement
         * historique de "getId" s'appelle maintenant {@code getKey(T)}. Gardé
         * par la présence de la classe {@code Registries} (n'existe QUE sur
         * 26.1+), donc jamais tenté par erreur sur un bracket antérieur.
         */
        private String modernRegistryBiomeName(Object world, Object biome) {
            try {
                Class<?> registriesClass = McReflect.rawClass("net.minecraft.core.registries.Registries");
                if (registriesClass == null) return null; // <26.1 : classe inexistante, repli sur l'ancien chemin

                Method registryAccess = McReflect.noArgMethod(world.getClass(), "net/minecraft/world/World", "getRegistryManager", "registryAccess");
                if (registryAccess == null) { diag2("registryAccess introuvable (26.1+)"); return null; }
                Object access = registryAccess.invoke(world);
                if (access == null) { diag2("registryAccess == null"); return null; }

                Object biomeKey = registriesClass.getField("BIOME").get(null);
                if (biomeKey == null) { diag2("Registries.BIOME == null"); return null; }

                Method lookup = McReflect.oneArgMethod(access.getClass(), "net/minecraft/core/RegistryAccess", "lookup", biomeKey.getClass());
                if (lookup == null) { diag2("RegistryAccess.lookup introuvable"); return null; }
                Object optionalRegistry = lookup.invoke(access, biomeKey);
                if (!(optionalRegistry instanceof java.util.Optional) || !((java.util.Optional<?>) optionalRegistry).isPresent()) {
                    diag2("RegistryAccess.lookup(BIOME) vide"); return null;
                }
                Object biomeRegistry = ((java.util.Optional<?>) optionalRegistry).get();

                Method getKey = McReflect.oneArgMethod(biomeRegistry.getClass(), "net/minecraft/core/Registry", "getKey", Object.class);
                if (getKey == null) { diag2("Registry.getKey introuvable"); return null; }
                Object identifier = getKey.invoke(biomeRegistry, biome);
                if (identifier == null) { diag2("Registry.getKey(biome) == null"); return null; }

                Method getPath = McReflect.noArgMethod(identifier.getClass(), "net/minecraft/util/Identifier", "getPath");
                if (getPath == null) { diag2("Identifier.getPath introuvable (26.1+)"); return null; }
                String path = (String) getPath.invoke(identifier);
                diag2("OK (chemin moderne 26.1+), path=" + path);
                return prettifyBiomePath(path);
            } catch (Throwable t) {
                diag2("modernRegistryBiomeName exception: " + t);
                return null;
            }
        }

        private String registryBiomeName(Object world, Object biome) {
            String modern = modernRegistryBiomeName(world, biome);
            if (modern != null) return modern;
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

        private void diag2(String msg) {
            // Diagnostics de résolution retirés du log (bruit, voir historique
            // de session) — la chaîne de repli elle-même reste inchangée,
            // seul l'affichage de sa progression est coupé.
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
            // Diagnostics retirés du log (bruit, voir historique de session).
        }

        private void diagBiome(String msg) {
            // Diagnostics retirés du log (bruit, voir historique de session).
        }
    }
}
