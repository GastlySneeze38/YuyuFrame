package com.yuyuframe.launcheragent.celeritas.v1_8_9.nativecore;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Les quads de modèles, rangés une fois pour toutes en mémoire native pour
 * que le noyau les lise par leur adresse — notre code. Le format d'une fiche
 * est celui de {@code QuadRecord} dans {@code content-core/src/mesh/emit.rs} :
 * 28 mots de 4 octets.
 *
 * <p>Une fiche n'est jamais libérée ni déplacée : une tâche de maillage peut
 * encore la lire après un rechargement des ressources. Ce que laisse derrière
 * lui un rechargement (les quads des anciens modèles) reste alloué — quelques
 * mégaoctets au plus.
 */
public final class QuadStore {

    /** Mots de 4 octets d'une fiche. */
    public static final int RECORD_WORDS = 28;
    private static final long RECORD_BYTES = RECORD_WORDS * 4L;

    /** Rang des mots qui suivent les six tableaux de quatre valeurs. */
    public static final int WORD_FLAGS = 24;
    public static final int WORD_FACES = 25;
    public static final int WORD_NORMAL = 26;

    private static final long BLOCK_BYTES = 4096 * RECORD_BYTES;

    private static MemorySegment block;
    private static long used;

    private QuadStore() {
    }

    /** Range une fiche et rend son adresse. */
    public static synchronized long store(int[] words) {
        if (words.length != RECORD_WORDS) {
            throw new IllegalArgumentException("fiche de " + words.length + " mots");
        }
        if (block == null || used + RECORD_BYTES > BLOCK_BYTES) {
            block = Arena.global().allocate(BLOCK_BYTES, 8);
            used = 0;
        }
        MemorySegment record = block.asSlice(used, RECORD_BYTES);
        MemorySegment.copy(words, 0, record, ValueLayout.JAVA_INT, 0, RECORD_WORDS);
        used += RECORD_BYTES;
        return record.address();
    }

    /** Réécrit un mot d'une fiche déjà rangée. */
    public static void setWord(long address, int word, int value) {
        MemorySegment.ofAddress(address).reinterpret(RECORD_BYTES).set(ValueLayout.JAVA_INT, word * 4L, value);
    }
}
