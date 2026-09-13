package com.mojang.blaze3d.platform;

/**
 * Stub compile-only (26.1+) — juste assez pour typer le champ {@code
 * Minecraft.window} (voir {@code MinecraftAccessor261}) et appeler {@code
 * handle()}/{@code getGuiScaledWidth()} directement (méthodes publiques,
 * plus besoin de réflexion — voir l'ancienne implémentation de {@code
 * GlobalUiRenderBridge261}).
 *
 * Package RÉEL vérifié par javap sur le jar client 26.1.2 réel : {@code
 * com.mojang.blaze3d.platform.Window} (PAS {@code net.minecraft.client.
 * Window} — corrigé après avoir trouvé le même type de bug sur {@code
 * GuiRenderState}, voir sa javadoc de stub ; celui-ci n'avait pas encore
 * planté au moment de la correction, mais aurait fait exactement la même
 * InvalidAccessorException dès que MinecraftAccessor261 aurait été tissé).
 */
public abstract class Window {
    public long handle() { return 0L; }
    public int getGuiScaledWidth() { return 0; }
    /**
     * Ajouté le 2026-09-01 pour {@code SaturationModule} (alignement exact sur
     * la barre de faim vanilla, qui se place en coordonnées GUI). Signature
     * confirmée sur le vrai jar 26.1.2 : {@code getGuiScaledHeight ()I}.
     */
    public int getGuiScaledHeight() { return 0; }
}
