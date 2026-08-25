package net.minecraft.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.sounds.SoundManager;

/**
 * Stub compile-only (26.1+) — cible de mixin ({@code ScreenSetMixin261}, etc.)
 * ET, depuis {@code GlobalUiRenderBridge261} (remplacement réflexion →
 * accès direct), point d'appel typé pour les méthodes PUBLIQUES vérifiées via
 * javap sur le jar client 26.1.2 réel : {@code getInstance()}, {@code
 * setScreen(Screen)}, {@code getMainRenderTarget()}, {@code getConnection()}
 * — descripteurs exacts requis pour qu'un appel direct (hors Mixin) résolve
 * la VRAIE classe au runtime (voir javadoc de {@code GlobalUiRenderBridge261}).
 *
 * {@code player}/{@code screen}/{@code mouseHandler}/{@code options} : TOUS
 * champs PUBLICS (vérifiés par javap) — accès direct, aucun {@code @Accessor}
 * nécessaire. {@code getUser()}/{@code getFps()} : {@code user}/{@code fps}
 * SONT privés, mais chacun a une méthode getter PUBLIQUE — même chose,
 * inutile de générer un Accessor Mixin dessus.
 *
 * BUG TROUVÉ (VerifyError "Bad type on operand stack" sur {@code
 * Minecraft.setScreen}, plusieurs heures de bissection) : une classe {@code
 * MinecraftAccessor261} existait ici avec 5 {@code @Accessor} Sponge Mixin
 * pour exposer window/screen/mouseHandler/options/user/fps — TOUS ces champs
 * s'avèrent en réalité publics ou dotés d'un getter public (vérifié par
 * javap), donc l'Accessor n'a jamais été nécessaire. Sa seule présence sur la
 * classe {@code Minecraft} (peu importe QUELS champs elle exposait — testé
 * avec seulement 4 champs restants, crash identique) corrompait le bytecode
 * généré pour {@code setScreen} au tissage (StackMapTable/local invalide,
 * incompatibilité probable Sponge Mixin 0.8.7 + class file version 69/Java
 * 25). Supprimée définitivement — accès direct partout à la place. Ne JAMAIS
 * recréer d'{@code @Accessor} sur {@code Minecraft} sans d'abord vérifier via
 * javap qu'aucun accès public n'existe déjà.
 */
public abstract class Minecraft {
    public static Minecraft getInstance() { return null; }
    public abstract void setScreen(Screen screen);
    public abstract RenderTarget getMainRenderTarget();
    public abstract ClientPacketListener getConnection();
    public abstract SoundManager getSoundManager();
    public abstract Window getWindow();
    public abstract User getUser();
    public abstract int getFps();

    public LocalPlayer player;
    public ClientLevel level;
    public Screen screen;
    public MouseHandler mouseHandler;
    public Options options;
}
