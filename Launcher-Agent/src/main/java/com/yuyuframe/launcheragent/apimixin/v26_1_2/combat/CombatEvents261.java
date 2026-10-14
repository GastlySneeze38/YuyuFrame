package com.yuyuframe.launcheragent.apimixin.v26_1_2.combat;

import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import com.yuyuframe.launcheragent.apimixin.data.ItemCooldown;
import com.yuyuframe.launcheragent.apimixin.data.ItemUse;
import com.yuyuframe.launcheragent.apimixin.data.SoundPlayed;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.CooldownPacketAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.DamageEventPacketAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.SoundPacketAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.EntityEventPacketAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.ItemStackAccessor261;
import com.yuyuframe.launcheragent.apimixin.v26_1_2.core.LivingEntityAccessor261;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Des objets du jeu aux porteurs neutres de {@code apimixin/data} — ce que
 * les mixins de combat de la tranche 26.1.2 ont en commun. Les types du jeu
 * s'arrêtent ici : au-delà, les modules ne voient que des entiers et des
 * chaînes.
 *
 * <p>Chaque {@code instanceof} vaut « cet accessor s'est-il tissé ? » : s'il
 * ne l'est pas, la méthode rend {@code null} et le mixin ne transmet rien,
 * plutôt que de lever dans un gestionnaire de paquets du jeu.
 */
public final class CombatEvents261 {

    private CombatEvents261() {}

    /** Chemin de registre d'une entrée ({@code "mace_smash"}), ou {@code null}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static String path(Holder holder) {
        if (holder == null) return null;
        Optional<ResourceKey> key = holder.unwrapKey();
        if (key == null || !key.isPresent() || key.get().identifier() == null) return null;
        return key.get().identifier().getPath();
    }

    /** Identifiant d'une pile sans espace de noms, ou {@code null} si elle est vide. */
    public static String itemId(Object stack) {
        if (!(stack instanceof ItemStack) || ((ItemStack) stack).isEmpty()) return null;
        return stack instanceof ItemStackAccessor261 ? path(((ItemStackAccessor261) stack).la$typeHolder()) : null;
    }

    public static DamageEvent damage(Object packet) {
        if (!(packet instanceof DamageEventPacketAccessor261)) return null;
        DamageEventPacketAccessor261 p = (DamageEventPacketAccessor261) packet;
        return new DamageEvent(p.la$entityId(), path(p.la$sourceType()), p.la$sourceCauseId(), p.la$sourceDirectId());
    }

    public static EntityEvent entityEvent(Object packet) {
        if (!(packet instanceof EntityEventPacketAccessor261)) return null;
        EntityEventPacketAccessor261 p = (EntityEventPacketAccessor261) packet;
        return new EntityEvent(p.la$entityId(), p.la$eventId());
    }

    public static SoundPlayed sound(Object packet) {
        if (!(packet instanceof SoundPacketAccessor261)) return null;
        SoundPacketAccessor261 p = (SoundPacketAccessor261) packet;
        return new SoundPlayed(path(p.la$sound()), p.la$x(), p.la$y(), p.la$z());
    }

    public static ItemCooldown cooldown(Object packet) {
        if (!(packet instanceof CooldownPacketAccessor261)) return null;
        CooldownPacketAccessor261 p = (CooldownPacketAccessor261) packet;
        net.minecraft.resources.Identifier group = p.la$cooldownGroup();
        return new ItemCooldown(group == null ? null : group.getPath(), p.la$duration());
    }

    public static ItemUse itemUse(Object player, InteractionHand hand) {
        if (!(player instanceof LivingEntityAccessor261) || hand == null) return null;
        Object stack = ((LivingEntityAccessor261) player).la$itemInHand(hand);
        return new ItemUse(itemId(stack), hand == InteractionHand.MAIN_HAND);
    }
}
