package net.minecraft.entity.effect;

import net.minecraft.text.Text;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cfk}) — pendant de
 * {@code MobEffect} en 26.1.2.
 *
 * <p>Le nom AFFICHÉ s'obtient ici par {@code getName()} et en 26.1.2 par
 * {@code getDisplayName()} ; les deux rendent un texte traduit, que la liaison
 * aplatit en {@link String} avant de le mettre dans {@code PlayerEffect}.
 */
public class StatusEffect {

    protected StatusEffect() {
    }

    /** Nom traduit, sans le niveau. */
    public Text getName() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** Couleur de l'effet, en RVB empaqueté. */
    public int getColor() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isBeneficial() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
