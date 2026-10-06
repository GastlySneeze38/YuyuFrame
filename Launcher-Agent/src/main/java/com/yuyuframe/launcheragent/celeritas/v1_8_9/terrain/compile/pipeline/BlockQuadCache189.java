package com.yuyuframe.launcheragent.celeritas.v1_8_9.terrain.compile.pipeline;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.celeritas.model.quad.properties.ModelQuadFacing;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Les modèles de blocs, décodés une fois : pour chaque modèle du jeu, ses
 * quads par face sous forme de {@link CachedQuad}. Notre code.
 *
 * <p>Partagé entre les fils de construction. Vidé au rechargement des
 * ressources, qui refait tous les modèles.
 */
@YarnNamed
public final class BlockQuadCache189 {

    private BlockQuadCache189() {
    }

    /** Les six faces du jeu, dans son ordre : bas, haut, nord, sud, ouest, est. */
    static final Direction[] DIRECTIONS = Direction.values();

    /** La face du moteur pour chaque face du jeu, par numéro d'ordre. */
    static final ModelQuadFacing[] FACINGS = {
        ModelQuadFacing.NEG_Y, ModelQuadFacing.POS_Y,
        ModelQuadFacing.NEG_Z, ModelQuadFacing.POS_Z,
        ModelQuadFacing.NEG_X, ModelQuadFacing.POS_X,
    };

    /** Un modèle décodé. */
    static final class Model {
        /** Les quads collés à chaque face du bloc : ils ne se dessinent que si la face est dégagée. */
        final CachedQuad[][] byFace = new CachedQuad[6][];
        /** Les quads à l'intérieur du bloc : toujours dessinés. */
        CachedQuad[] inner;
        boolean ambientOcclusion;
    }

    private static final CachedQuad[] NO_QUADS = new CachedQuad[0];

    /** Les modèles du jeu ne redéfinissent pas leur égalité : deux modèles distincts ont deux entrées. */
    private static final ConcurrentHashMap<Object, Model> MODELS = new ConcurrentHashMap<>();

    static Model of(BakedModel model) {
        Model cached = MODELS.get(model);
        if (cached == null) {
            cached = decode(model);
            MODELS.put(model, cached);
        }
        return cached;
    }

    private static Model decode(BakedModel model) {
        Model decoded = new Model();
        for (int face = 0; face < 6; face++) {
            decoded.byFace[face] = decode(model.getByDirection(DIRECTIONS[face]));
        }
        decoded.inner = decode(model.getQuads());
        decoded.ambientOcclusion = model.useAmbientOcclusion();
        return decoded;
    }

    private static CachedQuad[] decode(List<?> quads) {
        if (quads.isEmpty()) {
            return NO_QUADS;
        }
        CachedQuad[] out = new CachedQuad[quads.size()];
        for (int i = 0; i < out.length; i++) {
            BakedQuad quad = (BakedQuad) quads.get(i);
            out[i] = new CachedQuad(quad.getVertexData(), quad.hasColor() ? quad.getColorIndex() : -1,
                FACINGS[quad.getFace().ordinal()]);
        }
        return out;
    }

    /** Ressources rechargées : les modèles sont refaits. */
    public static void clear() {
        MODELS.clear();
    }
}
