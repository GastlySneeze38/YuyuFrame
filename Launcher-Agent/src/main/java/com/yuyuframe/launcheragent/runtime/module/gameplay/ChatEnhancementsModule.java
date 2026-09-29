package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import java.util.regex.Pattern;
import com.yuyuframe.launcheragent.runtime.module.visual.FovModule;
import com.yuyuframe.launcheragent.runtime.module.visual.FullbrightModule;
import com.yuyuframe.launcheragent.runtime.game.ChatData;
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

    public boolean pingOnMention = true;

    public boolean stackRepeats = true;

    @Override
    protected void settings(SettingList s) {
        s.toggle("pingOnMention", "Ping quand mon pseudo est mentionné", "Réglages",
            () -> pingOnMention, v -> pingOnMention = v);
        s.toggle("stackRepeats", "Regrouper les messages répétés", "Réglages",
            () -> stackRepeats, v -> stackRepeats = v);
    }

    private static final Pattern COUNTER_SUFFIX = Pattern.compile("\\s*\\(x\\d+\\)$");
    private Object la$lastProcessedMessage;
    private String la$lastDistinctBase;
    /**
     * Poignée OPAQUE du contenu de la PREMIÈRE occurrence — conservée pour
     * reconstruire la ligne fusionnée SANS perdre sa mise en forme (voir
     * {@code AccessPoint.CHAT_MERGE_REPEATED}). Jamais transtypée ici : c'est
     * un objet du jeu, dont ce module n'a pas à connaître le type.
     */
    private Object la$lastDistinctContent;
    private int la$repeatCount = 1;

    public ChatEnhancementsModule() {
        super("chat-enhancements", "Chat amélioré", "Ping quand ton pseudo est mentionné + regroupe les messages répétés", false,
            HookPoint.CHAT_RECEIVE, HookPoint.CHAT_SEND);
        iconUrl = icons8("chat");
        // 26.1.2 — voir apimixin/v26_1_2/chat/ChatReceiveMixin261 (réconciliation
        // de l'ancien mixin.client.v26_1.ChatListenerMixin261, même déclencheur).
        VanillaHookRegistry.register(HookPoint.CHAT_RECEIVE, ctx -> { onChatMessageObserved(); return false; });
        // Voir consumeOwnEcho() : on note ce que le joueur ENVOIE pour
        // reconnaître l'écho du serveur, quel que soit le format d'affichage.
        // Retour false SYSTÉMATIQUE — purement notificatif, ce hook est
        // annulable (ClientCommandRegistry s'en sert pour intercepter des
        // commandes client) et un true ici empêcherait le message de partir.
        // Enregistré même module désactivé : sans ça, activer le module au
        // milieu d'une conversation laisserait l'historique d'envois vide.
        VanillaHookRegistry.register(HookPoint.CHAT_SEND, ctx -> {
            if (ctx instanceof String) onChatSent((String) ctx);
            return false;
        });
    }

    // ── Reconnaissance de nos PROPRES messages ────────────────────────────
    //
    // BUG TROUVÉ (retour utilisateur 2026-08-31) : « vu que le nom du joueur
    // qui a parlé s'est déplacé, ça nous ping quand on parle ». L'ancienne
    // approche cherchait le pseudo APRÈS avoir retiré un en-tête au format
    // vanilla "<Nom> " (SENDER_TAG_PREFIX, supprimé). Ça ne tient que sur un
    // serveur vanilla : dès qu'un serveur préfixe un grade, colore, ou change
    // le séparateur ("[MVP+] Nom » ..."), l'en-tête n'est plus reconnu, notre
    // propre pseudo reste dans le texte analysé, et chacun de nos messages
    // nous ping. Toute variante de cette approche est condamnée à courir
    // après les formats de chaque serveur.
    //
    // APPROCHE STRUCTURELLE retenue, indépendante du format : le client SAIT
    // ce qu'il a envoyé. On mémorise chaque message sortant (HookPoint
    // CHAT_SEND → ClientPacketListener.sendChat) ; quand une ligne arrive et
    // qu'elle CONTIENT un de ces envois récents, c'est notre écho — peu
    // importe comment le serveur l'a habillé. L'entrée est alors CONSOMMÉE
    // (un envoi ne peut masquer qu'une seule ligne reçue), ce qui borne les
    // dégâts si un autre joueur écrit par hasard exactement la même chose.
    //
    // Même principe que ChatNotify (github.com/TerminalMC/ChatNotify), qui a
    // déjà rencontré ce problème sur serveurs modifiés.
    //
    // Limite connue : un serveur qui RÉÉCRIT le message (censure, troncature)
    // casse la correspondance. Le repli exact serait de comparer l'UUID de
    // l'expéditeur sur handlePlayerChatMessage — mais ce chemin ne couvre que
    // le chat signé vanilla ; la plupart des serveurs modifiés (Hypixel…)
    // passent TOUT par handleSystemMessage, sans expéditeur, là où l'écho
    // fonctionne.
    private static final long SENT_ECHO_WINDOW_MS = 15_000L;
    private static final int SENT_HISTORY_MAX = 8;

    private static final class Sent {
        final String text;
        final long at;
        Sent(String text, long at) { this.text = text; this.at = at; }
    }

    private final java.util.ArrayDeque<Sent> la$sent = new java.util.ArrayDeque<Sent>();

    private void onChatSent(String content) {
        if (content == null || content.isEmpty()) return;
        synchronized (la$sent) {
            la$sent.addLast(new Sent(content, System.currentTimeMillis()));
            while (la$sent.size() > SENT_HISTORY_MAX) la$sent.removeFirst();
        }
    }

    /** {@code true} si {@code plain} est l'écho d'un de nos envois récents — l'entrée correspondante est retirée au passage. */
    private boolean consumeOwnEcho(String plain) {
        long now = System.currentTimeMillis();
        synchronized (la$sent) {
            java.util.Iterator<Sent> it = la$sent.iterator();
            while (it.hasNext()) {
                Sent sent = it.next();
                if (now - sent.at > SENT_ECHO_WINDOW_MS) { it.remove(); continue; }
                if (plain.contains(sent.text)) { it.remove(); return true; }
            }
        }
        return false;
    }

    // ── Détection de la mention ───────────────────────────────────────────
    //
    // Recherche par LIMITES DE MOT plutôt que par contains() brut : sans ça,
    // un joueur nommé "GhastlySneeze38x" — ou n'importe quel mot contenant le
    // pseudo — déclenche le ping. Le motif est compilé une fois et gardé tant
    // que le pseudo ne change pas (il ne change jamais en session, mais le
    // cache évite une compilation de regex par message reçu).
    private String la$mentionPatternFor;
    private Pattern la$mentionPattern;

    private boolean mentions(String body, String username) {
        if (!username.equals(la$mentionPatternFor) || la$mentionPattern == null) {
            la$mentionPattern = Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(username) + "(?![A-Za-z0-9_])",
                Pattern.CASE_INSENSITIVE);
            la$mentionPatternFor = username;
        }
        return la$mentionPattern.matcher(body).find();
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
     * Minecraft.gui}/{@code Gui.getChat()} (publics, via {@code ClientData}
     * pour rester sur une seule surface d'accès à {@code Minecraft}) puis
     * {@code AccessPoint.CHAT_ALL_MESSAGES}/{@code CHAT_ADD_MESSAGE}
     * (privés). Trois étapes : dédup par IDENTITÉ d'objet, ping de mention
     * (voir {@link #consumeOwnEcho} et {@link #mentions}), fusion des
     * répétitions (voir {@link #mergeRepeatedMessageDirect}).
     *
     * @return {@code true} si traité ICI — y compris quand il n'y avait rien
     * de neuf à traiter ; {@code false} si le chat n'est pas encore
     * disponible.
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
        // {ligne, contenu, texte} — les deux premiers sont des poignées
        // OPAQUES : ce module les compare par identité et les repasse, il ne
        // les transtype jamais. C'est ce qui lui permet de ne nommer ni
        // ChatComponent, ni GuiMessage, ni Component.
        Object[] head = ChatData.headMessage();
        if (head == null) return false;
        Object headLine = head[0];
        Object content = head[1];
        String plain = (String) head[2];

        if (headLine == la$lastProcessedMessage) return true;
        la$lastProcessedMessage = headLine;

        // Consommé AVANT la branche du ping, et hors du "if (pingOnMention)" :
        // l'historique d'envois doit s'écouler au même rythme que le chat,
        // sinon couper le ping laisserait des entrées périmées derrière lui.
        boolean ownEcho = consumeOwnEcho(plain);

        if (pingOnMention && !ownEcho) {
            String username = ClientData.username();
            if (!username.isEmpty() && mentions(plain, username)) playPingSound();
        }

        if (stackRepeats) {
            String base = COUNTER_SUFFIX.matcher(plain).replaceAll("");
            if (base.equals(la$lastDistinctBase) && la$lastDistinctContent != null) {
                la$repeatCount++;
                Object newHead = ChatData.mergeRepeated(la$lastDistinctContent, la$repeatCount);
                if (newHead != null) la$lastProcessedMessage = newHead;
            } else {
                la$repeatCount = 1;
                la$lastDistinctBase = base;
                // Le composant de la PREMIÈRE occurrence, celui qui porte la
                // mise en forme du serveur — les répétitions suivantes se
                // réaffichent à partir de lui, jamais à partir de la ligne
                // déjà fusionnée (qui traîne son propre " (xN)").
                la$lastDistinctContent = content;
            }
        }
        return true;
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
        // Toute la chaîne (gestionnaire de sons, instance, lecture) est faite
        // par la liaison de la tranche active depuis le 2026-09-12 — ce module
        // ne nomme plus ni SoundEvents ni SimpleSoundInstance, et le ping
        // fonctionne donc aussi en 1.21.11.
        //
        // Volume 0,25 explicite : c'est la valeur que mettait la surcharge à
        // deux arguments utilisée jusqu'ici (forUI(sound, pitch)), et le point
        // d'accès, lui, demande les trois. Ne pas la rétablir aurait monté le
        // ping à plein volume sans que personne ne l'ait demandé.
        try {
            ClientData.playUiSound("minecraft:entity.experience_orb.pickup", 1.0f, 0.25f);
        } catch (Throwable t) {
            if (!pingErrorLogged) {
                pingErrorLogged = true;
                LauncherLog.err("[ChatEnhancementsModule] playPingSound: " + t);
            }
        }
    }

    private static boolean pingErrorLogged;
    /** Toujours utilisé par mergeRepeatedMessageDirect — le pendant réflexif a disparu, pas celui-ci. */
}
