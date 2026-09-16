package com.yuyuframe.launcheragent.runtime.module.legacy17;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.MatrixOps;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;

/**
 * Animations 1.7 — UN module (2026-09-16) à la place du groupe de sept
 * (swing, épée en diagonale, item fixe, swing en bloquant, arc, manger/boire,
 * sneak progressif), chaque effet devenant une option.
 *
 * <h2>Références</h2>
 *
 * Les valeurs viennent des mods 1.8.9 que les joueurs PvP utilisent :
 * <a href="https://github.com/Legacy-Visuals-Project/Animatium-Legacy">Animatium-Legacy</a>
 * (ex-OverflowAnimationsV2, lui-même fork de
 * <a href="https://github.com/Sk1erLLC/OldAnimations">Sk1er OldAnimations</a>).
 * Code réécrit ici sur nos points d'accroche ; seules les constantes de pose,
 * mesurées sur la 1.7 par ces projets, sont reprises.
 *
 * <h2>Ce qui a été retiré et pourquoi</h2>
 * <ul>
 *   <li><b>Épée en diagonale</b> : angle et décalage inventés, rien de 1.7.
 *       Remplacée par les vraies positions 1.7 des objets plats.</li>
 *   <li><b>Arc 1.7</b> : simple décalage arbitraire. Remplacé par la pose 1.7
 *       mesurée (avant et après la mise à l'échelle de l'arc).</li>
 *   <li><b>Manger/Boire 1.7</b> : pose recalculée à la main, différente de la
 *       1.7 — la 1.7 garde la pose vanilla et laisse seulement voir le coup
 *       pendant qu'on mange (option « Block-hit »).</li>
 *   <li><b>Sneak progressif</b> : ralentissait le DÉPLACEMENT, ce que la 1.7
 *       ne faisait pas. Remplacé par la vraie sensation 1.7 : la CAMÉRA
 *       descend et remonte en douceur.</li>
 *   <li><b>Swing en bloquant</b> : relançait le swing une seule fois, à
 *       l'appui. Remplacé par le block-hit 1.7 complet (swing continu,
 *       particules).</li>
 * </ul>
 */
public final class Animations17Module extends LauncherModule {

    public boolean blockHit = true;
    public boolean blockHitParticles = true;
    public boolean itemPositions = true;
    public boolean fixedItem = false;
    public float swingSpeedPercent = 100f;
    public boolean smoothSneak = true;
    public boolean thirdPersonBlocking = true;
    public boolean redArmor = true;
    public boolean noHeartFlash = true;

    private final MatrixOps itemOps = new MatrixOps();
    private final MatrixOps bowPreOps = new MatrixOps()
        .rotate(-335.0f, 0f, 0f, 1f).rotate(-50.0f, 0f, 1f, 0f).translate(0f, 0.5f, 0f);
    private final MatrixOps bowPostOps = new MatrixOps()
        .translate(0f, -0.5f, 0f).rotate(50.0f, 0f, 1f, 0f).rotate(335.0f, 0f, 0f, 1f);
    private final MatrixOps thirdPersonBlockOps = new MatrixOps()
        .translate(0.05f, 0f, -0.1f).rotate(-50.0f, 0f, 1f, 0f).rotate(-10.0f, 1f, 0f, 0f).rotate(-60.0f, 0f, 0f, 1f);

