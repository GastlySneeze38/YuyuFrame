package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern;
import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.NetworkData;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingList;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingModal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Macros de commandes liées à une touche, et connexion automatique aux
 * serveurs qui demandent un login.
 *
 * <p>Deux fonctions dans un seul module parce qu'elles partagent tout leur
 * outillage : l'envoi de commande, la notion de serveur courant, et l'écran
 * d'édition de listes.
 *
 * <h2>Détection du login — pourquoi elle est fiable</h2>
 *
 * La première idée était de guetter un message du genre « connectez-vous avec
 * /login ». Mauvaise idée, et l'utilisateur l'a écarté d'emblée : la
 * formulation dépend du plugin, de la langue et de la configuration du
 * serveur. Il fallait quelque chose que TOUT système de login possède par
 * construction.
 *
 * <p>C'est l'ARBRE DE COMMANDES. Pour que {@code /login <mdp>} fonctionne, le
 * serveur doit déclarer la commande au client
 * ({@code ClientboundCommandsPacket}) — c'est ce qui alimente la complétion
 * au Tab. Pas de déclaration, pas de commande. On lit donc
 * {@code ClientPacketListener.getCommands()} et on regarde si la racine a un
 * enfant nommé « login » : indépendant de la langue, de la mise en forme, et
 * du plugin.
 *
 * <p>Limite assumée : la présence de {@code /login} prouve que le serveur A un
 * système d'authentification, pas que le joueur est actuellement déconnecté.
 * Si la session est encore valide, le serveur répondra « déjà connecté » —
 * sans conséquence. C'est pour ça qu'on n'essaie qu'UNE fois par connexion.
 *
 * <h2>Le mot de passe ne peut pas partir en chat public</h2>
 *
 * Il est envoyé par {@link ClientPacketListener#sendCommand}, jamais par
 * {@code sendChat}. La distinction n'est pas cosmétique : une commande
 * envoyée comme message de chat part en clair dans le canal public. C'est le
 * seul vrai risque de cette fonctionnalité, et il est écarté par
 * construction plutôt que par précaution.
 *
 * <p>Le mot de passe est stocké EN CLAIR dans le fichier de configuration —
 * il n'y a pas de coffre-fort dans l'agent, et c'est ce que font tous les
 * mods équivalents. À dire à l'utilisateur, pas à cacher.
 */
public final class MacroModule extends LauncherModule {

    /** Un nom, une touche, une commande. La touche peut être vide : la macro n'existe alors que dans la palette. */
    public static final class Macro {
        public String key;
        /** Libellé affiché dans la palette — vide, on retombe sur la commande. */
        public String name;
        public String command;

        public Macro(String key, String name, String command) {
            this.key = key == null ? "" : key;
            this.name = name == null ? "" : name;
            this.command = command == null ? "" : command;
        }

        /** Ce qu'affiche la palette : le nom s'il existe, sinon la commande — jamais rien. */
        public String label() {
            if (name != null && !name.trim().isEmpty()) return name.trim();
            if (command != null && !command.trim().isEmpty()) return command.trim();
            return "(vide)";
        }
    }

    /** Édité ligne par ligne dans l'écran de configuration ; persisté en bloc par un réglage opaque, voir {@link #settings}. */
    public final List<Macro> macros = new ArrayList<Macro>();

    /**
     * Adresse de serveur → mot de passe. {@link LinkedHashMap} et non
     * {@code HashMap} : l'ordre d'affichage dans l'écran d'édition doit être
     * stable d'une ouverture à l'autre.
     */
    public final Map<String, String> logins = new LinkedHashMap<String, String>();

    /**
     * Touche qui ouvre la palette de macros — {@code NONE} par défaut, à
     * assigner. Pas de défaut arbitraire : toute touche libre en vanilla est
     * revendiquée par un mod ou un autre, et un conflit silencieux serait plus
     * pénible à comprendre qu'un réglage à faire une fois.
     */
    public String menuKey = "NONE";

    public boolean autoLogin = true;

    @Override
    protected void settings(SettingList s) {
        s.keybind("menuKey", "Touche de la palette",
            "Ouvre en jeu une liste cliquable de toutes les macros — pratique quand on en a trop pour leur donner chacune une touche. Une macro sans touche assignée n'apparaît QUE là.",
            "Macros", null, () -> menuKey, v -> menuKey = v);

        // Catégorie « Auto-login » et non « Connexion automatique » : ce
        // libellé sert d'onglet dans la sous-sidebar, où il débordait.
        s.toggle("autoLogin", "Connexion automatique",
            "Envoie /login sur les serveurs qui déclarent cette commande. Le mot de passe est enregistré par adresse de serveur, plus bas.",
            "Auto-login", null, () -> autoLogin, v -> autoLogin = v);

        // Les deux listes : persistées ICI, en un seul réglage chacune.
        // Les lignes d'édition ci-dessous sont ENGENDRÉES à partir d'elles et
        // marquées non-persistantes — sans quoi elles seraient sauvegardées en
        // double, et relues alors que les listes sont encore vides.
        s.opaque("macros", this::serializeMacros, this::deserializeMacros);
        s.opaque("logins", this::serializeLogins, this::deserializeLogins);

        // ── Une ligne par macro, UNE SEULE ligne ──────────────────────────
        //
        // Setting.Inline porte tous les contrôles d'une entrée sur la même
        // ligne : touche, commande, suppression. La version précédente en
        // étalait trois, chacune avec son libellé — « ce qui est horrible
        // c'est la configuration de la macro dans l'écran de config […] ça
        // doit prendre 1 seule ligne pour toutes ses composantes ».
        for (int i = 0; i < macros.size(); i++) {
            final Macro macro = macros.get(i);
            final int index = i;
            s.inlineKeyed("macro" + i, "Macros",
                () -> macro.key, v -> macro.key = v,
                "nom", () -> macro.name, v -> macro.name = v,
                "/spawn", () -> macro.command, v -> macro.command = v,
                () -> { macros.remove(index); invalidateSettings(); });
        }
        s.action("macro.add", "Ajouter une macro",
            "Touche (facultative), nom affiché, puis commande.", "Macros",
            () -> SettingModal.request(new SettingModal("Nouvelle macro", answers -> {
                macros.add(new Macro(answers.get(0), answers.get(1), answers.get(2)));
                invalidateSettings();
            },
            // La touche EN PREMIER et facultative (Échap = aucune) : une macro
            // sans touche ne vit que dans la palette, ce qui est le cas
            // d'usage principal dès qu'on en a beaucoup.
            SettingModal.Step.key("Sur quelle touche ? (Échap pour aucune)"),
            SettingModal.Step.text("Nom affiché dans la palette", "Retour au spawn"),
            SettingModal.Step.text("Quelle commande envoyer ?", "/spawn"))));

        for (Map.Entry<String, String> entry : new ArrayList<Map.Entry<String, String>>(logins.entrySet())) {
            final String address = entry.getKey();
            // masked = true : le champ affiche des points et gagne un œil pour
            // révéler à la demande. Un mot de passe lisible en permanence dans
            // un écran qu'on ouvre en jeu, potentiellement en partageant son
            // écran, n'avait pas de raison d'être le défaut.
            s.inlineLabeled("login." + address, "Auto-login", address, "mot de passe",
                () -> logins.get(address), v -> logins.put(address, v),
                () -> { logins.remove(address); invalidateSettings(); },
                true);
        }
        s.action("login.add", "Ajouter un serveur",
            "L'adresse est pré-remplie avec le serveur en cours. Le mot de passe part par /login, jamais en chat public, et est stocké en clair dans la configuration.",
            "Auto-login",
            () -> {
                // Adresse PRÉ-REMPLIE avec le serveur courant plutôt qu'une
                // saisie libre : une faute de frappe donnerait une entrée qui
                // ne se déclenche jamais, sans rien pour l'expliquer. Elle
                // reste modifiable, pour préparer un serveur hors ligne.
                final String current = currentServerAddress();
                SettingModal.request(new SettingModal("Nouveau serveur", answers -> {
                    String address = answers.get(0).trim();
                    if (address.isEmpty()) return;
                    logins.put(address, answers.get(1));
                    invalidateSettings();
                },
                new SettingModal.Step("Adresse du serveur", current == null ? "jouer.exemple.fr" : current, false),
                SettingModal.Step.text("Mot de passe", "mot de passe")));
            });
    }


    public MacroModule() {
        super("macros", "Macros",
            "Lance des commandes avec une touche, et connecte automatiquement aux serveurs qui demandent un login",
            "Macros de commandes et connexion automatique", false);
        iconUrl = icons8("keyboard");
    }

    // ⚠️ Les deux désérialiseurs se terminent par invalidateSettings().
    //
    // BUG TROUVÉ (retour utilisateur 2026-08-31 : « des fois on ne voit pas
    // les macros enregistrées dès l'ouverture de la config du module, il faut
    // créer une macro pour qu'elles apparaissent toutes »). Enchaînement
    // exact : LauncherModule.settings() MÉMORISE la liste au premier appel, et
    // ce premier appel vient de HudConfigStore, qui la parcourt pour relire la
    // configuration. À cet instant `macros` est encore VIDE — aucune ligne
    // n'est donc engendrée. La lecture remplit ensuite la liste, mais trop
    // tard : la liste de réglages mémorisée, elle, ne contient toujours pas
    // les lignes. Créer une macro appelait invalidateSettings() et faisait
    // apparaître tout le monde d'un coup, ce qui donnait au bug son air
    // aléatoire.
    //
    // Invalider ICI, au moment où la donnée arrive réellement, referme le
    // trou : le prochain settings() reconstruit avec les lignes. Sans risque
    // de boucle — invalidateSettings() ne fait que vider le cache, il ne
    // redéclenche aucune lecture.
    // ── Sérialisation ─────────────────────────────────────────────────────
    //
    // Le magasin de configuration est un .properties : une chaîne par
    // réglage, sur une ligne. Les séparateurs sont donc des caractères de
    // CONTRÔLE, que ni une touche ni une commande ne peuvent contenir — un
    // ';' ou un '|' aurait fini par apparaître dans une commande et couper
    // l'entrée en deux. Properties les réécrit sous forme d'échappement
    // Unicode à l'enregistrement, la ligne reste donc valide.
    //
    // ⚠️ Ne PAS écrire cette séquence d'échappement en toutes lettres dans un
    // commentaire : javac décode les échappements Unicode AVANT d'analyser le
    // source, commentaires compris — « illegal unicode escape », et le jar
    // tombe à 49 Ko.
    private static final char ENTRY_SEP = '\u0001';
    private static final char FIELD_SEP = '\u0002';

    private String serializeMacros() {
        StringBuilder sb = new StringBuilder();
        for (Macro m : macros) {
            if (m.key.isEmpty() && m.name.isEmpty() && m.command.isEmpty()) continue;
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(m.key).append(FIELD_SEP).append(m.name).append(FIELD_SEP).append(m.command);
        }
        return sb.toString();
    }

    private void deserializeMacros(String raw) {
        macros.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split(String.valueOf(ENTRY_SEP), -1)) {
            if (entry.isEmpty()) continue;
            // Deux champs = ancien format (touche + commande), écrit avant
            // que les macros ne puissent porter un nom. Relu tel quel plutôt
            // qu'ignoré : personne ne doit perdre ses macros en mettant à
            // jour.
            String[] parts = entry.split(String.valueOf(FIELD_SEP), -1);
            if (parts.length == 2) macros.add(new Macro(parts[0], "", parts[1]));
            else if (parts.length >= 3) macros.add(new Macro(parts[0], parts[1], parts[2]));
        }
        invalidateSettings();
    }

    private String serializeLogins() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : logins.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty()) continue;
            if (sb.length() > 0) sb.append(ENTRY_SEP);
            sb.append(e.getKey()).append(FIELD_SEP).append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    private void deserializeLogins(String raw) {
        logins.clear();
        if (raw == null || raw.isEmpty()) return;
        for (String entry : raw.split(String.valueOf(ENTRY_SEP), -1)) {
            if (entry.isEmpty()) continue;
            int sep = entry.indexOf(FIELD_SEP);
            if (sep < 0) continue;
            logins.put(entry.substring(0, sep), entry.substring(sep + 1));
        }
        invalidateSettings();
    }

    // ── Macros ────────────────────────────────────────────────────────────

    /**
     * Touches maintenues au tick précédent — une macro se déclenche au FRONT
     * MONTANT. Sans ça, garder la touche enfoncée enverrait la commande 20
     * fois par seconde, ce qu'un serveur lit comme du spam.
     */
    private final java.util.Set<String> heldLastTick = new java.util.HashSet<String>();

    @Override
    public void onTick() {
        try {
            tickMacros();
            tickAutoLogin();
        } catch (Throwable t) {
            if (!tickErrorLogged) {
                tickErrorLogged = true;
                LauncherLog.err("[MacroModule] onTick: " + t);
            }
        }
    }

    private static boolean tickErrorLogged;

    private void tickMacros() {
        // UiInputPollerModern et non UiInputPoller : seule la variante
        // moderne expose la lecture par NOM de touche (GLFW). Sur 1.8.9 elle
        // est nulle — ce module ne cible pas ce bracket.
        UiInputPollerModern poller = UiInputPollerModern.ACTIVE;
        if (poller == null) return;

        // AUCUNE macro tant qu'un écran est ouvert : le joueur y tape du
        // texte (tchat, renommage d'enclume, champ de recherche). Sans cette
        // garde, écrire un message contenant la lettre d'une macro enverrait
        // la commande au milieu de la frappe.
        if (ClientData.screenObject() != null) {
            heldLastTick.clear();
            return;
        }

        // Palette : même détection de front montant que les macros, donc la
        // touche s'ouvre une fois et pas vingt fois par seconde.
        if (menuKey != null && !menuKey.isEmpty() && !"NONE".equals(menuKey)) {
            boolean down = poller.isKeyDownByName(menuKey);
            if (down && !heldLastTick.contains(menuKey)) openPicker();
            if (down) heldLastTick.add(menuKey);
            else heldLastTick.remove(menuKey);
        }

        for (Macro macro : macros) {
            if (macro.key == null || macro.key.isEmpty()) continue;
            if (macro.command == null || macro.command.trim().isEmpty()) continue;

            boolean down = poller.isKeyDownByName(macro.key);
            boolean wasDown = heldLastTick.contains(macro.key);
            if (down && !wasDown) run(macro.command);

            if (down) heldLastTick.add(macro.key);
            else heldLastTick.remove(macro.key);
        }
    }

    /**
     * Ouvre la palette de macros.
     *
     * <p>Par réflexion, pour une raison d'architecture et non de commodité :
     * un module ne référence JAMAIS une classe d'écran (voir la javadoc de
     * {@link LauncherModule}), sans quoi la couche des modules dépendrait de
     * la couche d'interface. L'écran, lui, a parfaitement le droit de
     * connaître ce module — la dépendance ne va que dans ce sens.
     */
    private void openPicker() {
        try {
            Class<?> screenClass = Class.forName(
                "com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMacroPickerScreen",
                true, getClass().getClassLoader());
            Object screen = screenClass.getConstructor(MacroModule.class).newInstance(this);
            ClientData.setScreen(screen);
        } catch (Throwable t) {
            if (!pickerErrorLogged) {
                pickerErrorLogged = true;
                LauncherLog.err("[MacroModule] ouverture de la palette : " + t);
            }
        }
    }

    private static boolean pickerErrorLogged;

    /** Exécute une macro — appelé par la touche assignée ET par la palette (voir {@code UiMacroPickerScreen}). */
    public void runMacro(Macro macro) {
        if (macro == null || macro.command == null) return;
        run(macro.command);
    }

    /**
     * Exécute le texte d'une macro : commande si elle commence par « / »,
     * message de chat sinon — la convention que le joueur a déjà en tête
     * quand il tape dans le tchat.
     */
    private void run(String text) {
        String trimmed = text.trim();
        try {
            if (trimmed.startsWith("/")) NetworkData.sendCommand(trimmed.substring(1));
            else NetworkData.sendChat(trimmed);
        } catch (Throwable t) {
            if (!runErrorLogged) {
                runErrorLogged = true;
                LauncherLog.err("[MacroModule] envoi de « " + trimmed + " » : " + t);
            }
        }
    }

    private static boolean runErrorLogged;

    // ── Connexion automatique ─────────────────────────────────────────────

    /** Noms sous lesquels la commande de connexion est déclarée, par ordre de préférence. */
    private static final String[] LOGIN_COMMANDS = { "login", "l", "connexion" };

    /**
     * La connexion pour laquelle on a déjà tenté — comparée par IDENTITÉ.
     * Chaque connexion à un serveur crée un nouvel objet, ce qui donne un
     * « une seule fois par connexion » exact et gratuit, sans avoir à
     * détecter les déconnexions.
     */
    private Object lastAttemptedConnection;

    private void tickAutoLogin() {
        if (!autoLogin) return;
        // Poignée OPAQUE, comparée par identité seulement — voir
        // AccessPoint.NETWORK_CONNECTION.
        Object connection = NetworkData.connection();
        if (connection == null) {
            lastAttemptedConnection = null;
            return;
        }
        if (connection == lastAttemptedConnection) return;

        String address = currentServerAddress();
        if (address == null) return;                 // solo, ou serveur pas encore connu
        String password = passwordFor(address);
        if (password == null || password.isEmpty()) {
            // Journalisé UNE fois par adresse : c'est LE cas où l'utilisateur
            // se demande pourquoi rien ne se passe, et l'adresse vue par le
            // jeu peut différer de celle qu'il croit avoir enregistrée.
            reportOnce("aucun mot de passe enregistré pour " + address);
            return;
        }

        String command = declaredLoginCommand();
        if (command == null) {
            reportOnce(address + " ne déclare aucune commande de connexion — rien à envoyer");
            return;
        }

        // Marqué AVANT l'envoi : si sendCommand lève, on ne veut surtout pas
        // réessayer à chaque tick avec un mot de passe.
        lastAttemptedConnection = connection;
        try {
            NetworkData.sendCommand(command + " " + password);
            LauncherLog.info("[MacroModule] connexion automatique envoyée à " + address
                + " (commande /" + command + ")");
        } catch (Throwable t) {
            LauncherLog.err("[MacroModule] connexion automatique : " + t);
        }
    }

    /**
     * Nom de la commande de connexion DÉCLARÉE par le serveur, ou
     * {@code null} s'il n'en déclare aucune — voir la javadoc de classe pour
     * pourquoi ce signal vaut mieux que la lecture du chat.
     */
    private String declaredLoginCommand() {
        try {
            // La BOUCLE reste ici (c'est notre liste de candidates, donc de la
            // politique) ; seule l'interrogation de l'arbre part dans la
            // liaison, qui doit partir de la connexion pour y accéder.
            for (String name : LOGIN_COMMANDS) {
                if (NetworkData.hasCommand(name)) return name;
            }
        } catch (Throwable t) {
            if (!commandsErrorLogged) {
                commandsErrorLogged = true;
                LauncherLog.err("[MacroModule] lecture de l'arbre de commandes : " + t);
            }
        }
        return null;
    }

    private static boolean commandsErrorLogged;

    /** Journalise une raison d'abandon UNE fois par raison distincte — sans ça, un auto-login muet est indiagnosticable. */
    private static String lastReport;

    private static void reportOnce(String reason) {
        if (reason.equals(lastReport)) return;
        lastReport = reason;
        LauncherLog.info("[MacroModule] " + reason);
    }

    /**
     * Adresse du serveur courant, telle qu'elle apparaît dans la liste des
     * serveurs — c'est la clé sous laquelle le mot de passe est rangé.
     * {@code null} en solo.
     */
    public static String currentServerAddress() {
        return serverAddress();
    }

    /**
     * {@code Minecraft} n'a AUCUN champ portant le serveur courant (vérifié
     * en listant ses champs) — {@code getCurrentServer()} le dérive de la
     * connexion. On part donc directement de celle-ci, qu'on a déjà en main.
     */
    private static String serverAddress() {
        try {
            Object[] sources = NetworkData.serverAddressSources();
            if (sources == null) return null;
            String ip = sources[0] instanceof String ? (String) sources[0] : null;
            if (ip != null && !ip.isEmpty()) return normalizeAddress(ip);

            // BUG TROUVÉ (retour utilisateur 2026-08-31 : « l'auto-login ne
            // marchait pas quand on se connecte directement au serveur en
            // lançant le jeu directement sur l'IP »). Sur ce chemin — le
            // « quick play » du lanceur — le jeu ne construit pas forcément
            // d'entrée de liste de serveurs : getServerData() rend null, et
            // toute la connexion automatique s'arrêtait là, sans un mot.
            //
            // La CONNEXION, elle, existe toujours : c'est par définition ce
            // qui nous relie au serveur. Son adresse distante est donc le
            // repli qui ne peut pas manquer.
            if (sources[1] instanceof java.net.SocketAddress) {
                String address = hostOf((java.net.SocketAddress) sources[1]);
                if (address != null && !address.isEmpty()) return normalizeAddress(address);
            }
            return null;
        } catch (Throwable t) {
            if (!addressErrorLogged) {
                addressErrorLogged = true;
                LauncherLog.err("[MacroModule] adresse du serveur : " + t);
            }
            return null;
        }
    }

    private static boolean addressErrorLogged;

    /**
     * Nom d'hôte d'une adresse de socket, sans la partie résolue.
     *
     * <p>{@code InetSocketAddress.toString()} rend
     * {@code jouer.exemple.fr/1.2.3.4:25565} : on garde ce qui précède le
     * {@code /} quand il y en a, c'est-à-dire ce que le joueur a réellement
     * saisi. Sans hôte (IP nue), on retombe sur l'adresse littérale.
     */
    private static String hostOf(java.net.SocketAddress remote) {
        if (remote instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress inet = (java.net.InetSocketAddress) remote;
            String host = inet.getHostString();
            if (host != null && !host.isEmpty()) {
                return inet.getPort() == DEFAULT_PORT ? host : host + ":" + inet.getPort();
            }
        }
        String text = String.valueOf(remote);
        int slash = text.indexOf('/');
        return slash > 0 ? text.substring(0, slash) : text;
    }

    private static final int DEFAULT_PORT = 25565;

    /**
     * Forme canonique d'une adresse : minuscules, port par défaut retiré.
     *
     * <p>Sans ça, {@code Jouer.Exemple.fr} saisi dans la liste des serveurs et
     * {@code jouer.exemple.fr:25565} vu à la connexion seraient deux clés
     * différentes — le mot de passe enregistré ne serait jamais retrouvé, et
     * rien n'expliquerait pourquoi.
     */
    private static String normalizeAddress(String address) {
        String a = address.trim().toLowerCase(java.util.Locale.ROOT);
        if (a.endsWith(":" + DEFAULT_PORT)) a = a.substring(0, a.length() - (String.valueOf(DEFAULT_PORT).length() + 1));
        return a;
    }

    /** Mot de passe enregistré pour {@code address}, en comparant les formes canoniques des deux côtés. */
    private String passwordFor(String address) {
        String target = normalizeAddress(address);
        for (Map.Entry<String, String> e : logins.entrySet()) {
            if (e.getKey() != null && normalizeAddress(e.getKey()).equals(target)) return e.getValue();
        }
        return null;
    }
}
