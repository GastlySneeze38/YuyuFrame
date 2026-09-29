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
 * champs PUBLICS (vérifiés par javap) — un accès direct reste toujours
 * possible sans {@code @Accessor}. {@code getUser()}/{@code getFps()} :
 * {@code user}/{@code fps} SONT privés, mais chacun a une méthode getter
 * PUBLIQUE — accès direct possible là aussi, sans Accessor Mixin.
 *
 * ⚠️ HISTORIQUE (2026-08 — RÉSOLU, voir {@code MinecraftAccessor261}) : un
 * VerifyError ("Bad type on operand stack" sur {@code Minecraft.setScreen})
 * avait été bissecté jusqu'à la SEULE PRÉSENCE d'un {@code @Accessor} Sponge
 * Mixin sur {@code Minecraft} (peu importe quels champs il exposait — testé
 * avec seulement 4 champs restants, crash identique). {@code
 * MinecraftAccessor261} a depuis été confirmé fonctionnel (tissage sans
 * erreur, exercé en jeu) — la cause racine du VerifyError d'alors n'était PAS
 * "un Accessor sur Minecraft est intrinsèquement impossible", elle est
 * ailleurs et corrigée. L'architecture du projet PRÉFÈRE désormais passer
 * par {@code MinecraftAccessor261} pour ces champs plutôt que par un cast
 * direct {@code (Minecraft) mc}, même quand le champ est public — une seule
 * surface documentée pour tout accès à l'état interne de {@code Minecraft}
 * (2026-08-25, §19/§20). Avant de toucher à nouveau ce mixin, vérifier que le
 * problème historique reste bien absent (démarrage + ouverture d'un écran,
 * ex: menu pause) plutôt que de supposer l'un ou l'autre sans preuve.
 */
public abstract class Minecraft {
    public static Minecraft getInstance() { return null; }
    // 26.2 : setScreen et le champ screen sont passés dans le nouveau Gui
    // (voir apimixin/v26_2/core/GuiAccessor262) — retirés de ce stub.
    // ⚠️ getMainRenderTarget N'EXISTE PLUS en 26.2 (surface de fenêtre
    // GpuSurface à la place) : gardé ici le temps de l'étape 3 (couche GPU)
    // pour que l'unité compile, signalé par tools/refcheck261.py.
    public abstract RenderTarget getMainRenderTarget();
    public abstract ClientPacketListener getConnection();
    public abstract SoundManager getSoundManager();
    public abstract Window getWindow();
    public abstract User getUser();
    public abstract int getFps();

    public LocalPlayer player;
    public ClientLevel level;
    public MouseHandler mouseHandler;
    public Options options;
    /** Public final en jeu (vérifié sur le jar) — pour {@code GuiGraphicsExtractor.itemDecorations}. */
    public net.minecraft.client.gui.Font font;
}
