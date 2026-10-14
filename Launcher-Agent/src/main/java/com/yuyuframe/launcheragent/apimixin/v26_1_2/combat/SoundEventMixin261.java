package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.SoundPlayed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code HookPoint.SOUND_PLAYED} : un son joué par le serveur à une position.
 * Le coup arrêté au bouclier ne se voit que par là — le serveur joue le son du
 * blocage au lieu d'envoyer un paquet de dégâts. Voir {@code SoundEventMixin263}.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")
abstract class SoundEventMixin261 {

    @Inject(method = "handleSoundEvent(Lnet/minecraft/network/protocol/game/ClientboundSoundPacket;)V",
        at = @At("RETURN"), require = 0)
    private void la$dispatchSoundEvent(@Coerce Object packet, CallbackInfo ci) {
        SoundPlayed sound = CombatEvents261.sound(packet);
        if (sound != null) {
            VanillaHookRegistry.dispatch(HookPoint.SOUND_PLAYED, sound);
        }
    }
}
