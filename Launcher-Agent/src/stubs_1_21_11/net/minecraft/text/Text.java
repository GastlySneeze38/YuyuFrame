package net.minecraft.text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code yh}) — pendant de
 * {@code Component} en 26.1.2.
 *
 * <p>{@code getString()} n'est PAS dans les mappings Yarn : elle vient de
 * {@code com.mojang.brigadier.Message}, bibliothèque externe non obfusquée, et
 * {@code Text} la redéclare en méthode {@code default} (vérifié par
 * {@code javap} sur le jar réel). Son nom est donc le vrai nom d'exécution, et
 * le remappeur la laisse telle quelle.
 *
 * <p>Deux avertissements en découlent au chargement, tous deux ATTENDUS :
 * {@code [Mappings] … plusieurs classes candidates aux noms runtime différents
 * → non traduit} (la recherche globale de {@code getString()Ljava/lang/String;}
 * tombe sur des homonymes d'autres classes, qui ne s'accordent pas — c'est ce
 * désaccord qui la protège d'une traduction fautive) puis
 * {@code [YarnNamedRemapper] membre inconnu de Yarn, laissé tel quel}. Le banc
 * {@code RemapCheck} vérifie l'issue sur le jar réellement exécuté : l'appel
 * final vise bien {@code yh.getString()}, qui existe.
 */
public interface Text {

    String getString();
}
