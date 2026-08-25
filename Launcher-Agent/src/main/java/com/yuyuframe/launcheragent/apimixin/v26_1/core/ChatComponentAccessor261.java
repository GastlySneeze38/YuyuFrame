package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

/**
 * Accessor + Invoker Sponge pour {@code ChatComponent.allMessages} (privé,
 * {@code List<GuiMessage>}) et {@code ChatComponent.addMessage(Component,
 * MessageSignature, GuiMessageSource, GuiMessageTag)} (privée, 4 arguments —
 * remplace {@code ChatHud.addMessage(Text)} à 1 argument, supprimée depuis la
 * refonte du chat) — vérifiés par javap sur le jar client 26.1.2 réel. Voir
 * {@code ChatEnhancementsModule} (fusion des messages répétés).
 *
 * Pourquoi un Invoker plutôt que la réflexion + {@code setAccessible(true)}
 * utilisée avant (2026-08-26, §22) : même raison que les autres Accessors de
 * ce paquet — une seule surface documentée pour l'état interne du jeu, et la
 * même voie de remapping ({@code MappingsRegistry}, déjà branchée dans Mixin)
 * pourra un jour couvrir d'autres brackets, ce qu'un appel réflexif sur un
 * nom codé en dur ne permettra jamais.
 */
@Mixin(targets = "net.minecraft.client.gui.components.ChatComponent")
public interface ChatComponentAccessor261 {
    @Accessor("allMessages")
    List<GuiMessage> la$allMessages();

    @Invoker("addMessage")
    void la$addMessage(Component content, MessageSignature signature, GuiMessageSource source, GuiMessageTag tag);
}
