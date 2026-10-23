package com.yuyuframe.launcheragent.apimixin.v26_1_2.model;

import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiModel;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les modèles du jeu, recopiés dans un {@link UiModel} pour la brique 3D de
 * l'API graphique (point d'accès {@code VANILLA_MODEL}).
 *
 * <p>Le jeu construit le modèle lui-même ({@code createSingleBodyLayer} puis
 * {@code bakeRoot}) ; on n'en lit que le résultat, partie par partie, face par
 * face. La géométrie est donc exactement celle du jeu, et suivrait un
 * changement de Mojang sans rien toucher ici.
 *
 * <p>Repère : celui des modèles de bloc des versions récentes — pixels, Y vers
 * le haut — qui est aussi celui de {@link UiModel}. Rien à retourner.
 *
 * <p>Modèles connus : {@code "chest"} (parties {@code bottom}, {@code lid},
 * {@code lock} ; le couvercle et le loquet s'ouvrent ensemble en tournant
 * autour de X, angle négatif, comme {@code ChestModel.setupAnim}).
 */
public final class VanillaModels261 {

    private VanillaModels261() {
    }

    private static final Map<String, UiModel> CACHE = new HashMap<>();

    /** Le modèle {@code name}, ou {@code null} s'il est inconnu ou illisible (journalisé). */
    public static synchronized UiModel model(String name) {
        UiModel cached = CACHE.get(name);
        if (cached != null) return cached;
        Object root;
        try {
            switch (name) {
                case "chest":
                    root = ((LayerDefinitionAccessor261) (Object) ChestModelAccessor261.la$createSingleBodyLayer()).la$bakeRoot();
                    break;
                default:
                    LauncherLog.err("[VanillaModels261] modèle inconnu : " + name);
                    return null;
            }
            UiModel model = new UiModel(java.util.Collections.singletonList(convert("root", root)));
            CACHE.put(name, model);
            LauncherLog.ui(1, "[VanillaModels261] modèle '" + name + "' lu : " + model.faceCount() + " faces");
            return model;
        } catch (Throwable t) {
            LauncherLog.err("[VanillaModels261] lecture du modèle '" + name + "' : " + t);
            return null;
        }
    }

    private static UiModel.Part convert(String name, Object part) {
        ModelPartAccessor261 p = (ModelPartAccessor261) part;
        UiModel.PartBuilder builder = new UiModel.PartBuilder(name)
            .pivot(p.la$x(), p.la$y(), p.la$z())
            .rotation(p.la$xRot(), p.la$yRot(), p.la$zRot());
        List<?> cubes = p.la$cubes();
        if (cubes != null) {
            for (Object cube : cubes) addCube(builder, cube);
        }
        Map<String, ?> children = p.la$children();
        if (children != null) {
            for (Map.Entry<String, ?> child : children.entrySet()) {
                builder.child(convert(child.getKey(), child.getValue()));
            }
        }
        return builder.build();
    }

    private static void addCube(UiModel.PartBuilder builder, Object cube) {
        Object[] polygons = ((ModelPartCubeAccessor261) cube).la$polygons();
        if (polygons == null || polygons.length == 0) return;
        // Le centre du pavé, pour orienter chaque normale vers l'extérieur.
        float cx = 0, cy = 0, cz = 0;
        int count = 0;
        for (Object polygon : polygons) {
            for (Object vertex : ((ModelPartPolygonAccessor261) polygon).la$vertices()) {
                ModelPartVertexAccessor261 v = (ModelPartVertexAccessor261) vertex;
                cx += v.la$x(); cy += v.la$y(); cz += v.la$z();
                count++;
            }
        }
        if (count == 0) return;
        cx /= count; cy /= count; cz /= count;
        for (Object polygon : polygons) {
            Object[] vertices = ((ModelPartPolygonAccessor261) polygon).la$vertices();
            if (vertices == null || vertices.length < 3) continue;
            float[] positions = new float[12];
            float[] uvs = new float[8];
            for (int i = 0; i < 4; i++) {
                // Un triangle répète son dernier sommet.
                ModelPartVertexAccessor261 v = (ModelPartVertexAccessor261) vertices[Math.min(i, vertices.length - 1)];
                positions[i * 3] = v.la$x();
                positions[i * 3 + 1] = v.la$y();
                positions[i * 3 + 2] = v.la$z();
                uvs[i * 2] = v.la$u();
                uvs[i * 2 + 1] = v.la$v();
            }
            builder.face(positions, uvs, cx, cy, cz);
        }
    }
}
