package net.minecraft.entity;

/**
 * Stub compile-only 1.21.11, nom Yarn ({@code cgv}) — même nom qu'en 26.1.2.
 * Même forme que {@code Hand}, mêmes raisons.
 *
 * <p>Seuls les quatre emplacements d'armure sont déclarés : c'est tout ce que
 * les liaisons servent. À savoir pour un portage : l'armure se lisait par
 * {@code getArmorSlot(int)} — un ENTIER magique — avant la refonte
 * « Flattening » (~1.13), qui a introduit cet enum.
 */
public final class EquipmentSlot {

    public static final EquipmentSlot HEAD;
    public static final EquipmentSlot CHEST;
    public static final EquipmentSlot LEGS;
    public static final EquipmentSlot FEET;

    static {
        HEAD = stub();
        CHEST = stub();
        LEGS = stub();
        FEET = stub();
    }

    private EquipmentSlot() {
    }

    private static EquipmentSlot stub() {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
