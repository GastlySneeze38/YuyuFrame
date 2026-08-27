package com.yuyuframe.launcheragent.apigraphic.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Système de particules léger (roadmap Phase 5.4, "confettis/étincelles pour
 * les thèmes premium") — pas de moteur physique, juste position+vitesse+
 * gravité+fondu, taillé pour quelques dizaines de particules décoratives à
 * la fois (pas un système générique haute densité). Une instance par usage
 * (ex: un écran "achat réussi") — pas de singleton global, la durée de vie
 * suit celle de l'écran qui la possède.
 *
 * Dessine via {@link UiRenderer#drawRoundedRect} (une des 3 backends
 * legacy/modern/Blaze3D, quel que soit celui actif) — un appel par
 * particule, PAS le pipeline de rects batchés (roadmap Phase 5.5,
 * Blaze3D uniquement) : ce système reste volontairement portable sur les 3
 * backends plutôt que de se coupler à une optimisation spécifique à l'un
 * d'eux, un système "léger" de quelques dizaines de particules n'a de toute
 * façon pas besoin du batching pour rester fluide.
 */
public final class UiParticleSystem {

    private static final float GRAVITY = 420f; // px/s², tire vers le BAS de l'écran

    private static final UiColor[] CONFETTI_COLORS = {
        new UiColor(255, 99, 132, 255),
        new UiColor(255, 205, 86, 255),
        new UiColor(75, 192, 192, 255),
        new UiColor(139, 124, 255, 255),
        new UiColor(255, 159, 64, 255),
    };

    private static final class Particle {
        float x, y, vx, vy, size, spin, angle;
        float life, maxLife;
        UiColor color;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final Random rng = new Random();

    public int count() { return particles.size(); }

    /**
     * Explosion de confettis depuis {@code (x,y)} — {@code count} particules,
     * couleurs aléatoires parmi {@link #CONFETTI_COLORS}, direction/vitesse
     * aléatoires biaisées vers le haut (typique d'un "burst" de confettis).
     */
    public void burstConfetti(float x, float y, int count) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            float angleRad = (float) (rng.nextFloat() * Math.PI); // demi-cercle vers le haut (0..π), voir Y-UP ci-dessous
            float speed = 120f + rng.nextFloat() * 260f;
            p.vx = (float) Math.cos(angleRad) * speed;
            // Y CROISSANT VERS LE HAUT (même convention que tout le moteur,
            // voir Blaze3DCore/ensureProjectionBuffer) — vy positif = part
            // vers le haut, la gravité (soustraite dans tick()) le ramène.
            p.vy = (float) Math.sin(angleRad) * speed;
            p.size = 5f + rng.nextFloat() * 6f;
            p.spin = (rng.nextFloat() - 0.5f) * 8f;
            p.maxLife = p.life = 0.9f + rng.nextFloat() * 0.7f;
            p.color = CONFETTI_COLORS[rng.nextInt(CONFETTI_COLORS.length)];
            particles.add(p);
        }
    }

    /** Étincelles — plus petites/rapides/courtes que les confettis, dispersion complète (360°) plutôt que biaisée vers le haut. */
    public void burstSparkles(float x, float y, int count, UiColor color) {
        for (int i = 0; i < count; i++) {
            Particle p = new Particle();
            p.x = x;
            p.y = y;
            float angleRad = (float) (rng.nextFloat() * Math.PI * 2.0);
            float speed = 60f + rng.nextFloat() * 140f;
            p.vx = (float) Math.cos(angleRad) * speed;
            p.vy = (float) Math.sin(angleRad) * speed;
            p.size = 2f + rng.nextFloat() * 3f;
            p.spin = 0f;
            p.maxLife = p.life = 0.3f + rng.nextFloat() * 0.3f;
            p.color = color;
            particles.add(p);
        }
    }

    /** À appeler une fois par frame avec le delta-temps RÉEL (secondes) — jamais un pas fixe, le framerate de l'UI n'est pas garanti constant. */
    public void tick(float deltaSeconds) {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            p.life -= deltaSeconds;
            if (p.life <= 0f) {
                it.remove();
                continue;
            }
            p.vy -= GRAVITY * deltaSeconds;
            p.x += p.vx * deltaSeconds;
            p.y += p.vy * deltaSeconds;
            p.angle += p.spin * deltaSeconds;
        }
    }

    public void clear() { particles.clear(); }

    public void draw(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (Particle p : particles) {
            float half = p.size / 2f;
            float fade = Math.max(0f, Math.min(1f, p.life / p.maxLife));
            renderer.drawRoundedRect(p.x - half, p.y - half, p.x + half, p.y + half,
                p.size * 0.25f, p.color.multiplyAlpha(fade), vpWidth, vpHeight);
        }
    }

    // ── POC ──────────────────────────────────────────────────────────────
    /** Toggle via {@code /yf particlepoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean testEnabled = false;
    private static final UiParticleSystem TEST_SYSTEM = new UiParticleSystem();
    private static long lastTestTickNanos = -1L;
    private static long lastBurstAtNanos = -1L;

    /** Rejoue un burst de confettis toutes les ~1.5s en bas au centre de l'écran — preuve de mécanisme, tick+dessin en direct. */
    public static void tickAndDrawTest(UiRenderer renderer, int vpWidth, int vpHeight) {
        long now = System.nanoTime();
        if (lastTestTickNanos < 0) lastTestTickNanos = now;
        float dt = (now - lastTestTickNanos) / 1_000_000_000f;
        lastTestTickNanos = now;

        if (lastBurstAtNanos < 0 || (now - lastBurstAtNanos) > 1_500_000_000L) {
            TEST_SYSTEM.burstConfetti(vpWidth / 2f, 60f, 30);
            lastBurstAtNanos = now;
        }
        TEST_SYSTEM.tick(Math.min(dt, 0.1f)); // clamp — évite un saut énorme après un freeze/F3+T
        TEST_SYSTEM.draw(renderer, vpWidth, vpHeight);
    }
}
