package com.yuyuframe.launcheragent.celeritas.v1_8_9.world;

import com.yuyuframe.launcheragent.celeritas.util.position.SectionPos;

/**
 * Ce qu'une tâche de construction emporte du fil du jeu vers son fil de
 * travail : la section à mailler, et la copie figée de ses 27 sections (elle
 * et ses voisines), rangées comme {@link SnapshotCache#around}.
 */
public record ChunkRenderContext(SectionPos origin, SectionSnapshot[] sections) {
}