    @Override
    protected void settings(SettingList s) {
        s.toggle("blockHit", "Block-hit",
            "Frapper un bloc en bloquant, mangeant ou bandant l'arc fait bouger le bras, en continu tant que les deux clics sont maintenus.",
            "Combat", null, () -> blockHit, v -> blockHit = v);
        s.toggle("blockHitParticles", "Particules du block-hit",
            "Particules du bloc frappé pendant le block-hit, comme un vrai coup.",
            "Combat", () -> blockHit, () -> blockHitParticles, v -> blockHitParticles = v);
        s.toggle("redArmor", "Armure rouge",
            "L'armure d'un joueur touché devient rouge avec lui.",
            "Combat", null, () -> redArmor, v -> redArmor = v);
        s.toggle("itemPositions", "Position des objets",
            "Épées, outils, arc et canne à pêche tenus comme en 1.7 en 1re personne.",
            "Première personne", null, () -> itemPositions, v -> itemPositions = v);
        s.toggle("fixedItem", "Objet fixe",
            "L'objet tenu ne se balance plus quand on tourne la tête.",
            "Première personne", null, () -> fixedItem, v -> fixedItem = v);
        s.slider("swingSpeedPercent", "Vitesse du swing (%)",
            "100 % = vitesse normale.",
            "Première personne", 100f, 400f, 10f, null,
            () -> swingSpeedPercent, v -> swingSpeedPercent = v);
        s.toggle("thirdPersonBlocking", "Blocage en 3e personne",
            "Épée et bras des joueurs qui bloquent placés comme en 1.7.",
            "Troisième personne", null, () -> thirdPersonBlocking, v -> thirdPersonBlocking = v);
        s.toggle("smoothSneak", "Sneak fluide",
            "La caméra descend et remonte en douceur en s'accroupissant.",
            "Caméra et interface", null, () -> smoothSneak, v -> smoothSneak = v);
        s.toggle("noHeartFlash", "Cœurs sans clignotement",
            "Les cœurs ne clignotent plus quand la vie change.",
            "Caméra et interface", null, () -> noHeartFlash, v -> noHeartFlash = v);
    }

