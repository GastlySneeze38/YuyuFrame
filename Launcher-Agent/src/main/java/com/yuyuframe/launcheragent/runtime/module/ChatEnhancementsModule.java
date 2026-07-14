package com.yuyuframe.launcheragent.runtime.module;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.mapping.McReflect;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.ConfigToggle;

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
                Method getUsername = session != null
                    ? McReflect.noArgMethod(session.getClass(), "net/minecraft/client/util/Session", "getUsername", "getName")
                    : null;
                String username = getUsername != null ? (String) getUsername.invoke(session) : null;
                boolean matched = username != null && !username.isEmpty() && plain.toLowerCase().contains(username.toLowerCase());

                // Diag pour CHAQUE nouveau message (pas juste une fois) — borné
                // par la dédup déjà en place plus haut (une ligne par message
                // RÉELLEMENT nouveau, pas par tick). Sans ça, impossible de
                // savoir si un message précis n'a pas matché (mauvaise
                // comparaison) ou n'a même jamais été vu comme "le plus récent"
                // (plusieurs messages arrivés dans le même tick — un lobby
                // Hypixel très actif en reçoit beaucoup — voir
                // project_mc_261_port.md).
                LauncherLog.info("[ChatEnhancementsModule] pingOnMention diag: sessionField=" + sessionField
                    + " session=" + session + " getUsername=" + getUsername + " username=" + username
                    + " plain=\"" + plain + "\" matched=" + matched);

                if (matched) playPingSound(mc);
            }

            if (stackRepeats) {
                String base = COUNTER_SUFFIX.matcher(plain).replaceAll("");
                if (base.equals(la$lastDistinctBase)) {
                    la$repeatCount++;
                    String combinedText = base + " (x" + la$repeatCount + ")";
                    if (mergeRepeatedMessage(chatHud, messages, headLine, getText, combinedText)) {
                        la$lastProcessedText = combinedText;
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

    private static boolean pingSoundResolveAttempted;
    private static Object cachedOrbSoundEvent;
    private static Method cachedGetSoundManager;
    private static Method cachedSoundManagerPlay;
    private static Method cachedForUi;

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
    private void playPingSound(Object mc) {
        try {
            if (!pingSoundResolveAttempted) {
                pingSoundResolveAttempted = true;
                Class<?> soundEventsClass = McReflect.yarnClass("net/minecraft/sound/SoundEvents", "net.minecraft.sounds.SoundEvents");
                Field orbField = soundEventsClass != null
                    ? McReflect.field(soundEventsClass, "net/minecraft/sound/SoundEvents", "ENTITY_EXPERIENCE_ORB_PICKUP", "EXPERIENCE_ORB_PICKUP")
                    : null;
                cachedOrbSoundEvent = orbField != null ? orbField.get(null) : null;

                cachedGetSoundManager = McReflect.noArgMethod(mc.getClass(), "net/minecraft/client/MinecraftClient", "getSoundManager");

                if (cachedOrbSoundEvent != null) {
                    Class<?> soundInstanceClass = McReflect.yarnClass("net/minecraft/client/sound/PositionedSoundInstance", "net.minecraft.client.resources.sounds.SimpleSoundInstance");
                    cachedForUi = soundInstanceClass != null
                        ? McReflect.method(soundInstanceClass, "net/minecraft/client/sound/PositionedSoundInstance", "ui", "forUI", cachedOrbSoundEvent.getClass(), float.class)
                        : null;
                }
                // Diag une seule fois : sans ça, un échec de résolution silencieux
                // (aucune exception, juste un "return" plus bas) ne laisse AUCUNE
                // trace dans les logs — impossible à diagnostiquer à distance.
                LauncherLog.info("[ChatEnhancementsModule] playPingSound diag: orbSound=" + cachedOrbSoundEvent
                    + " getSoundManager=" + cachedGetSoundManager + " forUi=" + cachedForUi);
            }
            if (cachedOrbSoundEvent == null || cachedGetSoundManager == null || cachedForUi == null) return;

            Object soundManager = cachedGetSoundManager.invoke(mc);
            if (soundManager == null) return;
            if (cachedSoundManagerPlay == null) {
                // Le paramètre déclaré de play() est l'INTERFACE SoundInstance,
                // pas la classe concrète PositionedSoundInstance/SimpleSoundInstance
                // renvoyée par "ui"/"forUI" — utiliser cachedForUi.getReturnType()
                // ici échouerait la recherche par réflexion (types EXACTS requis),
                // même piège que documenté dans mergeRepeatedMessage() plus bas.
                Class<?> soundInstanceInterface = McReflect.yarnClass("net/minecraft/client/sound/SoundInstance", "net.minecraft.client.resources.sounds.SoundInstance");
                cachedSoundManagerPlay = soundInstanceInterface != null
                    ? McReflect.method(soundManager.getClass(), "net/minecraft/client/sound/SoundManager", "play", soundInstanceInterface)
                    : null;
            }
            if (cachedSoundManagerPlay == null) return;

            Object soundInstance = cachedForUi.invoke(null, cachedOrbSoundEvent, 1.0f);
            cachedSoundManagerPlay.invoke(soundManager, soundInstance);
        } catch (Throwable t) {
            if (!pingErrorLogged) {
                pingErrorLogged = true;
                LauncherLog.err("[ChatEnhancementsModule] playPingSound: " + t);
            }
        }
    }

    private static boolean pingErrorLogged;
    private static boolean mergeErrorLogged;

    /**
     * 26.1+ : {@code ChatHud.addMessage(Text)} à 1 argument N'EXISTE PLUS
     * (devenu PRIVÉ, 4 paramètres — {@code Component, MessageSignature,
     * GuiMessageSource, GuiMessageTag}, vérifié par javap) et
     * {@code LiteralText} n'existe plus comme classe (remplacé par {@code
     * Component.literal(String)}, une factory statique — Yarn :
     * {@code Text.literal(String)}, même nom des deux côtés). Résolu ici en
     * appelant la vraie méthode PRIVÉE par réflexion (McReflect force
     * {@code setAccessible(true)}) plutôt que de renoncer à la fusion —
     * les 3 autres paramètres ({@code signature}/{@code source}/{@code tag})
     * sont repris TELS QUELS depuis le message d'origine (via les accesseurs
     * de {@code headLine}, mêmes noms {@code signature()}/{@code source()}/
     * {@code tag()}) plutôt que devinés, pour préserver l'affichage
     * (couleur/icône de source) du message combiné.
     *
     * Types de paramètres résolus depuis le TYPE DE RETOUR des accesseurs
     * existants ({@code getText.getReturnType()} etc.) plutôt que depuis
     * {@code instance.getClass()} — cette dernière donnerait la classe
     * D'IMPLÉMENTATION concrète (qui peut différer du type DÉCLARÉ attendu
     * par la signature de {@code addMessage}, notamment pour {@code
     * Component}/{@code tag()} qui peut être {@code null}) : piège de
     * réflexion déjà rencontré ailleurs ce chantier (RegistryEntry/Holder).
     *
     * @return {@code true} si la fusion a réussi (message combiné inséré),
     * {@code false} si le mécanisme n'est pas disponible sur ce bracket —
     * l'appelant ne doit alors PAS mettre à jour son état de dédoublonnage.
     */
    private static boolean mergeResolveDiagLogged;

    private boolean mergeRepeatedMessage(Object chatHud, List<Object> messages, Object headLine, Method getText, String combinedText) {
        try {
            Method sourceMethod = McReflect.noArgMethod(headLine.getClass(), "net/minecraft/client/gui/hud/ChatHudLine", "source");
            Method tagMethod = McReflect.noArgMethod(headLine.getClass(), "net/minecraft/client/gui/hud/ChatHudLine", "tag");
            Method signatureMethod = McReflect.noArgMethod(headLine.getClass(), "net/minecraft/client/gui/hud/ChatHudLine", "signature");

            Class<?> textClass = McReflect.yarnClass("net/minecraft/text/Text", "net.minecraft.network.chat.Component");
            Method literalMethod = textClass != null
                ? McReflect.method(textClass, "net/minecraft/text/Text", "literal", String.class)
                : null;

            Method addMessage4 = (sourceMethod != null && tagMethod != null && signatureMethod != null)
                ? McReflect.method(chatHud.getClass(), "net/minecraft/client/gui/hud/ChatHud", "addMessage",
                    getText.getReturnType(), signatureMethod.getReturnType(), sourceMethod.getReturnType(), tagMethod.getReturnType())
                : null;

            // Diag une seule fois — voir playPingSound() pour le pourquoi (échec
            // silencieux sinon, aucune trace en cas de "return false" plus bas).
            if (!mergeResolveDiagLogged) {
                mergeResolveDiagLogged = true;
                LauncherLog.info("[ChatEnhancementsModule] mergeRepeatedMessage diag: source=" + sourceMethod
                    + " tag=" + tagMethod + " signature=" + signatureMethod + " textClass=" + textClass
                    + " literal=" + literalMethod + " addMessage4=" + addMessage4);
            }

            if (sourceMethod == null || tagMethod == null || signatureMethod == null) return false;
            if (textClass == null || literalMethod == null) return false;
            if (addMessage4 == null) return false;

            // Retire le doublon qu'on vient de voir + le précédent (déjà
            // combiné ou seul) — cas courant : message court tenant sur une
            // seule ligne. Les messages plus longs (repliés sur plusieurs
            // lignes visibles) ne sont pas nettoyés parfaitement, limitation
            // acceptée pour ce cas d'usage. "trimmedMessages" (les lignes
            // affichées, dérivées de "messages") n'a PAS besoin d'être touché
            // séparément : addMessage() le régénère lui-même en interne.
            Object sourceValue = sourceMethod.invoke(headLine);
            Object tagValue = tagMethod.invoke(headLine);
            if (messages.size() >= 2) { messages.remove(0); messages.remove(0); }

            Object combined = literalMethod.invoke(null, combinedText);
            addMessage4.invoke(chatHud, combined, null, sourceValue, tagValue);
            return true;
        } catch (Throwable t) {
            if (!mergeErrorLogged) {
                mergeErrorLogged = true;
                LauncherLog.err("[ChatEnhancementsModule] mergeRepeatedMessage: " + t);
            }
            return false;
        }
    }
}
