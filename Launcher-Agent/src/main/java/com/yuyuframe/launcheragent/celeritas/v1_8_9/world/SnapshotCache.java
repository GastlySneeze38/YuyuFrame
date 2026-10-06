package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.World;

/**
 * Garde les copies figées récentes : mailler une section demande ses 26
 * voisines, et deux sections voisines en partagent la plupart. Sans ce cache,
 * chaque construction recopierait 27 sections.
 *
 * <p>Fil du jeu seulement — c'est lui qui copie, et lui qui apprend les
 * changements de blocs.
 *
 * <p>Une copie est jetée quand sa section change ({@link #invalidate}), au
 * bout de cinq secondes (un changement que personne ne nous aurait signalé ne
 * reste pas affiché), ou quand le cache est plein.
 */
@YarnNamed
public final class SnapshotCache {

    private static final int MAX_ENTRIES = 512;
    private static final long MAX_AGE_NANOS = 5_000_000_000L;

    private final World world;
    private final Long2ObjectLinkedOpenHashMap<SectionSnapshot> entries = new Long2ObjectLinkedOpenHashMap<>();

    public SnapshotCache(World world) {
        this.world = world;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) z & 0x3FFFFFL) << 20 | ((long) y & 0xFFFFFL);
    }

    public SectionSnapshot acquire(int x, int y, int z) {
        long key = key(x, y, z);
        SectionSnapshot snapshot = this.entries.getAndMoveToLast(key);
        if (snapshot != null && System.nanoTime() - snapshot.capturedNanos <= MAX_AGE_NANOS) {
            return snapshot;
        }
        snapshot = SectionSnapshot.capture(this.world, x, y, z);
        while (this.entries.size() >= MAX_ENTRIES) {
            this.entries.removeFirst();
        }
        this.entries.putAndMoveToLast(key, snapshot);
        return snapshot;
    }

    /**
     * Les 27 sections autour de celle-ci, rangées pour {@link WorldSlice189} :
     * indice {@code dx + 3 × (dz + 3 × dy)}, chaque écart allant de 0 à 2.
     */
    public SectionSnapshot[] around(int x, int y, int z) {
        SectionSnapshot[] sections = new SectionSnapshot[27];
        for (int dy = 0; dy < 3; dy++) {
            for (int dz = 0; dz < 3; dz++) {
                for (int dx = 0; dx < 3; dx++) {
                    sections[dx + 3 * (dz + 3 * dy)] = this.acquire(x + dx - 1, y + dy - 1, z + dz - 1);
                }
            }
        }
        return sections;
    }

    public void invalidate(int x, int y, int z) {
        this.entries.remove(key(x, y, z));
    }

    /** Un chunk entier vient d'arriver ou de partir. */
    public void invalidateColumn(int x, int z) {
        // Une section au-dessus et une en dessous du monde : elles sont copiées aussi, vides.
        for (int y = -1; y <= 16; y++) {
            this.invalidate(x, y, z);
        }
    }
}
