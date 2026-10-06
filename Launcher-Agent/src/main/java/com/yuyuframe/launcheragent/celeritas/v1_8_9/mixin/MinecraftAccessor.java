package com.yuyuframe.launcheragent.celeritas.v1_8_9.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Accessor("INSTANCE")
    static Minecraft celeritas$getInstance() {
        throw new AssertionError();
    }
}
