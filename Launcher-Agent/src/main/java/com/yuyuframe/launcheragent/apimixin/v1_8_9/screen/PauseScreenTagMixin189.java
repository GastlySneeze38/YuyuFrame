package com.yuyuframe.launcheragent.apimixin.v1_8_9.screen;

import com.yuyuframe.launcheragent.apimixin.data.PauseScreenTag;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Le menu Échap ({@code GameMenuScreen} en Yarn legacy, {@code axp} dans le
 * jar 1.8.9) porte {@link PauseScreenTag}. Aucun membre visé.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.GameMenuScreen")
public abstract class PauseScreenTagMixin189 implements PauseScreenTag {
}
