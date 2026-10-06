package com.yuyuframe.launcheragent.celeritas.v1_8_9.mixin;

//? if >=1.8 {
/*import net.minecraft.client.gui.overlay.DebugOverlay;
import net.minecraft.text.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.yuyuframe.launcheragent.celeritas.v1_8_9.debug.CeleritasDebugStrings;

import java.util.List;

@Mixin(DebugOverlay.class)
public class DebugOverlayMixin {
    @Inject(method = "getSystemInfo", at = @At("RETURN"))
    private void appendCeleritasSystemInfo(CallbackInfoReturnable<List<String>> cir) {
        var strings = cir.getReturnValue();
        strings.add("");
        strings.addAll(CeleritasDebugStrings.getStringsToRender().stream().map(pair -> {
            // TODO
            if (pair.right() == 0xFF55FF55) {
                return Formatting.GREEN + pair.left();
            } else {
                return pair.left();
            }
        }).toList());
    }
}
*///?}