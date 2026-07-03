package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.regex.Pattern;

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
 */
public final class ChatEnhancementsModule extends LauncherModule {

    @ConfigToggle(name = "Ping quand mon pseudo est mentionné", category = "Réglages")
    public boolean pingOnMention = true;

    @ConfigToggle(name = "Regrouper les messages répétés", category = "Réglages")
    public boolean stackRepeats = true;

    private static final Pattern COUNTER_SUFFIX = Pattern.compile("\\s*\\(x\\d+\\)$");

    private String la$lastProcessedText;
    private String la$lastDistinctBase;
    private int la$repeatCount = 1;

    public ChatEnhancementsModule() {
        super("chat-enhancements", "Chat amélioré", "Ping quand ton pseudo est mentionné + regroupe les messages répétés", false);
    }

    @Override
    public void onTick() {
        try {
            Object mc = McReflect.minecraftClient();
            if (mc == null) return;
            Object inGameHud = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "inGameHud").get(mc);
            if (inGameHud == null) return;
            Object chatHud = McReflect.field(inGameHud.getClass(), "net/minecraft/client/gui/hud/InGameHud", "chatHud").get(inGameHud);
            if (chatHud == null) return;

            Field messagesField = McReflect.field(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "messages");
            if (messagesField == null) return;
            Object messagesObj = messagesField.get(chatHud);
            if (!(messagesObj instanceof List)) return;
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) messagesObj;
            if (messages.isEmpty()) return;

            Object headLine = messages.get(0);
            Method getText = McReflect.noArgMethod(headLine.getClass(), "net/minecraft/client/gui/hud/ChatHudLine", "getText");
            if (getText == null) return;
            Object textObj = getText.invoke(headLine);
            if (textObj == null) return;
            Method asUnformatted = McReflect.noArgMethod(textObj.getClass(), "net/minecraft/text/Text", "asUnformattedString");
            if (asUnformatted == null) return;
            String plain = (String) asUnformatted.invoke(textObj);
            if (plain == null) return;

            if (plain.equals(la$lastProcessedText)) return; // déjà traité, rien de nouveau
            la$lastProcessedText = plain;

            if (pingOnMention) {
                Object session = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "session").get(mc);
                if (session != null) {
                    Method getUsername = McReflect.noArgMethod(session.getClass(), "net/minecraft/client/util/Session", "getUsername");
                    if (getUsername != null) {
                        String username = (String) getUsername.invoke(session);
                        if (username != null && !username.isEmpty() && plain.toLowerCase().contains(username.toLowerCase())) {
                            Object player = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player").get(mc);
                            if (player != null) {
                                Method playSound = McReflect.method(player.getClass(), "net/minecraft/entity/Entity", "playSound",
                                    String.class, float.class, float.class);
                                if (playSound != null) playSound.invoke(player, "random.orb", 1.0f, 1.0f);
                            }
                        }
                    }
                }
            }

            if (stackRepeats) {
                String base = COUNTER_SUFFIX.matcher(plain).replaceAll("");
                if (base.equals(la$lastDistinctBase)) {
                    la$repeatCount++;
                    Field visibleField = McReflect.field(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "visibleMessages");
                    Object visibleObj = visibleField != null ? visibleField.get(chatHud) : null;

                    // Retire le doublon qu'on vient de voir + le précédent (déjà
                    // combiné ou seul) — cas courant : message court tenant sur
                    // une seule ligne. Les messages plus longs (repliés sur
                    // plusieurs lignes visibles) ne sont pas nettoyés
                    // parfaitement, limitation acceptée pour ce cas d'usage.
                    if (messages.size() >= 2) { messages.remove(0); messages.remove(0); }
                    if (visibleObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> visible = (List<Object>) visibleObj;
                        if (visible.size() >= 2) { visible.remove(0); visible.remove(0); }
                    }

                    Class<?> literalTextClass = McReflect.yarnClass("net/minecraft/text/LiteralText");
                    if (literalTextClass != null) {
                        Constructor<?> ctor = literalTextClass.getConstructor(String.class);
                        Object combined = ctor.newInstance(base + " (x" + la$repeatCount + ")");
                        Method addMessage = McReflect.method(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "addMessage", literalTextClass);
                        if (addMessage != null) {
                            addMessage.invoke(chatHud, combined);
                            la$lastProcessedText = base + " (x" + la$repeatCount + ")";
                        }
                    }
                } else {
                    la$repeatCount = 1;
                    la$lastDistinctBase = base;
                }
            }
        } catch (Throwable t) {
            LauncherLog.err("[ChatEnhancementsModule] onTick: " + t);
        }
    }
}
