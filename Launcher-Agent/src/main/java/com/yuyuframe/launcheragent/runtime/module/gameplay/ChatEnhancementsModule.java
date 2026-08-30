package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.v26_1.core.ChatComponentAccessor261;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.regex.Pattern;
import com.yuyuframe.launcheragent.runtime.module.visual.FovModule;
import com.yuyuframe.launcheragent.runtime.module.visual.FullbrightModule;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

/**
 * Chat amélioré — deux fonctionnalités demandées ("comme dans le pvp-Mod"
 * n'existait PAS pour celles-ci dans PvP-Mod, contrairement au crosshair —
 * implémentées de zéro) :
 *
 * 1) Ping (son) quand le pseudo du joueur apparaît dans un message.
 * 2) Regroupe les messages IDENTIQUES consécutifs en une seule ligne
 *    "message (xN)" au lieu de spammer une ligne par répétition.
 *
 * AUCUN Mixin ici — {@code ChatHud.addMessage(Text)} prend un paramètre de
 * type référence MC ({@code eu}/Text, une interface) : capturer ce paramètre
 * dans un @Inject exigerait un type EXACT (même souci que rencontré partout
 * ailleurs cette session avec des types MC dans une signature de handler, ex.
 * MixinOldItemRotations189). Au lieu de ça : lecture PURE PAR RÉFLEXION dans
 * {@link #onTick()} (comme FovModule/FullbrightModule) — la réflexion Java
 * standard n'a AUCUNE de ces contraintes de correspondance de type au moment
 * de la compilation (Method.invoke prend des Object, résolu au runtime).
 *
 * Chemin d'accès : {@code MinecraftClient.inGameHud} (lettre "q") →
 * {@code InGameHud.chatHud} (lettre "l") → {@code ChatHud.messages} (lettre
 * "h", {@code List<ChatHudLine>}, INSÉRÉ EN TÊTE — le plus récent message est
 * toujours à l'index 0, vérifié par javap) → {@code ChatHudLine.getText()}
 * (lettre "a") → {@code Text.asUnformattedString()} (lettre "c").
 *
 * BUG TROUVÉ (build v503, log Minecraft complet fourni par l'utilisateur) :
 * lire {@code messages.get(0)} depuis {@link #onTick()} (appelé une fois par
 * TICK client, ~20/s) est intrinsèquement RACE-Y sur un salon très actif
 * (lobby Hypixel : plusieurs messages "a rejoint le lobby" par seconde) —
 * si DEUX messages arrivent entre deux ticks, seul le PLUS RÉCENT est
 * jamais lu comme "index 0" ; celui contenant la mention du joueur peut
 * être écrasé avant même d'être inspecté une seule fois. Confirmé en
 * étudiant un mod réel équivalent qui supporte déjà 26.1.2
 * (github.com/TerminalMC/ChatNotify, branche mc26.1) : il n'interroge
 * JAMAIS la liste d'affichage par polling — il MIXIN directement
 * {@code ChatListener.handleSystemMessage}/{@code handlePlayerChatMessage}
 * (méthodes appelées PAR LE JEU exactement une fois par message REÇU, avant
 * même l'affichage) pour être notifié de CHAQUE message sans exception,
 * quel que soit le débit. Fix : {@code ChatListenerMixin261} appelle
 * {@link #onChatMessageObserved()} en TAIL de ces deux méthodes — {@link
 * #checkChatState()} (le corps de l'ancien {@code onTick()}) tourne alors
 * de façon fiable, DÉCLENCHÉE PAR L'ÉVÉNEMENT plutôt qu'en espérant
 * l'attraper au bon tick. {@link #onTick()} continue de l'appeler aussi
 * (filet de sécurité redondant, sans risque — la dédup déjà en place
 * empêche tout double traitement).
 */
public final class ChatEnhancementsModule extends LauncherModule {

    @ConfigToggle(name = "Ping quand mon pseudo est mentionné", category = "Réglages")
    public boolean pingOnMention = true;

    @ConfigToggle(name = "Regrouper les messages répétés", category = "Réglages")
    public boolean stackRepeats = true;

