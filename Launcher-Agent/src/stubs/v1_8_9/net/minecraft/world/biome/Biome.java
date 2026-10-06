package net.minecraft.world.biome;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code ady}).
 *
 * <p>Pas de registre ni d'identifiant : seul le NOM d'affichage anglais, champ
 * public ({@code "Extreme Hills"}, {@code "MushroomIsland"}…).
 */
public abstract class Biome {

    public String name;

    private Biome() {
    }

    /** {@code ady.e(I)} — le biome d'un identifiant, ou {@code null} s'il n'existe pas. */
    public static Biome byId(int id) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
