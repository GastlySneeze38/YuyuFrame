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
            // 26.1+ : InGameHud→Gui, champ "inGameHud"→"gui" ; ChatHud→ChatComponent,
            // champ "chatHud"→"chat" (vérifiés par javap sur le jar client 26.1.2
            // réel). BUG TROUVÉ (confirmé par l'utilisateur, NullPointerException en
            // boucle sur onTick) : ces deux lignes appelaient .get(...) directement
            // sur le retour de field() SANS vérifier null d'abord — dès que le nom
            // ne résolvait plus (comme ici avant ce fix), NullPointerException
            // immédiate à CHAQUE tick, jamais avalée par le catch générique
            // puisqu'elle survient hors du bloc protégé... en fait si, avalée par
            // le catch du bas, mais reloggée sans relâche vu qu'onTick() est
            // rappelé en boucle — d'où le spam.
            Field inGameHudField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "inGameHud", "gui");
            if (inGameHudField == null) return;
            Object inGameHud = inGameHudField.get(mc);
            if (inGameHud == null) return;
            Field chatHudField = McReflect.field(inGameHud.getClass(), "net/minecraft/client/gui/hud/InGameHud", "chatHud", "chat");
            if (chatHudField == null) return;
            Object chatHud = chatHudField.get(inGameHud);
            if (chatHud == null) return;

            // 26.1+ : ChatHud.messages n'existe plus (remplacé par un modèle à
            // deux listes, allMessages/trimmedMessages, avec addMessage() devenu
            // PRIVÉ et à 4 paramètres) — repli en LECTURE SEULE sur "allMessages"
            // pour le ping/détection de répétition ; la fusion visuelle des
            // messages répétés (suppression + réinsertion) ne peut plus
            // fonctionner sur cette version (addMessage(Text) à 1 argument
            // n'existe plus du tout) — dégrade proprement plus bas (aucun crash,
            // juste la fusion visuelle qui ne s'applique pas sur 26.1+).
            Field messagesField = McReflect.field(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "messages", "allMessages");
            if (messagesField == null) return;
            Object messagesObj = messagesField.get(chatHud);
            if (!(messagesObj instanceof List)) return;
            @SuppressWarnings("unchecked")
            List<Object> messages = (List<Object>) messagesObj;
            if (messages.isEmpty()) return;

            Object headLine = messages.get(0);
            // 26.1+ : ChatHudLine.getText()→GuiMessage.content() (accesseur de
            // record), Text.asUnformattedString()→Component.getString() (vérifiés
            // par javap).
            Method getText = McReflect.noArgMethod(headLine.getClass(), "net/minecraft/client/gui/hud/ChatHudLine", "getText", "content");
            if (getText == null) return;
            Object textObj = getText.invoke(headLine);
            if (textObj == null) return;
            Method asUnformatted = McReflect.noArgMethod(textObj.getClass(), "net/minecraft/text/Text", "asUnformattedString", "getString");
            if (asUnformatted == null) return;
            String plain = (String) asUnformatted.invoke(textObj);
            if (plain == null) return;

            if (plain.equals(la$lastProcessedText)) return; // déjà traité, rien de nouveau
            la$lastProcessedText = plain;

            if (pingOnMention) {
                // 26.1+ : champ "session"→"user" (type Session→User), méthode
                // "getUsername"→"getName" (vérifiés par javap).
                Field sessionField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "session", "user");
                Object session = sessionField != null ? sessionField.get(mc) : null;
                if (session != null) {
                    Method getUsername = McReflect.noArgMethod(session.getClass(), "net/minecraft/client/util/Session", "getUsername", "getName");
                    if (getUsername != null) {
                        String username = (String) getUsername.invoke(session);
                        if (username != null && !username.isEmpty() && plain.toLowerCase().contains(username.toLowerCase())) {
                            Field playerField = McReflect.field(mc.getClass(), "net/minecraft/client/MinecraftClient", "player");
                            Object player = playerField != null ? playerField.get(mc) : null;
                            if (player != null) {
                                // LIMITATION PRÉ-EXISTANTE (pas spécifique à 26.1+) :
                                // Entity.playSound() n'a jamais pris de signature
                                // (String,float,float) — le vrai paramètre est un
                                // SoundEvent, pas une chaîne — cette recherche ne
                                // trouve donc jamais rien, sur AUCUN bracket ; le son
                                // de ping ne joue donc jamais (dégradation propre,
                                // aucun crash, juste une fonctionnalité inopérante).
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

                    // 26.1+ : ChatHud.addMessage(Text) à 1 argument N'EXISTE PLUS
                    // (devenu privé, 4 paramètres, voir plus haut) et "LiteralText"
                    // n'existe plus comme classe (remplacé par Component.literal(...),
                    // une factory statique) — la reconstruction du message combiné
                    // "xN" est donc IMPOSSIBLE sur cette version avec ce mécanisme.
                    // BUG ÉVITÉ : vérifier ICI, AVANT toute suppression destructive
                    // des messages existants — sinon on supprimerait 2 messages du
                    // tchat SANS JAMAIS les remplacer par le message combiné (la
                    // vérification était auparavant faite APRÈS la suppression).
                    Class<?> literalTextClass = McReflect.yarnClass("net/minecraft/text/LiteralText");
                    Method addMessage = literalTextClass != null
                        ? McReflect.method(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "addMessage", literalTextClass)
                        : null;
                    if (literalTextClass == null || addMessage == null) return;

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

                    Constructor<?> ctor = literalTextClass.getConstructor(String.class);
                    Object combined = ctor.newInstance(base + " (x" + la$repeatCount + ")");
                    addMessage.invoke(chatHud, combined);
                    la$lastProcessedText = base + " (x" + la$repeatCount + ")";
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
