package net.minecraft.client;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code KeybindRegisterMixin261}).
 * {@code getCameraType()}/{@code setCameraType(CameraType)} ajoutés pour
 * {@code FreelookModule} — méthodes publiques vérifiées par désassemblage
 * bytecode du vrai jar 26.1.2 (voir javadoc de {@code FreelookModule#onTick}),
 * appel direct sans réflexion une fois {@code Options} obtenu via {@code
 * MinecraftAccessor261#la$options()}.
 *
 * {@code keyUp}/{@code keyDown}/{@code keyLeft}/{@code keyRight}/{@code keyJump}
 * : champs PUBLICS (confirmé par l'usage universel dans l'écosystème
 * Fabric/Forge — contrairement à {@code fov}/{@code sensitivity}, eux
 * privés, voir {@code OptionsAccessor261}) — pour {@code KeystrokesModule}.
 */
public abstract class Options {
    public abstract CameraType getCameraType();
    public abstract void setCameraType(CameraType type);

    public KeyMapping keyUp;
    public KeyMapping keyDown;
    public KeyMapping keyLeft;
    public KeyMapping keyRight;
    public KeyMapping keyJump;
}
