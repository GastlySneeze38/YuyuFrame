package com.yuyuframe.launcheragent.apimixin.v1_21_11.chat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Historique complet du chat sur 1.21.11 — pour
 * {@code AccessPoint.CHAT_HEAD_MESSAGE} et {@code CHAT_MERGE_REPEATED}.
 *
 * <h2>Un seul accessor, là où la 26.1.2 en a deux</h2>
 *
 * Vérifié par {@code javap} sur le jar client réel : {@code addMessage(Text,
 * MessageSignatureData, MessageIndicator)} ({@code method_44811}) et
 * {@code refresh()} ({@code method_1817}) sont PUBLIQUES ici, alors que leurs
 * pendants 26.1.2 demandent un {@code @Invoker}. Seul le champ
 * {@code messages} ({@code field_2061}) est privé.
 *
 * <h2>Pourquoi une {@code List} brute</h2>
 *
 * La liste contient des {@code ChatHudLine}, un type OBFUSQUÉ que cette unité
 * de compilation ne peut pas nommer (elle est compilée contre le jar du jeu
 * sous des noms qui n'existent pas à l'exécution). Ce n'est pas un obstacle :
 * les génériques sont effacés, le descripteur du champ est
 * {@code Ljava/util/List;}, et une {@code List} brute y correspond exactement.
 * Les éléments ressortent en {@link Object} et sont interprétés par
 * {@code AccessorBindings1211}, qui, lui, a le droit de les nommer.
 *
 * <p>Le plus RÉCENT est à l'index 0 (insertion en tête), comme en 26.1.2.
 */
@Mixin(targets = "net.minecraft.client.gui.hud.ChatHud")
public interface ChatHudAccessor1211 {

    @Accessor("messages")
    List<Object> la$messages();
}