    private static final Pattern COUNTER_SUFFIX = Pattern.compile("\\s*\\(x\\d+\\)$");
    /** Tag d'expéditeur en tête de ligne (ex: "{@code <Nom> }") — voir le fix du ping sur soi-même plus bas. */
    private static final Pattern SENDER_TAG_PREFIX = Pattern.compile("^<[^>]+>\\s*");

    private Object la$lastProcessedMessage;
    private String la$lastDistinctBase;
    private int la$repeatCount = 1;

    public ChatEnhancementsModule() {
        super("chat-enhancements", "Chat amélioré", "Ping quand ton pseudo est mentionné + regroupe les messages répétés", false,
            HookPoint.CHAT_RECEIVE);
        iconUrl = icons8("chat");
        // 26.1.2 — voir apimixin/v26_1/chat/ChatReceiveMixin261 (réconciliation
        // de l'ancien mixin.client.v26_1.ChatListenerMixin261, même déclencheur).
        VanillaHookRegistry.register(HookPoint.CHAT_RECEIVE, ctx -> { onChatMessageObserved(); return false; });
    }

    /** Appelé par {@code ChatListenerMixin261} (mixin/) ou {@code ChatReceiveMixin261} (apimixin/) — voir javadoc de tête pour le pourquoi (fiabilité face au polling par tick). */
    public static void onChatMessageObserved() {
        try {
            com.yuyuframe.launcheragent.runtime.ui.LauncherModule module =
                com.yuyuframe.launcheragent.runtime.ui.ModuleRegistry.get("chat-enhancements");
            if (module instanceof ChatEnhancementsModule && module.isEnabled()) {
                ((ChatEnhancementsModule) module).checkChatState();
            }
        } catch (Throwable t) {
            LauncherLog.err("[ChatEnhancementsModule] onChatMessageObserved: " + t);
        }
    }

    @Override
    public void onTick() {
        checkChatState();
    }

    /**
     * 26.1.2 sans réflexion (2026-08-26, §22 — audit modules) — {@code
     * Minecraft.gui}/{@code Gui.getChat()} (publics, via {@link
     * MinecraftAccessor261#la$gui()} pour rester sur une seule surface
     * d'accès à {@code Minecraft}) puis {@link ChatComponentAccessor261}
     * pour {@code allMessages}/{@code addMessage(...)} (privés, voir sa
     * javadoc). Logique IDENTIQUE au chemin réflexion ci-dessous (dédup par
     * IDENTITÉ d'objet, retrait du tag d'expéditeur avant la recherche du
     * pseudo, fusion des répétitions) — voir les commentaires de {@link
     * #checkChatState} pour l'historique de chaque bug déjà corrigé.
     *
     * @return {@code true} si traité ICI (pas de repli réflexion à faire —
     * y compris quand il n'y avait rien de neuf à traiter), {@code false}
     * si indisponible sur ce bracket (repli réflexion complet côté appelant).
     */
    /**
     * Le chemin par accessors est le SEUL depuis le 2026-08-27 — le repli
     * réflexif multi-bracket (résolution de Minecraft.inGameHud/gui →
     * ChatHud.chatHud/chat → messages/allMessages → ChatHudLine.getText/
     * content) a été supprimé avec lui.
     */
    private void checkChatState() {
        try {
            checkChatStateDirect();
        } catch (Throwable t) {
            LauncherLog.err("[ChatEnhancementsModule] checkChatState: " + t);
        }
    }

