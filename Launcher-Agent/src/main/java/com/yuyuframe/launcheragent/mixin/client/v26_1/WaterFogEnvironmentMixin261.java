package com.yuyuframe.launcheragent.mixin.client.v26_1;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.module.visual.ClearVisionModule;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * "Clear Water" — CORRIGÉ après un CRASH EN JEU (rapport
 * {@code crash-2026-07-14_19.57.28-client.txt}) : la 1ère version forçait
 * {@code isApplicable(FogType, Entity)} à {@code false} pour ne PLUS
 * sélectionner cet environnement du tout. Ça a planté dès que le joueur
 * était réellement sous l'eau : {@code FogRenderer.computeFogColor()} lève
 * {@code IllegalStateException("No color source environment found")} si
 * AUCUN environnement ne se déclare applicable alors qu'on EST dans le
 * type de brouillard correspondant — {@code isApplicable} n'est pas
 * qu'un simple "affiche/masque du brouillard", c'est aussi la sélection de
 * la SOURCE DE COULEUR obligatoire pour ce contexte.
 *
 * Fix : NE PLUS toucher à {@code isApplicable} du tout (reste toujours
 * sélectionné normalement, plus aucun risque de "aucune source trouvée") —
 * à la place, {@code @Inject} en TAIL de {@code setupFog(FogData, Camera,
 * ClientLevel, float, DeltaTracker)} (void, remplit {@code FogData} en
 * place par champs publics mutables) et repousse juste les distances de
 * brouillard très loin (biens au-delà de toute distance de rendu réaliste)
 * quand le module est actif — la couleur/l'environnement restent
 * sélectionnés normalement, seul le rendu VISUEL du brouillard disparaît.
 *
 * NON RETESTÉ EN JEU au moment de l'écriture (le crash lui-même a été
 * observé en jeu, ce correctif ne l'a pas encore été).
 *
 * Déclenché par "clear-vision" OU "no-fog" (retour utilisateur : NoFogModule
 * promet "eau, lave" dans sa propre description, doit donc AUSSI les
 * couvrir, indépendamment de clear-vision — voir AtmosphericFogEnvironmentMixin261
 * pour le vrai morceau manquant de NoFogModule, le brouillard de distance normal).
 *
 * BUG TROUVÉ (crash "Mixin apply ... failed... contains non-private static
 * method clearVisionOrNoFogEnabled()Z" — CRASH EN JEU à la connexion) :
 * Sponge Mixin interdit toute méthode NON PRIVÉE dans une classe Mixin (une
 * classe Mixin est "fondue" dans sa cible, jamais un vrai type autonome —
 * un appel statique cross-Mixin comme {@code WaterFogEnvironmentMixin261.xxx()}
 * depuis {@code LavaFogEnvironmentMixin261} ne fonctionne PAS comme du Java
 * normal). Le partage de ce petit contrôle a été abandonné — chaque Mixin
 * (Water/Lava/PowderedSnow) porte maintenant sa propre copie PRIVÉE
 * (duplication mineure acceptée, comme {@code FAR} déjà dupliqué partout ici).
 */
@Mixin(targets = "net.minecraft.client.renderer.fog.environment.WaterFogEnvironment")
public abstract class WaterFogEnvironmentMixin261 {

    private static final float FAR = 1_000_000f;

    @Inject(method = "setupFog(Lnet/minecraft/client/renderer/fog/FogData;Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;FLnet/minecraft/client/DeltaTracker;)V",
            at = @At("TAIL"), require = 0)
    private void la$clearFog(FogData fogData, Camera camera, ClientLevel level, float partialTick, DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            if (!shouldClear() || fogData == null) return;
            fogData.environmentalStart = FAR;
            fogData.environmentalEnd = FAR * 2f;
            fogData.renderDistanceStart = FAR;
            fogData.renderDistanceEnd = FAR * 2f;
        } catch (Throwable t) {
            LauncherLog.err("[WaterFogEnvironmentMixin261] la$clearFog: " + t);
        }
    }

    /**
     * Demandé explicitement ("ajoute 3 paramètres pour activer/désactiver
     * indépendamment le clear lava/water/powdersnow") — {@code no-fog}
     * coupe TOUJOURS tout (pas de granularité par liquide, voir sa propre
     * description) ; {@code clear-vision}, lui, ne clarifie L'EAU que si
     * {@link ClearVisionModule#clearWater} est activé.
     */
    private static boolean shouldClear() {
        LauncherModule noFog = ModuleRegistry.get("no-fog");
        if (noFog != null && noFog.isEnabled()) return true;
        LauncherModule clearVision = ModuleRegistry.get("clear-vision");
        if (!(clearVision instanceof ClearVisionModule) || !clearVision.isEnabled()) return false;
        return ((ClearVisionModule) clearVision).clearWater;
    }
}
