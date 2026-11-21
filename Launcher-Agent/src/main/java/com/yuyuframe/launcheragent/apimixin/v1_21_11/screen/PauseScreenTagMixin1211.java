package com.yuyuframe.launcheragent.apimixin.v1_21_11.screen;

import com.yuyuframe.launcheragent.apimixin.data.PauseScreenTag;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Le menu Échap ({@code GameMenuScreen} en Yarn, {@code grx} dans le jar)
 * porte {@link PauseScreenTag}. Aucun membre visé : rien à mettre dans la
 * refmap, seule la classe est traduite.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.GameMenuScreen")
public abstract class PauseScreenTagMixin1211 implements PauseScreenTag {
}