    private boolean checkChatStateDirect() {
        Gui gui = ClientData.gui();
        if (gui == null) return false;
        ChatComponent chat = gui.getChat();
        if (!(chat instanceof ChatComponentAccessor261)) return false;
        ChatComponentAccessor261 chatAcc = (ChatComponentAccessor261) chat;
        List<GuiMessage> messages = chatAcc.la$allMessages();
        if (messages == null || messages.isEmpty()) return false;

        GuiMessage headLine = messages.get(0);
        Component content = headLine.content();
        if (content == null) return false;
        String plain = content.getString();
        if (plain == null) return false;

        if (headLine == la$lastProcessedMessage) return true;
        la$lastProcessedMessage = headLine;

        if (pingOnMention) {
            String username = null;
            try {
                User user = ClientData.user();
                if (user != null) username = user.getName();
            } catch (Throwable ignored) {}
            String body = SENDER_TAG_PREFIX.matcher(plain).replaceFirst("");
            boolean matched = username != null && !username.isEmpty() && body.toLowerCase().contains(username.toLowerCase());
            if (matched) playPingSound();
        }

        if (stackRepeats) {
            String base = COUNTER_SUFFIX.matcher(plain).replaceAll("");
            if (base.equals(la$lastDistinctBase)) {
                la$repeatCount++;
                String combinedText = base + " (x" + la$repeatCount + ")";
                if (mergeRepeatedMessageDirect(chatAcc, chat, messages, headLine, combinedText)) {
                    if (!messages.isEmpty()) la$lastProcessedMessage = messages.get(0);
                }
            } else {
                la$repeatCount = 1;
                la$lastDistinctBase = base;
            }
        }
        return true;
    }

    /** Version directe (accessor) de {@link #mergeRepeatedMessage} — voir sa javadoc pour le détail de chaque champ repris tel quel (source/tag) et pourquoi {@code rescaleChat()} est nécessaire après. */
    private boolean mergeRepeatedMessageDirect(ChatComponentAccessor261 chatAcc, ChatComponent chat, List<GuiMessage> messages, GuiMessage headLine, String combinedText) {
        try {
            GuiMessageSource sourceValue = headLine.source();
            GuiMessageTag tagValue = headLine.tag();
            if (messages.size() >= 2) { messages.remove(0); messages.remove(0); }
            Component combined = Component.literal(combinedText);
            chatAcc.la$addMessage(combined, null, sourceValue, tagValue);
            chat.rescaleChat();
            return true;
        } catch (Throwable t) {
            if (!mergeErrorLogged) {
                mergeErrorLogged = true;
                LauncherLog.err("[ChatEnhancementsModule] mergeRepeatedMessageDirect: " + t);
            }
            return false;
        }
    }

    /**
     * LIMITATION PRÉ-EXISTANTE CORRIGÉE : {@code Entity.playSound(String,
     * float, float)} n'a JAMAIS existé sur AUCUN bracket (le vrai paramètre
     * est un {@code SoundEvent}, pas une chaîne) — cette recherche ne
     * trouvait donc jamais rien, le ping ne jouait jamais. Remplacé par le
     * VRAI mécanisme client (son local à l'utilisateur, pas un son "monde"
     * émis par l'entité — plus cohérent avec l'intention d'origine, une
     * notification personnelle) : {@code MinecraftClient.getSoundManager()}
     * → {@code SoundManager.play(SoundInstance)}, avec une instance créée
     * via {@code PositionedSoundInstance.ui(SoundEvent, float)} (Yarn) /
     * réel Mojang {@code SimpleSoundInstance.forUI(SoundEvent, float)}
     * (vérifiés par javap sur le jar client 26.1.2 réel). Son choisi :
     * {@code SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP} (Yarn) / réel
     * {@code EXPERIENCE_ORB_PICKUP} — équivalent moderne exact de l'ancien
     * "random.orb" (1.8.9) visé à l'origine.
     */
    private void playPingSound() {
        // SoundManager par ClientData (getSoundManager(), méthode publique) —
        // zéro réflexion. Le repli réflexif multi-bracket (résolution de
        // SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, de PositionedSoundInstance
        // .ui/forUI et de SoundManager.play) a été supprimé le 2026-08-27.
        // Piège à retenir pour un portage : le paramètre déclaré de play() est
        // l'INTERFACE SoundInstance, pas la classe concrète renvoyée par
        // forUI() — une recherche réflexive sur le type de retour échoue.
        try {
            net.minecraft.client.sounds.SoundManager soundManager = ClientData.soundManager();
            if (soundManager == null) return;
            soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f));
        } catch (Throwable t) {
            if (!pingErrorLogged) {
                pingErrorLogged = true;
                LauncherLog.err("[ChatEnhancementsModule] playPingSound: " + t);
            }
        }
    }

    private static boolean pingErrorLogged;

}
