package net.minecraft.entity;

/**
 * Stub compile-only 1.8.9, nom Yarn legacy ({@code pk}).
 *
 * <p>Position, angles et {@code onGround} sont des CHAMPS publics sur cette
 * version — ni {@code getX()} ni {@code getYaw()}. Toujours lire ces champs
 * sur une variable typée {@code Entity} : javac écrit le type statique du
 * receveur comme propriétaire du {@code getfield}, et Yarn range le champ ici.
 */
public class Entity implements net.minecraft.command.CommandSource {

    /**
     * {@code m.f_()} — Yarn range cette méthode sur {@code CommandSource} :
     * toujours l'appeler sur une variable de ce type, jamais sur {@code Entity}.
     */
    @Override
    public net.minecraft.text.Text getName() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    public double x;

    public double y;

    public double z;

    /** {@code pk.p} — position au tick précédent. */
    public double prevX;

    /** {@code pk.r} */
    public double prevZ;

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

    /** {@code pk.W} — nombre de ticks vécus ; 0 à la toute première image. */
    public int ticksAlive;

    /** {@code pk.ax()} */
    public boolean isInvisible() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.f(wn)} — invisible, sauf pour un spectateur. */
    public boolean isInvisibleTo(net.minecraft.entity.player.PlayerEntity player) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.K} */
    public float height;

    /** {@code pk.b(F)} — lumière du bloc où se trouve l'entité, ciel dans les 16 bits hauts. */
    public int getLightmapCoordinates(float tickDelta) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.h(pk)} */
    public double squaredDistanceTo(Entity other) {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.at()} */
    public boolean isOnFire() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.aN()} — le nom personnalisé s'affiche en permanence au-dessus de l'entité (hologrammes). */
    public boolean isCustomNameVisible() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.ah} — toujours dessinée, même hors du champ de la caméra. */
    public boolean ignoreCameraFrustum;

    /** {@code pk.aR()} */
    public net.minecraft.util.math.Box getBoundingBox() {
        throw new UnsupportedOperationException("stub compile-only");
    }

    /** {@code pk.l} — l'entité qui chevauche celle-ci. */
    public Entity rider;

    /** {@code pk.o} */
    public net.minecraft.world.World world;

    /** {@code pk.a(World)} */
    public void setWorld(net.minecraft.world.World world) {
        throw new UnsupportedOperationException("stub compile-only");
    }
}
