package net.minecraft.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** Stub compile-only (26.1+) — voir {@link OrderedSubmitNodeCollector}. */
public interface SubmitNodeCollector extends OrderedSubmitNodeCollector {

    /** {@code SubmitNodeCollector$CustomGeometryRenderer} — écrit des sommets dans le tampon du type de rendu demandé. */
    interface CustomGeometryRenderer {
        void render(PoseStack.Pose pose, VertexConsumer buffer);
    }
}