    public Animations17Module() {
        super("animations-1-7", "Animations 1.7",
            "Block-hit, positions d'objets, armure rouge et sneak fluide de la 1.7", false,
            HookPoint.HELD_ITEM_TRANSFORM, HookPoint.HELD_ITEM_SWING_PROGRESS, HookPoint.SWING_DURATION,
            HookPoint.ATTACK_HELD_TICK, HookPoint.CAMERA_EYE_HEIGHT, HookPoint.HEALTH_BAR_FLASH,
            HookPoint.ARMOR_DAMAGE_TINT, HookPoint.THIRD_PERSON_HELD_ITEM_TRANSFORM, HookPoint.BLOCKING_ARM_YAW);
        iconUrl = icons8("time-machine");
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_TRANSFORM, this::heldItemTransform);
        VanillaHookRegistry.registerValue(HookPoint.HELD_ITEM_SWING_PROGRESS,
            ctx -> isEnabled() && blockHit ? Boolean.TRUE : null);
        VanillaHookRegistry.registerValue(HookPoint.SWING_DURATION, this::swingDuration);
        VanillaHookRegistry.register(HookPoint.ATTACK_HELD_TICK, this::attackHeldTick);
        VanillaHookRegistry.registerValue(HookPoint.CAMERA_EYE_HEIGHT, this::eyeHeight);
        VanillaHookRegistry.registerValue(HookPoint.HEALTH_BAR_FLASH,
            ctx -> isEnabled() && noHeartFlash ? Boolean.FALSE : null);
        VanillaHookRegistry.registerValue(HookPoint.ARMOR_DAMAGE_TINT,
            ctx -> isEnabled() && redArmor ? Boolean.TRUE : null);
        VanillaHookRegistry.registerValue(HookPoint.THIRD_PERSON_HELD_ITEM_TRANSFORM,
            ctx -> isEnabled() && thirdPersonBlocking && PlayerData.isBlocking(ctx) ? thirdPersonBlockOps : null);
        VanillaHookRegistry.registerValue(HookPoint.BLOCKING_ARM_YAW,
            ctx -> isEnabled() && thirdPersonBlocking ? Float.valueOf(0f) : null);
    }

    // ── 1re personne ──────────────────────────────────────────────────────

    /** Contexte {@code Object[]{ String étape, Float tickDelta }} — voir {@link HookPoint#HELD_ITEM_TRANSFORM}. */
    private Object heldItemTransform(Object ctx) {
        if (!isEnabled() || !(ctx instanceof Object[]) || ((Object[]) ctx).length < 1) return null;
        Object stage = ((Object[]) ctx)[0];
        if ("rotation".equals(stage)) return fixedItem ? MatrixOps.EMPTY : null;
        if (!itemPositions) return null;
        if ("bow_pre".equals(stage)) return bowPreOps;
        if ("bow".equals(stage)) return bowPostOps;
        if (!"item".equals(stage)) return null;
        String kind = PlayerData.mainHandKind();
        // Les blocs (modèle en volume) gardent leur pose 1.8, comme en 1.7.
        if (kind == null || "empty".equals(kind) || "block".equals(kind)) return null;
        itemOps.clear();
        // La canne à pêche est dessinée retournée : demi-tour avant la pose commune.
        if ("rod".equals(kind)) itemOps.rotate(180.0f, 0f, 1f, 0f);
        // Pose 1.7 des objets plats : échelle 1/1.7 × 1.5, léger lacet, recentrage.
        float scale = 0.88235295f;
        return itemOps.scale(scale, scale, scale).rotate(5.0f, 0f, 1f, 0f).translate(-0.29f, 0.149f, -0.0328f);
    }

    private Object swingDuration(Object ctx) {
        if (!isEnabled() || !(ctx instanceof Integer) || swingSpeedPercent <= 100f) return null;
        return Integer.valueOf(Math.max(1, Math.round((Integer) ctx * 100f / swingSpeedPercent)));
    }

    /**
     * Block-hit : attaque maintenue pendant une utilisation, réticule sur un
     * bloc — vanilla 1.8 ne fait rien. Le swing est relancé dès qu'il a passé
     * sa première moitié (garde de {@code PLAYER_RESTART_SWING_ANIMATION},
     * même règle que la 1.7) : maintenu, le bras enchaîne les coups.
     */
    private boolean attackHeldTick(Object ctx) {
        if (!isEnabled() || !blockHit || !Boolean.TRUE.equals(ctx)) return false;
        if (PlayerData.itemUse() == null || !"block".equals(ClientData.crosshairTarget())) return false;
        if (blockHitParticles) ClientData.spawnBlockHitParticles();
        PlayerData.restartSwingAnimation();
        return false;
    }

    // ── Caméra ────────────────────────────────────────────────────────────

    private static final long TICK_NANOS = 50_000_000L;
    private float sneakHeight = Float.NaN;
    private float sneakPrevHeight;
    private long sneakNextTick;

    /**
     * Sneak fluide, même courbe qu'Animatium-Legacy (« Smooth Sneaking » +
     * « Longer Unsneak ») : par tick de jeu, la hauteur rejoint la cible d'un
     * coup en descendant et de moitié de l'écart en remontant, puis est
     * interpolée entre deux ticks à chaque image. Les ticks sont recomptés
     * sur l'horloge, ce point n'étant appelé qu'aux images.
     */
    private Object eyeHeight(Object ctx) {
        if (!isEnabled() || !smoothSneak || !(ctx instanceof Float)) {
            sneakHeight = Float.NaN;
            return null;
        }
        float target = (Float) ctx;
        long now = System.nanoTime();
        if (Float.isNaN(sneakHeight) || now - sneakNextTick > 20 * TICK_NANOS) {
            sneakHeight = target;
            sneakPrevHeight = target;
            sneakNextTick = now + TICK_NANOS;
        }
        while (now >= sneakNextTick) {
            sneakPrevHeight = sneakHeight;
            sneakHeight = target < sneakHeight ? target : sneakHeight + (target - sneakHeight) * 0.5f;
            sneakNextTick += TICK_NANOS;
        }
        float t = 1f - (sneakNextTick - now) / (float) TICK_NANOS;
        return Float.valueOf(sneakPrevHeight + (sneakHeight - sneakPrevHeight) * t);
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        sneakHeight = Float.NaN;
    }
}
