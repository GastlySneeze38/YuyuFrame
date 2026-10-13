package com.yuyuframe.launcheragent.apimixin.v26_3.combat;

import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import com.yuyuframe.launcheragent.apimixin.data.ItemUse;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.DamageEventPacketAccessor263;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.EntityEventPacketAccessor263;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.ItemStackAccessor263;
import com.yuyuframe.launcheragent.apimixin.v26_3.core.LivingEntityAccessor263;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * Des objets du jeu aux porteurs neutres de {@code apimixin/data} — pendant
 * de {@code CombatEvents261} pour la tranche 26.3. Les types du jeu
 * s'arrêtent ici.
 *
 * <p>Chaque {@code instanceof} vaut « cet accessor s'est-il tissé ? » : s'il
 * ne l'est pas, la méthode rend {@code null} et le mixin ne transmet rien,
 * plutôt que de lever dans un gestionnaire de paquets du jeu.
 */
public final class CombatEvents263 {

    private CombatEvents263() {}

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
        return stack instanceof ItemStackAccessor263 ? path(((ItemStackAccessor263) stack).la$typeHolder()) : null;
    }

    public static DamageEvent damage(Object packet) {
        if (!(packet instanceof DamageEventPacketAccessor263)) return null;
        DamageEventPacketAccessor263 p = (DamageEventPacketAccessor263) packet;
        return new DamageEvent(p.la$entityId(), path(p.la$sourceType()), p.la$sourceCauseId(), p.la$sourceDirectId());
    }

    public static EntityEvent entityEvent(Object packet) {
        if (!(packet instanceof EntityEventPacketAccessor263)) return null;
        EntityEventPacketAccessor263 p = (EntityEventPacketAccessor263) packet;
        return new EntityEvent(p.la$entityId(), p.la$eventId());
    }

    public static ItemUse itemUse(Object player, InteractionHand hand) {
        if (!(player instanceof LivingEntityAccessor263) || hand == null) return null;
        Object stack = ((LivingEntityAccessor263) player).la$itemInHand(hand);
        return new ItemUse(itemId(stack), hand == InteractionHand.MAIN_HAND);
    }
}
