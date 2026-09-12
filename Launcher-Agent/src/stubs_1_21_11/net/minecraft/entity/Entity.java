package net.minecraft.entity;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code bxc}) — racine de la hiérarchie
 * des entités.
 *
 * <h2>Pourquoi cette hiérarchie existe dans les stubs</h2>
 *
 * Elle n'est pas décorative : javac émet, pour un appel virtuel, le type
 * STATIQUE du receveur comme propriétaire de la méthode. Si un appel à
 * {@code getX()} se fait sur une variable typée {@code ClientPlayerEntity}, le
 * bytecode dit {@code ClientPlayerEntity.getX()D} — or Yarn range cette méthode
 * sous {@code Entity}, sa classe DÉCLARANTE. La traduction directe échoue
 * alors, et le repli « cherche ce nom partout » refuse de trancher dès que
 * plusieurs classes sans rapport portent le même nom — le cas de {@code getX},
 * {@code getYaw} ou {@code getHealth} (constaté au banc {@code RemapCheck},
 * 6 références introuvables).
 *
 * <p>En déclarant chaque méthode sur sa VRAIE classe déclarante et en typant la
 * variable en conséquence dans les liaisons, le bytecode nomme le bon
 * propriétaire et la traduction est exacte, sans repli.
 */
public class Entity {

    protected Entity() {
    }

    public double getX() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public double getY() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public double getZ() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getYaw() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getPitch() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public float getStandingEyeHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
