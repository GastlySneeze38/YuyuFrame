package net.minecraft.text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code yh}) — pendant de
 * {@code Component} en 26.1.2.
 *
 * <p>{@code getString()} n'est PAS dans les mappings Yarn : elle vient de
 * {@code com.mojang.brigadier.Message}, bibliothèque externe non obfusquée, et
 * {@code Text} la redéclare en méthode {@code default} (vérifié par
 * {@code javap} sur le jar réel). Son nom est donc le vrai nom d'exécution, et
 * le remappeur la laisse telle quelle — c'est correct, même s'il peut la
 * signaler comme « membre inconnu de Yarn ».
 */
public interface Text {

    String getString();
}
