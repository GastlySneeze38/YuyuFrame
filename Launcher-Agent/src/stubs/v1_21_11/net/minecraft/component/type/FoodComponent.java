package net.minecraft.component.type;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code dhf}) — pendant de
 * {@code FoodProperties} en 26.1.2. Mêmes trois accesseurs des deux côtés.
 */
public class FoodComponent {

    protected FoodComponent() {
    }

    /** Points de faim rendus. */
    public int nutrition() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Saturation rendue — ABSOLUE depuis la 1.20.5, plus un multiplicateur. */
    public float saturation() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Se mange même barre pleine (pomme dorée, ragoût suspect…). */
    public boolean canAlwaysEat() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
