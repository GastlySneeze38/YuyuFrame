package com.yuyuframe.launcheragent.celeritas.v1_8_9.entity;

import net.minecraft.util.TypeFilterableList;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer.ChunkAccessor189;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.renderer.ClientChunkProviderAccessor189;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Rassemble les entités à dessiner en parcourant les chunks chargés.
 *
 * <p>Vanilla les prend dans la liste des chunks VISIBLES de son propre
 * renderer de terrain. Quand c'est le nôtre qui dessine, cette liste est
 * vide : on repart donc des chunks chargés.
 *
 * <p>Repris de l'adaptateur {@code ornithe} de Celeritas. Les entités sont
 * tenues en {@code Object} : c'est {@code RendererHooks189} qui les type.
 */
@YarnNamed
public class EntityGatherer {
    private final List<Object> entityList = new ArrayList<>();

    public void clear() {
        this.entityList.clear();
    }

    public List<Object> getLoadedEntityList(World world) {
        List<Object> out = this.entityList;

        Object provider = world.getChunkProvider();

        if (provider instanceof ClientChunkProviderAccessor189 accessor) {
            for (Object loaded : accessor.la$chunks()) {
                if (!((ChunkAccessor189) loaded).la$containsEntities()) {
                    continue;
                }

                Chunk chunk = (Chunk) loaded;
                TypeFilterableList[] entityMaps = chunk.getEntities();

                for (TypeFilterableList map : entityMaps) {
                    // Vue Collection : addAll/iterator sont des méthodes du JDK,
                    // que le traducteur de noms n'a pas à connaître.
                    Collection<?> entities = map;
                    out.addAll(entities);
                }
            }
        } else {
            // Autre fournisseur de chunks (monde intégré) : la liste générale.
            out.addAll(world.loadedEntities);
        }

        return out;
    }
}
