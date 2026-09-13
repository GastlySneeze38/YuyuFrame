package net.minecraft.world.food;

/**
 * Stub compile-only (26.1+) — valeurs nutritionnelles d'un aliment, portées
 * par le composant {@code DataComponents.FOOD} d'un {@code ItemStack}.
 *
 * <p>⚠️ {@code saturation()} est la saturation ABSOLUE depuis la 1.20.5, plus
 * l'ancien MODIFICATEUR qu'il fallait multiplier par {@code nutrition * 2}.
 * Confondre les deux donne un aperçu faux d'un facteur ~10 sur les aliments
 * riches. Vérifié sur le jar 26.1.2 : {@code FoodData.eat(FoodProperties)}
 * appelle {@code add(nutrition(), saturation())} sans conversion.
 *
 * <p>Le vrai type est un {@code record} (donc une classe finale de superclasse
 * {@code java.lang.Record}) — déclaré ici en classe finale ordinaire : seul le
 * genre classe/interface compte pour le descripteur d'appel, pas l'ascendance.
 */
public final class FoodProperties {
    private FoodProperties() {}
    /** Points de faim restaurés (demi-jambons ×2). */
    public int nutrition() { return 0; }
    /** Saturation restaurée, valeur ABSOLUE — voir la javadoc de classe. */
    public float saturation() { return 0f; }
    /** {@code true} pour un aliment consommable même barre de faim pleine (pomme dorée…). */
    public boolean canAlwaysEat() { return false; }
}
