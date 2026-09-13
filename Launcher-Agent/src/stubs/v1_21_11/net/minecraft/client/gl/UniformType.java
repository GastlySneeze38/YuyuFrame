package net.minecraft.client.gl;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code fyz}, une {@code enum} en jeu) —
 * voir {@code com.mojang.blaze3d.systems.RenderSystem}. Déclaré en classe à
 * champs statiques plutôt qu'en {@code enum} : seul {@code getstatic} nous
 * sert, et une {@code enum} de stub ferait générer par javac une table de
 * {@code switch} synthétique si l'on s'en servait dans un {@code switch}.
 */
public final class UniformType {

    public static final UniformType UNIFORM_BUFFER;
    public static final UniformType TEXEL_BUFFER;

    static {
        UNIFORM_BUFFER = stub();
        TEXEL_BUFFER = stub();
    }

    private UniformType() {
    }

    private static UniformType stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
