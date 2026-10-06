package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.debug.MeshStats;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

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
 *
 * <p><b>Taille</b> (2026-10-06) : elle suit la distance de rendu. Avec 512
 * places fixes, à 32 chunks le cache ne servait plus qu'une fois sur deux —
 * 180 000 copies en cinq secondes pendant un chargement, et un ramasse-miettes
 * qui tournait en continu. Les copies qui ne servent plus depuis cinq secondes
 * sont rendues au fur et à mesure : un cache large ne retient donc de la
 * mémoire que pendant qu'on charge du terrain.
 */
@YarnNamed
public final class SnapshotCache {

    private static final int MIN_ENTRIES = 2048;
    private static final int MAX_ENTRIES = 24576;
    /** Sections gardées par colonne de chunk à portée de vue : le tiers de la hauteur du monde. */
    private static final int ENTRIES_PER_COLUMN = 6;
    private static final long MAX_AGE_NANOS = 5_000_000_000L;

    private final World world;
    private final int capacity;
    private final Long2ObjectLinkedOpenHashMap<SectionSnapshot> entries = new Long2ObjectLinkedOpenHashMap<>();
    /** Ce que chaque colonne de chunk a de commun à toutes ses sections, par {@link #columnKey}. */
    private final Long2ObjectLinkedOpenHashMap<ColumnSnapshot> columns = new Long2ObjectLinkedOpenHashMap<>();

    public SnapshotCache(World world, int renderDistance) {
        this.world = world;
        int side = 2 * renderDistance + 1;
        this.capacity = Math.max(MIN_ENTRIES, Math.min(MAX_ENTRIES, side * side * ENTRIES_PER_COLUMN));
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFL) << 42 | ((long) z & 0x3FFFFFL) << 20 | ((long) y & 0xFFFFFL);
    }

    private static long columnKey(int x, int z) {
        return ((long) x & 0xFFFFFFFFL) << 32 | ((long) z & 0xFFFFFFFFL);
    }

    public SectionSnapshot acquire(int x, int y, int z) {
        long key = key(x, y, z);
        long now = System.nanoTime();
        SectionSnapshot snapshot = this.entries.getAndMoveToLast(key);
        if (snapshot != null && now - snapshot.capturedNanos <= MAX_AGE_NANOS) {
            MeshStats.snapshot(false);
            return snapshot;
        }
        MeshStats.snapshot(true);

        Chunk chunk = this.world.getChunk(x, z);
        snapshot = SectionSnapshot.capture(chunk, this.column(chunk, x, z, now), x, y, z);
        while (this.entries.size() >= this.capacity) {
            this.entries.removeFirst();
        }
        this.entries.putAndMoveToLast(key, snapshot);
        return snapshot;
    }

    private ColumnSnapshot column(Chunk chunk, int x, int z, long now) {
        long key = columnKey(x, z);
        ColumnSnapshot column = this.columns.getAndMoveToLast(key);
        if (column == null || now - column.capturedNanos > MAX_AGE_NANOS) {
            column = new ColumnSnapshot(chunk);
            this.columns.putAndMoveToLast(key, column);
        }
        return column;
    }

    /**
     * Rend les copies qui n'ont plus servi depuis cinq secondes. Les deux
     * cartes sont rangées par dernier usage : il suffit de regarder en tête.
     * À appeler de temps en temps sur le fil du jeu.
     */
    public void trim() {
        long now = System.nanoTime();
        while (!this.entries.isEmpty() && now - this.entries.get(this.entries.firstLongKey()).capturedNanos > MAX_AGE_NANOS) {
            this.entries.removeFirst();
        }
        while (!this.columns.isEmpty() && now - this.columns.get(this.columns.firstLongKey()).capturedNanos > MAX_AGE_NANOS) {
            this.columns.removeFirst();
        }
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
        // Un bloc posé ou retiré change la hauteur de sa colonne.
        this.columns.remove(columnKey(x, z));
    }

    /** Un chunk entier vient d'arriver ou de partir. */
    public void invalidateColumn(int x, int z) {
        // Une section au-dessus et une en dessous du monde : elles sont copiées aussi, vides.
        for (int y = -1; y <= 16; y++) {
            this.entries.remove(key(x, y, z));
        }
        this.columns.remove(columnKey(x, z));
    }
}
