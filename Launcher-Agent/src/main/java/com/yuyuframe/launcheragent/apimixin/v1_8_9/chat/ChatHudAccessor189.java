package com.yuyuframe.launcheragent.apimixin.v1_8_9.chat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Historique LOGIQUE du chat — champ privé final {@code messages}
 * ({@code avt.h}, vérifié javap). À ne pas confondre avec
 * {@code visibleMessages} ({@code avt.i}), les lignes déjà découpées que
 * {@code ChatHud.reset()} reconstruit depuis celui-ci.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.ChatHud")
public interface ChatHudAccessor189 {

    @Accessor("messages")
    List<Object> la$messages();
}
