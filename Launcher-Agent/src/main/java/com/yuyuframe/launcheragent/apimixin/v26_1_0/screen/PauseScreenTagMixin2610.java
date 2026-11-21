package com.yuyuframe.launcheragent.apimixin.v26_1_0.screen;

import com.yuyuframe.launcheragent.apimixin.data.PauseScreenTag;
import org.spongepowered.asm.mixin.Mixin;

/** Le menu Échap porte {@link PauseScreenTag} : les raccourcis le reconnaissent sans nommer sa classe. */
@Mixin(targets = "net.minecraft.client.gui.screens.PauseScreen")
public abstract class PauseScreenTagMixin2610 implements PauseScreenTag {
}
