package com.yuyuframe.launcheragent.apimixin.v1_8_9.model;

import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiModel;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.render.entity.model.ChestBlockEntityModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Les modèles du jeu 1.8.9, recopiés dans un {@link UiModel} (point d'accès
 * {@code VANILLA_MODEL}) — pendant de {@code VanillaModels261}.
 *
 * <h2>Retourner le modèle</h2>
 *
 * Les modèles 1.8.9 sont écrits Y vers le BAS, et le jeu les dessine
 * retournés : {@code ChestBlockEntityRenderer} fait {@code translate(0, 1, 1)}
 * puis {@code scale(1, -1, -1)}. Un point du modèle {@code (x, y, z)} tombe
 * donc en {@code (x, 16 - y, 16 - z)} pixels dans le bloc. On applique ce
 * retournement {@code F = diag(1, -1, -1)} à la lecture, pour livrer le même
 * repère que les versions récentes (Y vers le haut) :
 * <ul>
 *   <li>sommets : {@code F · v} ;</li>
 *   <li>pivot d'une partie racine : {@code F · pivot + (0, 16, 16)} ; d'une
 *       partie enfant : {@code F · pivot} ;</li>
 *   <li>angles : {@code F · R · F} — X inchangé, Y et Z changent de signe.</li>
 * </ul>
 * Le couvercle s'ouvre donc avec le même angle négatif autour de X qu'en
 * 26.x ({@code TileEntityChestRenderer} écrit {@code -(ouverture · π/2)}).
 *
 * <p>Noms de parties alignés sur les versions récentes : {@code bottom},
 * {@code lid}, {@code lock}.
 */
public final class VanillaModels189 {

    private VanillaModels189() {
    }

    private static final Map<String, UiModel> CACHE = new HashMap<>();

    /** Le modèle {@code name}, ou {@code null} s'il est inconnu ou illisible (journalisé). */
    public static synchronized UiModel model(String name) {
        UiModel cached = CACHE.get(name);
        if (cached != null) return cached;
        try {
            UiModel.PartBuilder root = new UiModel.PartBuilder("root");
            switch (name) {
                case "chest": {
                    ChestBlockEntityModelAccessor189 chest = (ChestBlockEntityModelAccessor189) (Object) new ChestBlockEntityModel();
                    root.child(convert("bottom", chest.la$base(), true));
                    root.child(convert("lid", chest.la$lid(), true));
                    root.child(convert("lock", chest.la$lock(), true));
                    break;
                }
                default:
                    LauncherLog.err("[VanillaModels189] modèle inconnu : " + name);
                    return null;
            }
            UiModel model = new UiModel(java.util.Collections.singletonList(root.build()));
            CACHE.put(name, model);
            LauncherLog.ui(1, "[VanillaModels189] modèle '" + name + "' lu : " + model.faceCount() + " faces");
            return model;
        } catch (Throwable t) {
            LauncherLog.err("[VanillaModels189] lecture du modèle '" + name + "' : " + t);
            return null;
        }
    }

    private static UiModel.Part convert(String name, Object part, boolean topLevel) {
        ModelPartAccessor189 p = (ModelPartAccessor189) part;
        // Le décalage est en blocs, appliqué avant le pivot.
        float px = p.la$pivotX() + p.la$offsetX() * 16f;
        float py = p.la$pivotY() + p.la$offsetY() * 16f;
        float pz = p.la$pivotZ() + p.la$offsetZ() * 16f;
        UiModel.PartBuilder builder = new UiModel.PartBuilder(name)
            .pivot(px, topLevel ? 16f - py : -py, topLevel ? 16f - pz : -pz)
            .rotation(p.la$angleX(), -p.la$angleY(), -p.la$angleZ());
        List<?> boxes = p.la$cuboids();
        if (boxes != null) {
            for (Object box : boxes) addBox(builder, box);
        }
        List<?> children = p.la$children();
        if (children != null) {
            int index = 0;
            for (Object child : children) {
                builder.child(convert(name + "." + index++, child, false));
            }
        }
        return builder.build();
    }

    private static void addBox(UiModel.PartBuilder builder, Object box) {
        Object[] quads = ((ModelBoxAccessor189) box).la$quads();
        if (quads == null || quads.length == 0) return;
        float cx = 0, cy = 0, cz = 0;
        int count = 0;
        for (Object quad : quads) {
            for (Object vertex : ((TexturedQuadAccessor189) quad).la$positions()) {
                Vec3dAccessor189 pos = (Vec3dAccessor189) (Object) ((TexturePositionAccessor189) vertex).la$position();
                cx += (float) pos.la$x(); cy -= (float) pos.la$y(); cz -= (float) pos.la$z();
                count++;
            }
        }
        if (count == 0) return;
        cx /= count; cy /= count; cz /= count;
        for (Object quad : quads) {
            Object[] vertices = ((TexturedQuadAccessor189) quad).la$positions();
            if (vertices == null || vertices.length < 3) continue;
            float[] positions = new float[12];
            float[] uvs = new float[8];
            for (int i = 0; i < 4; i++) {
                TexturePositionAccessor189 v = (TexturePositionAccessor189) vertices[Math.min(i, vertices.length - 1)];
                Vec3dAccessor189 pos = (Vec3dAccessor189) (Object) v.la$position();
                positions[i * 3] = (float) pos.la$x();
                positions[i * 3 + 1] = (float) -pos.la$y();
                positions[i * 3 + 2] = (float) -pos.la$z();
                uvs[i * 2] = v.la$u();
                uvs[i * 2 + 1] = v.la$v();
            }
            builder.face(positions, uvs, cx, cy, cz);
        }
    }
}
