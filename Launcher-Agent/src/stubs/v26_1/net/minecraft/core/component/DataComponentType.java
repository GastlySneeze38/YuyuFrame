package net.minecraft.core.component;

/**
 * Stub compile-only (26.1+) — clé typée d'un composant d'objet ({@code
 * DataComponents.FOOD} et compagnie). Purement un jeton : aucune méthode
 * n'est appelée dessus, il ne sert qu'à typer l'argument de
 * {@code ItemStack.get(...)}.
 *
 * <p>⚠️ {@code interface}, PAS {@code class} — déclaré en classe à la
 * première écriture, {@code tools/audit_stubs.py} l'a rattrapé aussitôt
 * (mode C). C'est exactement le piège qui avait fait échouer le biome une
 * seconde fois : le genre décide entre {@code invokeinterface} et
 * {@code invokevirtual}, et se tromper donne un
 * {@code IncompatibleClassChangeError} au runtime, jamais à la compilation.
 * Vérifié sur le jar 26.1.2.
 */
public interface DataComponentType<T> {
}
