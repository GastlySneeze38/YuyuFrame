package net.minecraft.entity;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code pk}).
 *
 * <p>Position, angles et {@code onGround} sont des CHAMPS publics sur cette
 * version — ni {@code getX()} ni {@code getYaw()}. Toujours lire ces champs
 * sur une variable typée {@code Entity} : javac écrit le type statique du
 * receveur comme propriétaire du {@code getfield}, et Yarn range le champ ici.
 */
public class Entity {

    public double x;

    public double y;

    public double z;

    public float yaw;

    public float pitch;

    public boolean onGround;

    protected Entity() {
    }

    public float getEyeHeight() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean hasVehicle() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isTouchingWater() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public boolean isSprinting() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    // ── Renderer (adaptateur Celeritas, voir celeritas/v1_8_9/FEATHER-YARN.md) ──

    /** {@code pk.P} — position au tick précédent, pour l'interpolation. */
    public double prevTickX;

    /** {@code pk.Q} */
    public double prevTickY;

    /** {@code pk.R} */
    public double prevTickZ;

    /** {@code pk.T} */
    public boolean noClip;

    /** {@code pk.l} — l'entité qui chevauche celle-ci. */
    public Entity rider;

    /** {@code pk.o} */
    public net.minecraft.world.World world;

    /** {@code pk.a(World)} */
    public void setWorld(net.minecraft.world.World world) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
