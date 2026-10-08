package net.minecraft.client.render.command;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/** Stub compile-only 1.21.11, nom Yarn ({@code hpo}) — voir {@link RenderCommandQueue}. */
public interface OrderedRenderCommandQueue extends RenderCommandQueue {

    /** {@code hpo$a} — écrit des sommets dans le tampon de la couche demandée. {@code render} n'est pas obfusquée. */
    interface Custom {
        void render(MatrixStack.Entry entry, VertexConsumer buffer);
    }
}
