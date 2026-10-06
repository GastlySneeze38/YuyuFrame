package com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * SONDE TEMPORAIRE (2026-10-06) — à retirer avec {@link EntityCostProbeMixin189}
 * une fois le regroupement des entités décidé.
 *
 * <p>Découpe le coût du dessin d'une entité vivante : son modèle, son
 * étiquette de nom, et chacune de ses couches (armure, objet tenu, cape…).
 * Le relevé par sorte d'entité montrait un joueur à 80–250 µs contre 8 µs
 * pour un porte-armure : il faut savoir ce qui, dans un joueur, coûte autant.
 *
 * <p>Clés : « classe de l'entité : étape », en noms du jar.
 */
@YarnNamed
public final class EntityCostProbe189 {

    private EntityCostProbe189() {
    }

    private static final HashMap<String, long[]> COSTS = new HashMap<>();

    private static long modelStarted, labelStarted;

    private static void record(String key, long nanos) {
        long[] cost = COSTS.get(key);
        if (cost == null) {
            cost = new long[2];
            COSTS.put(key, cost);
        }
        cost[0]++;
        cost[1] += nanos;
    }

    public static void beginModel() {
        modelStarted = System.nanoTime();
    }

    public static void endModel(Object entity) {
        record(entity.getClass().getName() + " : modèle", System.nanoTime() - modelStarted);
    }

    public static void beginLabel() {
        labelStarted = System.nanoTime();
    }

    public static void endLabel(Object entity) {
        record(entity.getClass().getName() + " : étiquette", System.nanoTime() - labelStarted);
    }

    /** Rappelle la couche détournée par le mixin, en la chronométrant. */
    public static void renderFeature(Object feature, Object entity, float limbAngle, float limbDistance,
                                     float tickDelta, float age, float headYaw, float headPitch, float scale) {
        long started = System.nanoTime();
        ((FeatureRenderer) feature).render((LivingEntity) entity, limbAngle, limbDistance, tickDelta, age,
            headYaw, headPitch, scale);
        record(entity.getClass().getName() + " : couche " + feature.getClass().getName(), System.nanoTime() - started);
    }

    /** Le relevé depuis le dernier appel, du plus coûteux au moins coûteux, ou {@code null} s'il est vide. */
    public static String drain(int frames) {
        if (COSTS.isEmpty()) {
            return null;
        }
        ArrayList<Map.Entry<String, long[]>> entries = new ArrayList<>(COSTS.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]));
        StringBuilder line = new StringBuilder("entités, détail par image : ");
        for (Map.Entry<String, long[]> entry : entries) {
            long[] cost = entry.getValue();
            line.append(entry.getKey())
                .append(" ×").append(String.format("%.1f", cost[0] / (double) frames))
                .append(" = ").append(cost[1] / frames / 1000L).append(" µs")
                .append(" (").append(cost[1] / Math.max(1L, cost[0]) / 1000L).append(" µs l'une) ; ");
        }
        COSTS.clear();
        return line.toString();
    }
}
