package net.minecraft.client;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code KeybindRegisterMixin261}).
 * {@code getCameraType()}/{@code setCameraType(CameraType)} ajoutés pour
 * {@code FreelookModule} — méthodes publiques vérifiées par désassemblage
 * bytecode du vrai jar 26.1.2 (voir javadoc de {@code FreelookModule#onTick}),
 * appel direct sans réflexion une fois {@code Options} obtenu via {@code
 * MinecraftAccessor261#la$options()}.
 */
public abstract class Options {
    public abstract CameraType getCameraType();
    public abstract void setCameraType(CameraType type);
}
