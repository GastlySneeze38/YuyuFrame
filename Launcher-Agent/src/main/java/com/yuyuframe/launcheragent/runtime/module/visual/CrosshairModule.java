package com.yuyuframe.launcheragent.runtime.module.visual;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigColor;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigSlider;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Crosshair personnalisé — port du vrai PvP-Mod
 * ({@code CrosshairConfig}/{@code CrosshairHandler}, mode procédural
 * uniquement ici — le mode image PNG de PvP-Mod n'est pas repris, pas
 * demandé). Le vanilla est masqué par {@code MixinCrosshair189}
 * ({@code InGameHud.showCrosshair()} forcé à false), on dessine notre propre
 * croix ici à la place.
 *
 * Barre de rechargement ("nouveau PvP", combat 1.9+) : n'existait PAS dans
 * PvP-Mod (1.8.9 uniquement, combat sans cooldown) — nouvelle fonctionnalité.
 * Lit {@code PlayerEntity.getAttackCooldownProgress(float baseTime)} (Yarn,
 * mappings.tiny ligne ~80707) / réel Mojang 26.1+
 * {@code Player.getAttackStrengthScale(float)} (vérifié par javap sur le jar
 * client 26.1.2 réel + bytecode de {@code Gui.renderCrosshair}, qui appelle
 * {@code getAttackStrengthScale(0.0F)}). Absente sur 1.8.9 (pas de mapping,
 * pas de cooldown avant la refonte du combat) — {@link #attackCooldownProgress()}
 * renvoie alors {@code -1f} (repli "impossible à savoir") et la barre ENTIÈRE
 * (fond + remplissage) n'est simplement PAS dessinée sur ce bracket, plutôt
 * qu'une fausse barre toujours pleine (demande explicite de l'utilisateur :
 * "pour les anciennes version qui n'ont pas de cooldown ne le met juste
 * pas").
 *
 * Utilise la VRAIE texture vanilla (demande explicite de l'utilisateur,
 * après un premier essai en rendu procédural jugé pas assez bon) :
 * {@code assets/minecraft/textures/gui/sprites/hud/
 * crosshair_attack_indicator_{background,progress}.png}, chargées depuis le
 * jar du jeu lui-même via le classloader courant (même motif que
 * {@code LauncherMixinService.getResourceAsStream} : contexte du thread,
 * repli sur le classloader de cette classe) puis dessinées avec
 * {@link UiRenderer#drawIcon} (déjà utilisé pour les icônes Modrinth,
 * chemin Blaze3D/legacy déjà éprouvé). La texture "progress" est recadrée
 * (largeur source réduite proportionnellement à l'avancement) plutôt
 * qu'étirée, pour reproduire le remplissage progressif de gauche à droite
 * de vanilla plutôt qu'un simple fondu.
 *
 * Absente si le chemin de ressource n'existe pas sur cette version (mise
 * en page d'assets différente sur les brackets plus anciens que le
 * système de sprites GUI, ou pas de mapping Yarn équivalent avant tout) —
 * repli propre sur un rendu procédural aux couleurs déjà calquées sur ces
 * mêmes textures (fond #3A3B3C, remplissage dégradé #D7D9EA → blanc,
 * extraits par échantillonnage pixel du jar 26.1.2), jamais de crash ni de
 * barre manquante.
 */
public final class CrosshairModule extends LauncherModule {

    @ConfigSlider(name = "Taille", category = "Réglages", min = 1f, max = 32f, step = 1f)
    public float size = 8f;

    @ConfigSlider(name = "Épaisseur", category = "Réglages", min = 1f, max = 6f, step = 1f)
    public float thickness = 2f;

    @ConfigSlider(name = "Espacement central", category = "Réglages", min = 0f, max = 16f, step = 1f)
    public float gap = 3f;

    @ConfigToggle(name = "Point au lieu d'une croix", category = "Réglages")
    public boolean dotMode = false;

    @ConfigColor(name = "Couleur", category = "Réglages")
    public UiColor color = new UiColor(255, 255, 255, 255);

    @ConfigToggle(name = "Afficher la barre de rechargement", category = "Rechargement")
    public boolean showCooldownBar = true;

    @ConfigSlider(name = "Largeur", category = "Rechargement", min = 6f, max = 300f, step = 1f)
    public float cooldownWidth = 100f;

    @ConfigSlider(name = "Hauteur", category = "Rechargement", min = 1f, max = 60f, step = 1f)
    public float cooldownHeight = 20f;

    @ConfigSlider(name = "Décalage vertical", category = "Rechargement", min = 0f, max = 60f, step = 1f)
    public float cooldownOffset = 14f;

    /** #D7D9EA — teinte dominante du sprite vanilla "progress" (voir javadoc de tête). */
    @ConfigColor(name = "Couleur (rempli)", category = "Rechargement")
    public UiColor cooldownColor = new UiColor(215, 217, 234, 255);

    /** #3A3B3C — couleur EXACTE (pixel sampling) du sprite vanilla "background". */
    @ConfigColor(name = "Couleur (fond)", category = "Rechargement")
    public UiColor cooldownBackgroundColor = new UiColor(58, 59, 60, 255);

    /** Contour fixe (pas configurable, juste pour détacher la barre de l'arrière-plan du jeu quel qu'il soit — le vrai vanilla n'en a pas besoin, il compte sur son propre alpha, mais notre croix personnalisable peut être posée sur n'importe quel décor). Utilisé UNIQUEMENT par le repli procédural (voir javadoc de tête) — la vraie texture vanilla n'en a pas besoin. */
    private static final UiColor COOLDOWN_BORDER = new UiColor(0, 0, 0, 200);

    private static final String VANILLA_BG_PATH = "assets/minecraft/textures/gui/sprites/hud/crosshair_attack_indicator_background.png";
    private static final String VANILLA_PROGRESS_PATH = "assets/minecraft/textures/gui/sprites/hud/crosshair_attack_indicator_progress.png";

    private static boolean vanillaTexturesAttempted;
    private static BufferedImage vanillaBgImage;
    private static BufferedImage vanillaProgressImage;
    private static boolean vanillaTextureErrorLogged;

    private static boolean cooldownErrorLogged;

    public CrosshairModule() {
        super("custom-crosshair", "Crosshair personnalisé", "Remplace la croix de visée vanilla", false,
            HookPoint.HUD_EXTRACT_CROSSHAIR);
        // Voir LauncherModule.ICON_LOCAL_CROSSHAIR — aucune icône "crosshair"/
        // "réticule"/"viseur" trouvée dans le style icons8 utilisé partout
        // ailleurs (vérifié individuellement), un vrai réticule dessiné à la
        // main est de toute façon plus fidèle que "target" (cible en cercles
        // concentriques, PAS un viseur).
        iconUrl = ICON_LOCAL_CROSSHAIR;

        // Migration apimixin (ROADMAP-agent.md §3.2) — remplace l'ancien
        // CrosshairMixin261 (mixin/, ModuleRegistry.get("custom-crosshair") +
        // ci.cancel()) : dispatch()==true fait sauter le rendu vanilla dans
        // HudExtractCrosshairMixin261 (apimixin/), exactement le même effet
        // que l'ancien ci.cancel() — masquer la croix vanilla quand ce module
        // est actif, notre propre croix étant dessinée séparément par
        // onRenderOverlay ci-dessous.
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_CROSSHAIR, ctx -> isEnabled());
    }

    @Override
    public void onRenderOverlay(UiRenderer renderer, int vpWidth, int vpHeight) {
        try {
            float cx = vpWidth / 2f;
            float cy = vpHeight / 2f;

            if (dotMode) {
                renderer.drawRoundedRect(cx - thickness, cy - thickness, cx + thickness, cy + thickness, thickness, color, vpWidth, vpHeight);
            } else {
                // Gauche / droite / haut / bas — 4 barres autour du centre, avec l'espacement "gap"
                renderer.drawRoundedRect(cx - gap - size, cy - thickness / 2f, cx - gap, cy + thickness / 2f, 0, color, vpWidth, vpHeight);
                renderer.drawRoundedRect(cx + gap, cy - thickness / 2f, cx + gap + size, cy + thickness / 2f, 0, color, vpWidth, vpHeight);
                renderer.drawRoundedRect(cx - thickness / 2f, cy - gap - size, cx + thickness / 2f, cy - gap, 0, color, vpWidth, vpHeight);
                renderer.drawRoundedRect(cx - thickness / 2f, cy + gap, cx + thickness / 2f, cy + gap + size, 0, color, vpWidth, vpHeight);
            }

            if (showCooldownBar) {
                float progress = attackCooldownProgress();
                if (progress < 0f) return; // pas de cooldown sur ce bracket (1.8.9) — rien à dessiner, voir javadoc de tête
                // Comportement vanilla (demande explicite) : la barre n'existe
                // que PENDANT le rechargement — dès que l'attaque est prête,
                // vanilla ne dessine RIEN du tout (vérifié par bytecode de
                // Gui.renderCrosshair : "if (progress>=1f) return;" avant même
                // d'atteindre le blit du fond/du remplissage) — elle réapparaît
                // seulement au coup suivant qui relance le cooldown.
                if (progress >= 0.999f) return;

                float base = dotMode ? thickness : (gap + size);
                // BUG TROUVÉ (retour utilisateur, capture d'écran) : le renderer
                // (drawIcon/drawRoundedRect) utilise une convention Y-UP
                // (+y = vers le HAUT de l'écran, voir la javadoc de
                // UiRenderer#drawIcon) — "cy + décalage" plaçait donc la barre
                // AU-DESSUS de la croix au lieu d'en dessous (invisible sur la
                // croix elle-même, symétrique haut/bas, donc jamais remarqué
                // avant). Pour aller EN DESSOUS (comme vanilla), il faut
                // SOUSTRAIRE : le bord le plus proche de la croix est à
                // cy - base - cooldownOffset, la barre s'étend encore plus loin
                // (donc encore plus petit en y) sur cooldownHeight.
                float y2 = cy - base - cooldownOffset;
                float y1 = y2 - cooldownHeight;
                float half = cooldownWidth / 2f;
                float x1 = cx - half, x2 = cx + half;

                ensureVanillaTextures();
                if (vanillaBgImage != null && vanillaProgressImage != null) {
                    drawVanillaCooldownBar(renderer, x1, y1, x2, y2, progress, vpWidth, vpHeight);
                } else {
                    drawProceduralCooldownBar(renderer, x1, y1, x2, y2, progress, vpWidth, vpHeight);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Vraie texture vanilla — voir javadoc de tête. */
    private void drawVanillaCooldownBar(UiRenderer renderer, float x1, float y1, float x2, float y2,
                                         float progress, int vpWidth, int vpHeight) {
        renderer.drawIcon("crosshair-cooldown-bg", vanillaBgImage, x1, y1, x2 - x1, y2 - y1, vpWidth, vpHeight);
        if (progress <= 0.01f) return;
        int srcW = vanillaProgressImage.getWidth();
        int croppedW = Math.max(1, Math.round(srcW * Math.min(1f, progress)));
        BufferedImage cropped = croppedW >= srcW ? vanillaProgressImage : vanillaProgressImage.getSubimage(0, 0, croppedW, vanillaProgressImage.getHeight());
        float fillW = (x2 - x1) * ((float) croppedW / srcW);
        renderer.drawIcon("crosshair-cooldown-fg-" + croppedW, cropped, x1, y1, fillW, y2 - y1, vpWidth, vpHeight);
    }

    /** Repli si la texture vanilla n'est pas disponible sur ce bracket — voir javadoc de tête. */
    private void drawProceduralCooldownBar(UiRenderer renderer, float x1, float y1, float x2, float y2,
                                            float progress, int vpWidth, int vpHeight) {
        float radius = (y2 - y1) / 2f;

        // Piste en pilule (radius = hauteur/2) — cohérent avec le reste du
        // style (UiSlider etc.), pas un rectangle à coins vifs qui
        // détonnait avec tout le reste de l'UI.
        renderer.drawRoundedRect(x1, y1, x2, y2, radius, cooldownBackgroundColor, vpWidth, vpHeight);

        if (progress > 0.01f) {
            float fillX2 = x1 + (x2 - x1) * progress;
            // Radius du remplissage plafonné à sa propre demi-largeur : évite
            // une pilule déformée/auto-intersectante quand le remplissage est
            // plus étroit que la hauteur de la barre (tout début du cooldown).
            float fillRadius = Math.min(radius, (fillX2 - x1) / 2f);
            UiColor fillHighlight = UiColor.lerp(cooldownColor, new UiColor(1f, 1f, 1f, cooldownColor.a), 0.45f);
            renderer.drawGradientRect(x1, y1, fillX2, y2, fillRadius, cooldownColor, fillHighlight, vpWidth, vpHeight);
        }

        // Contour pour détacher la barre de n'importe quel arrière-plan
        // (herbe claire, ciel, neige...) — sans lui, le fond translucide se
        // fondait dans le décor selon la scène regardée.
        renderer.drawRoundedRectBorder(x1, y1, x2, y2, radius, 1f, COOLDOWN_BORDER, vpWidth, vpHeight);
    }

    /** Chargement paresseux, une seule fois (jamais par frame) — voir javadoc de tête pour le chemin/motif de classloader. */
    private static void ensureVanillaTextures() {
        if (vanillaTexturesAttempted) return;
        vanillaTexturesAttempted = true;
        try {
            vanillaBgImage = loadVanillaSprite(VANILLA_BG_PATH);
            vanillaProgressImage = loadVanillaSprite(VANILLA_PROGRESS_PATH);
        } catch (Throwable t) {
            if (!vanillaTextureErrorLogged) {
                vanillaTextureErrorLogged = true;
                LauncherLog.err("[CrosshairModule] ensureVanillaTextures: " + t);
            }
        }
    }

    private static BufferedImage loadVanillaSprite(String path) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(path) : null;
        if (in == null) in = CrosshairModule.class.getClassLoader().getResourceAsStream(path);
        if (in == null) return null;
        try (InputStream is = in) {
            return javax.imageio.ImageIO.read(is);
        }
    }

    /**
     * @return 0..1 (1 = attaque prête), ou {@code -1f} si le cooldown
     * d'attaque n'existe pas sur ce bracket (1.8.9) / n'est pas encore
     * lisible (monde pas chargé) — l'appelant doit alors ne RIEN dessiner
     * du tout, pas juste replier sur "toujours prêt" (voir javadoc de tête).
     */
    private float attackCooldownProgress() {
        // 26.1.2 sans réflexion — Minecraft.player + getAttackStrengthScale
        // (méthode publique, voir stub LocalPlayer). Try/catch dédié : nom de
        // classe RÉEL, inexistant tel quel sur les autres brackets (obfusqués).
        try {
            LocalPlayer directPlayer = Minecraft.getInstance().player;
            if (directPlayer != null) return directPlayer.getAttackStrengthScale(0f);
        } catch (Throwable ignored) {}
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return -1f;
            Field playerField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player");
            if (playerField == null) return -1f;
            Object player = playerField.get(mc);
            if (player == null) return -1f;
            Method getAttackCooldownProgress = McReflect.oneArgMethod(player.getClass(),
                "net/minecraft/entity/player/PlayerEntity", "getAttackCooldownProgress", "getAttackStrengthScale", float.class);
            if (getAttackCooldownProgress == null) return -1f;
            return (float) getAttackCooldownProgress.invoke(player, 0f);
        } catch (Throwable t) {
            if (!cooldownErrorLogged) {
                cooldownErrorLogged = true;
                LauncherLog.err("[CrosshairModule] attackCooldownProgress: " + t);
            }
            return -1f;
        }
    }
}
