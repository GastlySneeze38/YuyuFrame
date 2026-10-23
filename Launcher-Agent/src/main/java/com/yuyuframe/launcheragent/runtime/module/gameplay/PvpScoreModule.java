package com.yuyuframe.launcheragent.runtime.module.gameplay;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiLighting;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiView3D;
import com.yuyuframe.launcheragent.apigraphic.hud.HudAnchor;
import com.yuyuframe.launcheragent.apigraphic.hud.HudElement;
import com.yuyuframe.launcheragent.apigraphic.hud.HudRegistry;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.combat.CombatTracker;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.HudElementOwner;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpRank;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.PvpScoreUi;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.RankChest;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.RankTiles;

/**
 * Score PvP (docs/scoring/) : allumé, le module relève les duels à la masse,
 * le noyau natif les reconnaît, les note et écrit journal et résumé dans
 * {@code yuyuframe/matches/} du dossier de l'instance. À la fin d'un duel, un
 * panneau montre la note quelques secondes.
 *
 * <p>Le module ne contient aucune logique de jeu. Java relève
 * ({@link CombatTracker}) et affiche ; Rust reconnaît les duels et calcule
 * ({@code content-core/src/combat/}). Le résumé arrive ici en nombres, déjà
 * calculés ({@link NativeCombat#report}).
 *
 * <p>Le panneau de fin de duel est un élément du HUD comme les autres : il se
 * place et se redimensionne dans l'éditeur de HUD, où il montre un duel
 * d'exemple tant qu'aucun vrai duel n'a été noté. En jeu, il n'apparaît que
 * les quelques secondes qui suivent un duel.
 *
 * <p>La carte du module ouvre l'historique ({@code UiPvpScoreScreen}) : la
 * moyenne des notes, puis les duels, chacun menant à son détail. Son seul
 * réglage, « Partager mes duels », est un interrupteur de cette page.
 *
 * <p>Éteint par défaut : tant que le score n'est pas calibré, personne ne
 * doit se retrouver avec des journaux écrits sans l'avoir demandé.
 */
public final class PvpScoreModule extends LauncherModule implements HudElementOwner {

    /** Les versions dont les mixins de combat existent ({@code apimixin/<version>/combat}). */
    public static final java.util.Set<String> VERSIONS = java.util.Set.of("26.1.2", "26.3");

    /** Libellés des six familles, dans l'ordre de {@link NativeCombat#REPORT_FAMILIES}. */
    public static final String[] FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle vertical et horizontal", "Ressources", "Niveau adverse",
    };
    /** Les mêmes, en court, pour les colonnes étroites du panneau de fin de duel. */
    public static final String[] SHORT_FAMILIES = {
        "Conversion", "Qualité du smash", "Défense", "Contrôle", "Ressources", "Niveau adverse",
    };
    /**
     * Les familles de l'épée (2026-10-22), dans les mêmes cases du résumé. La
     * cinquième n'existe pas : le kit noté n'a que l'épée, il n'y a pas de
     * ressources à juger.
     */
    public static final String[] SWORD_FAMILIES = {
        "Combos", "Qualité du coup", "Défense", "Placement", "", "Niveau adverse",
    };
    public static final String[] SWORD_SHORT_FAMILIES = {
        "Combos", "Qualité du coup", "Défense", "Placement", "", "Niveau adverse",
    };

    /** Un duel à l'épée ({@link NativeCombat#REPORT_MODE} 2) ? */
    public static boolean isSword(double[] report) {
        return report[NativeCombat.REPORT_MODE] == 2;
    }

    /** Le libellé de la famille {@code i} dans le mode de ce duel. */
    public static String familyName(int i, double[] report) {
        return (isSword(report) ? SWORD_FAMILIES : FAMILIES)[i];
    }

    public static String shortFamilyName(int i, double[] report) {
        return (isSword(report) ? SWORD_SHORT_FAMILIES : SHORT_FAMILIES)[i];
    }

    /** Combien de mes familles ce duel a : cinq à la masse, quatre à l'épée. */
    public static int familyRows(double[] report) {
        return isSword(report) ? 4 : NativeCombat.FAMILY_COUNT - 1;
    }
    /** Libellés des résultats, par code ({@link NativeCombat#REPORT_RESULT}). */
    public static final String[] RESULTS = { "", "Victoire", "Défaite", "Match nul", "" };

    /** Les mots des bons coups, en haut de l'écran pendant le duel. */
    private final PvpCallouts callouts = new PvpCallouts();
    private final HudElement calloutElement = new HudElement("pvp-callouts", "Mots des bons coups", HudAnchor.TOP_CENTER, 0f, 42f,
        (HudElement.CustomRenderer) callouts);
    private final Summary summary = new Summary(callouts);
    private final HudElement element = new HudElement("pvp-score", "Score PvP", HudAnchor.TOP_CENTER, 0f, 36f,
        (HudElement.CustomRenderer) summary);
    /** L'annonce de début de duel et du changement compétitif / casual. */
    private final Banner banner = new Banner();
    private final HudElement bannerElement = new HudElement("pvp-duel-banner", "Annonce de duel", HudAnchor.TOP_CENTER, 0f, 8f,
        (HudElement.CustomRenderer) banner);
    /** Un duel était ouvert au tick précédent : son ouverture se voit au passage à vrai. */
    private boolean wasRecording;
    private boolean keyHeld;
    /** Le compteur d'équipements du noyau ({@link NativeCombat#kits}) au tick précédent. */
    private int lastKits = NativeCombat.kits();
    /** Dernière annonce de duel : au premier coup, on n'annonce pas une seconde fois ce qui vient de l'être. */
    private long announcedAtNanos;
    private static final long ANNOUNCE_VALID_NANOS = 20_000_000_000L;

    private final double[] incoming = new double[NativeCombat.REPORT_LEN];
    /** Le numéro du dernier résumé reçu — celui d'avant le lancement n'est pas montré. */
    private int lastReport = NativeCombat.report(new double[NativeCombat.REPORT_LEN]);
    private final double[] incomingRanked = new double[NativeCombat.RANKED_LEN];
    /** Le numéro du résumé dont on a reçu les points de classement. */
    private int lastRanked = NativeCombat.ranked(new double[NativeCombat.RANKED_LEN]);

    /** Le module, pour la page de l'historique qui porte son interrupteur de partage. */
    private static volatile PvpScoreModule instance;

    public static PvpScoreModule instance() {
        return instance;
    }

    public PvpScoreModule() {
        super("pvp-score", "Score PvP",
            "Note vos duels à la masse et affiche le résultat à la fin de chacun. En cours de développement : la note n'est pas encore calibrée.",
            "Note de vos duels à la masse", false,
            // DÉCLARÉS, sinon les mixins correspondants ne sont pas tissés du
            // tout (voir MixinHookPointRegistry).
            HookPoint.CLIENT_TICK, HookPoint.SERVER_CONNECT, HookPoint.CLIENT_LEVEL_LOAD,
            HookPoint.ENTITY_ATTACK, HookPoint.DAMAGE_EVENT, HookPoint.ENTITY_EVENT, HookPoint.ITEM_USE,
            HookPoint.SOUND_PLAYED, HookPoint.ITEM_COOLDOWN);
        iconUrl = icons8("sword");
        // Jamais à l'écran ensemble : l'éditeur de HUD les montre moment par moment.
        bannerElement.moment = "Début du duel";
        calloutElement.moment = "Pendant le duel";
        element.moment = "Fin du duel";
        CombatTracker.install();
        CombatTracker.setActive(isEnabled());
        instance = this;
        // La valeur par défaut part au noyau tout de suite : un réglage jamais
        // touché n'est pas relu du fichier, donc aucun setter ne la donnerait.
        // Une valeur sauvegardée arrive ensuite et la remplace.
        NativeCombat.share(shareDuels);
        NativeCombat.competitive(competitive);
        refreshRanks();
    }

    @Override
    public HudElement hudElement() {
        return element;
    }

    /** Les trois éléments, pour que chacun garde sa place d'une partie à l'autre. */
    @Override
    public java.util.List<HudElement> hudElements() {
        return java.util.List.of(element, bannerElement, calloutElement);
    }

    /**
     * « Partager mes duels » : chaque duel fermé part, anonymisé, vers le
     * serveur de YuyuFrame pour calibrer la note (voir
     * {@code content-core/src/combat/share.rs} et docs/scoring/partage.md).
     * Allumé par défaut (décision du 2026-10-18 : la calibration a besoin des
     * duels de tout le monde), et rien ne part sans compte YuyuFrame connecté.
     *
     * <p>Le module n'a pas d'écran de réglages — sa carte ouvre l'historique :
     * l'interrupteur est sur cette page ({@code UiPvpScoreScreen}). Le
     * réglage est déclaré ici pour être sauvegardé comme les autres.
     */
    private boolean shareDuels = true;

    /** « Montrer mon rang aux autres » : le serveur donne sinon le logo de base. */
    private boolean showRank = true;

    public boolean shareDuels() {
        return shareDuels;
    }

    public void setShareDuels(boolean value) {
        shareDuels = value;
        NativeCombat.share(value);
        com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save();
    }

    /**
     * Compétitif (les duels comptent pour le rang) ou casual (notés, hors du
     * classement) — docs/scoring/classement.md. Compétitif par défaut : c'est
     * le mode qu'on veut voir jouer. Le choix vaut pour les duels ouverts
     * ensuite, le noyau l'écrit dans l'en-tête de chacun.
     */
    private boolean competitive = true;
    /** La touche qui bascule de l'un à l'autre, hors des écrans. */
    private String competitiveKey = "K";
    private static final String NO_KEY = "NONE";
    /** Les mots des bons coups s'affichent (réglage). */
    private boolean showCallouts = true;

    public boolean competitive() {
        return competitive;
    }

    public void setCompetitive(boolean value) {
        if (value == competitive) return;
        competitive = value;
        NativeCombat.competitive(value);
        com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save();
        banner.switched(value, CombatTracker.recording(), bestRank());
        // Un clic, et une note qui monte vers le compétitif ou descend vers le casual.
        PvpSounds.play(PvpSounds.CLICK, 1f, 0.5f);
        PvpSounds.play(PvpSounds.PLING, value ? 1.5f : 0.8f, 0.5f);
    }

    /** Le nom de la touche de bascule, ou vide si aucune n'est choisie. */
    public String competitiveKey() {
        return competitiveKey == null || competitiveKey.trim().isEmpty() || NO_KEY.equals(competitiveKey) ? "" : competitiveKey;
    }

    @Override
    protected void settings(com.yuyuframe.launcheragent.runtime.ui.config.SettingList s) {
        s.toggle("competitive", "Jouer en compétitif",
            "Vos duels comptent pour votre rang. Éteint (casual), ils sont notés sans toucher au rang",
            "Classement", null, () -> competitive, v -> { competitive = v; NativeCombat.competitive(v); });
        s.keybind("competitiveKey", "Touche compétitif / casual",
            "Bascule entre compétitif et casual en jeu. Le duel en cours garde son type",
            "Classement", null, () -> competitiveKey, v -> competitiveKey = v);
        s.toggle("showRank", "Montrer mon rang aux autres",
            "Votre rang devient le logo à côté de votre pseudo, pour les autres joueurs YuyuFrame. Éteint, ils voient le logo de base",
            "Classement", null, () -> showRank, v -> { showRank = v; NativeCombat.showRank(v); });
        s.toggle("callouts", "Mots des bons coups",
            "Un mot surgit en haut de l'écran à chaque bon coup : combo, W-tap, smash, totem arraché…",
            "Pendant le duel", null, () -> showCallouts, v -> { showCallouts = v; callouts.visible = v; });
        s.toggle("sounds", "Sons",
            "Les sons des bons coups, des points gagnés et des nouveaux rangs",
            "Pendant le duel", null, () -> PvpSounds.enabled, v -> PvpSounds.enabled = v);
        s.slider("soundVolume", "Volume des sons (%)",
            "Le volume de tous les sons du Score PvP, par rapport à leur niveau normal",
            "Pendant le duel", 0f, 100f, 5f, () -> PvpSounds.enabled,
            () -> PvpSounds.volume * 100f, v -> PvpSounds.volume = v / 100f);
        // Les records personnels (plus long combo, plus haut smash) : édités par le jeu, pas par l'écran.
        s.opaque("records", callouts::records, callouts::loadRecords);
        s.toggle("shareDuels", "Partager mes duels",
            "Envoie vos duels, anonymisés, pour calibrer la note — sans pseudo, ni UUID, ni adresse de serveur",
            "Calibration", null, () -> shareDuels, v -> { shareDuels = v; NativeCombat.share(v); });
    }

    @Override
    protected void onEnabledChanged(boolean enabled) {
        CombatTracker.setActive(enabled);
        if (enabled) {
            HudRegistry.register(element);
            HudRegistry.register(bannerElement);
            HudRegistry.register(calloutElement);
            NativeCombat.showRank(showRank);
            refreshRanks();
        } else {
            HudRegistry.unregister(element);
            HudRegistry.unregister(bannerElement);
            HudRegistry.unregister(calloutElement);
        }
    }

    // ── Le rang de chaque mode, pour l'annonce ───────────────────────────────

    /** Les rangs connus (masse, épée), relus de l'historique hors du fil du jeu ; vide avant la première lecture. */
    private static volatile java.util.List<com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank> ranks = java.util.List.of();

    /**
     * Relit les rangs sur un fil à part : le noyau rejoue tout l'historique,
     * quelques millisecondes par duel — trop pour le fil du jeu.
     */
    private static void refreshRanks() {
        Thread thread = new Thread(() -> {
            try {
                ranks = com.yuyuframe.launcheragent.runtime.combat.DuelHistory.load().ranks;
            } catch (Throwable error) {
                com.yuyuframe.launcheragent.base.log.LauncherLog.err("[COMBAT] Rangs illisibles pour l'annonce de duel", error);
            }
        }, "yf-pvp-ranks");
        thread.setDaemon(true);
        thread.start();
    }

    /** Le rang d'un mode (1 masse, 2 épée), {@code null} s'il n'est pas connu. */
    private static com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rankOf(int mode) {
        for (com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rank : ranks) {
            if (rank.mode == mode) return rank;
        }
        return null;
    }

    /** Le rang le plus avancé, tous modes confondus : un rang dévoilé avant un placement. */
    private static com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank bestRank() {
        com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank best = null;
        double bestKey = -1;
        for (com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rank : ranks) {
            if (rank.duels == 0) continue;
            double key = (rank.placed ? 1e9 : 0) + PvpRank.cumulative(rank.division, rank.points) * 1e3 + rank.duels;
            if (key > bestKey) {
                bestKey = key;
                best = rank;
            }
        }
        return best;
    }

    /** « Or 2 », « Placement 4 / 10 », ou « Non classé » sans duel. */
    static String rankLabel(com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rank) {
        if (rank == null || rank.duels == 0) return Lang.tr("Non classé");
        if (!rank.placed) return Lang.tr("Placement") + " " + rank.duels + " / " + PvpRank.PLACEMENT_DUELS;
        return PvpRank.name(rank.division);
    }

    static UiColor rankColor(com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rank) {
        return rank != null && rank.placed ? PvpRank.color(rank.division) : UiTheme.ACCENT;
    }

    /**
     * À chaque image : un nouveau résumé est-il arrivé du noyau ? Puis ses
     * points de classement, qui le suivent de peu (le noyau rejoue
     * l'historique sur un fil à part).
     */
    @Override
    public void onTick() {
        long now = System.nanoTime();
        // Le serveur nous équipe pour un nouveau duel : le panneau du duel
        // d'avant s'efface, avant même le premier coup, et on annonce ce qui
        // est en jeu.
        int kits = NativeCombat.kits();
        if (kits != lastKits) {
            lastKits = kits;
            summary.hide();
            announce(now);
        }
        // Un duel vient de s'ouvrir (premier échange) : le panneau s'efface
        // s'il était encore là — serveur qui n'équipe pas, ou duel enchaîné —,
        // et l'annonce part si l'équipement ne l'a pas déjà fait partir.
        boolean recording = CombatTracker.recording();
        if (recording && !wasRecording) {
            summary.hide();
            callouts.duelStarted();
            if (now - announcedAtNanos > ANNOUNCE_VALID_NANOS) announce(now);
        }
        wasRecording = recording;

        // Les bons coups, après l'ouverture : le premier mot d'un duel en fait partie.
        callouts.poll();

        int number = NativeCombat.report(incoming);
        if (number != lastReport) {
            lastReport = number;
            summary.show(incoming, number);
            if (callouts.takeRecordsDirty()) com.yuyuframe.launcheragent.runtime.ui.HudConfigStore.save();
        }
        int ranked = NativeCombat.ranked(incomingRanked);
        // Vérifié par le serveur ou non : il arrive avec les points, ou juste après.
        summary.rankedStatus = NativeCombat.rankedStatus(ranked);
        if (ranked != lastRanked) {
            lastRanked = ranked;
            summary.ranked(ranked, incomingRanked);
            // Battre un adversaire bien plus fort : le mot le plus rare, une fois le classement connu.
            boolean win = summary.shownResult() == 1;
            if (win && incomingRanked[NativeCombat.RANKED_CASUAL] < 1 && incomingRanked[NativeCombat.RANKED_FOE] >= GIANT_FOE_FACTOR) {
                callouts.add(PvpCallouts.GIANT, 4, 0);
            }
            refreshRanks();
        }
        summary.tickSounds();

        pollKey();
    }

    /** Une victoire qui vaut au moins ce multiplicateur d'adversaire (le sien à 350, soit +100 sur la moyenne) : un géant tombé. */
    private static final double GIANT_FOE_FACTOR = 1.4;

    /** L'annonce de début de duel, si on tient une masse ou une épée (un autre duel n'est ni noté ni classé). */
    private void announce(long now) {
        int mode = NativeCombat.liveMode();
        if (mode != 1 && mode != 2) return;
        announcedAtNanos = now;
        banner.duelStarted(competitive, rankOf(mode));
        PvpSounds.play(PvpSounds.CHIME, competitive ? 1.2f : 0.9f, 0.6f);
    }

    /** La touche compétitif / casual, sur son front montant, hors des écrans (tchat, champs de texte). */
    private void pollKey() {
        String key = competitiveKey();
        com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern poller =
            com.yuyuframe.launcheragent.apigraphic.platform.lwjgl3.UiInputPollerModern.ACTIVE;
        if (key.isEmpty() || poller == null || com.yuyuframe.launcheragent.runtime.game.ClientData.screenObject() != null) {
            keyHeld = false;
            return;
        }
        boolean down = poller.isKeyDownByName(key);
        if (down && !keyHeld) setCompetitive(!competitive);
        keyHeld = down;
    }

    /**
     * Le panneau de fin de duel.
     *
     * <p><b>Duel classé</b> (le compétitif, docs/scoring/classement.md) : ce
     * qui compte est ce qu'on a gagné, et l'envie d'en relancer un. Sous le
     * résultat, les points du duel qui défilent (« +31 ») à côté de la note,
     * puis le rang : son insigne, la barre de la division qui se remplit ou se
     * vide — et passe à la division suivante en cas de montée, l'insigne
     * s'illuminant —, et combien de victoires il reste avant la suivante. Ni
     * familles ni chiffres : ils sont sur la page du duel.
     *
     * <p><b>Duel non classé</b> (pas noté, mode sans classement) : trois
     * bandes, de haut en bas : le résultat ; la note — ou pourquoi il n'y en a
     * pas — à côté des familles ; les chiffres du duel. Mêmes couleurs que les
     * pages de l'historique.
     *
     * <p>Le fond est la carte de verre du HUD ({@link #glassCard}). Toutes les
     * mesures sont à l'échelle 1 et multipliées par l'échelle de l'élément.
     * Repère du moteur : l'origine est en bas à gauche, y monte.
     */
    private static final class Summary implements HudElement.CustomRenderer {

        /** Durée d'affichage après un duel ordinaire : assez pour lire, pas plus. */
        private static final long SHOWN_NANOS = 8_000_000_000L;

        private static final float WIDTH = 320f;
        private static final float HEADER_H = 30f;
        private static final float GAP = 8f;
        private static final float ROW = 15f;
        private static final float BODY_H = NativeCombat.FAMILY_COUNT * ROW + 10f;
        private static final float FIGURES_H = 36f;
        /** La bande du bas : le meilleur moment du duel, ou le record battu. */
        private static final float MOMENT_H = 18f;
        private static final float HEIGHT = HEADER_H + GAP + BODY_H + GAP + FIGURES_H + GAP + MOMENT_H;

        /** Les mots du duel, pour son meilleur moment. */
        private final PvpCallouts callouts;

        Summary(PvpCallouts callouts) {
            this.callouts = callouts;
        }

        /** Le verdict du duel affiché : 1 victoire, 2 défaite, 3 nul ; 0 sans duel. */
        int shownResult() {
            return received ? (int) shown[NativeCombat.REPORT_RESULT] : 0;
        }
        private static final float SCORE_W = 108f;
        private static final float RADIUS = 6f;

        /** Ce qu'un texte occupe au-dessus de sa ligne de base, par unité d'échelle : sert à le centrer sur une hauteur. */
        private static final float HALF_CAP = 11f;

        /** Le duel d'exemple de l'éditeur de HUD, dans l'ordre du résumé du noyau. */
        private static final double[] SAMPLE = sample();

        /** Un résumé complet (toute la longueur du contrat) : le panneau lit aussi le nombre d'événements de chaque famille. */
        private static double[] sample() {
            double[] report = new double[NativeCombat.REPORT_LEN];
            double[] head = { 342, 0.8, 1, 0, 318, 356, 262, 395, 281, 290, 34, 12, 7, 11, 4, 96 };
            System.arraycopy(head, 0, report, 0, head.length);
            for (int i = 0; i < NativeCombat.FAMILY_COUNT - 1; i++) report[NativeCombat.REPORT_MY_COUNTS + i] = 10;
            report[NativeCombat.REPORT_MODE] = 1;
            report[NativeCombat.REPORT_BASE_SCORE] = 322;
            report[NativeCombat.REPORT_BUG_USE] = 20;
            return report;
        }

        /** Les points de l'exemple de l'éditeur : une victoire qui fait monter d'Or 1 à Or 2. */
        private static final double[] SAMPLE_RANKED = { 31, 20, 6, 1.2, 1, 9, 478, 40, 10, 9, 41, 1, 0 };
        /** Le temps d'une boucle de l'animation d'exemple, dans l'éditeur de HUD. */
        private static final long SAMPLE_LOOP_NANOS = 4_000_000_000L;

        private final double[] shown = new double[NativeCombat.REPORT_LEN];
        private boolean received;
        private long shownAtNanos;
        private int shownNumber;
        private final double[] ranked = new double[NativeCombat.RANKED_LEN];
        /** Les points de classement du duel affiché sont arrivés. */
        private boolean rankedReceived;
        private long rankedAtNanos;

        void show(double[] report, int number) {
            System.arraycopy(report, 0, shown, 0, shown.length);
            shownAtNanos = System.nanoTime();
            shownNumber = number;
            received = true;
            rankedReceived = false;
            ceremony = false;
        }

        /** Les points de classement du résumé {@code number} — ignorés s'ils ne sont pas ceux du duel affiché. */
        void ranked(int number, double[] values) {
            if (!received || number != shownNumber) return;
            System.arraycopy(values, 0, ranked, 0, ranked.length);
            rankedReceived = true;
            rankedAtNanos = System.nanoTime();
            countedShown = 0;
            lastCountSoundNanos = 0;
            divisionSoundPlayed = false;
            // Le panneau reste le temps de voir l'animation entière — plus
            // longtemps quand on change de division, c'est un moment à voir.
            boolean divisionChanged = values[NativeCombat.RANKED_BEFORE_DIVISION] != values[NativeCombat.RANKED_AFTER_DIVISION]
                && values[NativeCombat.RANKED_BEFORE_DUELS] >= PvpRank.PLACEMENT_DUELS && values[NativeCombat.RANKED_CASUAL] < 1;
            long stay = divisionChanged ? RANKED_SHOWN_CHANGED_NANOS : RANKED_SHOWN_NANOS;
            shownAtNanos = Math.max(shownAtNanos, rankedAtNanos - SHOWN_NANOS + stay);

            // Un rang entier franchi (pas une division), ou le rang dévoilé à la
            // fin du placement : le panneau part au centre pour la cérémonie.
            boolean wasPlaced = values[NativeCombat.RANKED_BEFORE_DUELS] >= PvpRank.PLACEMENT_DUELS;
            boolean placedNow = values[NativeCombat.RANKED_PLACED] >= 1;
            boolean casual = values[NativeCombat.RANKED_CASUAL] >= 1;
            int before = (int) values[NativeCombat.RANKED_BEFORE_DIVISION];
            int after = (int) values[NativeCombat.RANKED_AFTER_DIVISION];
            reveal = !wasPlaced && placedNow;
            boolean promoted = wasPlaced && PvpRank.tier(after) > PvpRank.tier(before);
            if (!casual && (reveal || promoted)) {
                ceremony = true;
                ceremonyBeat = -1;
                ceremonyBurst = false;
                ceremonyResonate = false;
                ceremonyTile = false;
                // Une montée laisse d'abord la barre se remplir jusqu'au bord ; un
                // rang dévoilé n'a rien à remplir, la cérémonie vient plus tôt.
                ceremonyAtNanos = rankedAtNanos + (long) ((reveal ? CEREMONY_DELAY_REVEAL : CEREMONY_DELAY) * 1e9);
                long end = ceremonyAtNanos + (long) (Ceremony.LENGTH * 1e9);
                shownAtNanos = Math.max(shownAtNanos, end - SHOWN_NANOS);
            }
        }

        // ── La cérémonie d'un nouveau rang ──

        /** Après l'arrivée des points : la barre monte au bord, puis le panneau part au centre. */
        private static final float CEREMONY_DELAY = 2.2f;
        private static final float CEREMONY_DELAY_REVEAL = 1.0f;
        /** Un nouveau rang (ou le rang dévoilé) à célébrer pour le duel affiché. */
        private boolean ceremony;
        private boolean reveal;
        private long ceremonyAtNanos;

        /** La cérémonie est prévue mais pas commencée : le panneau ne doit pas dévoiler le nouveau rang. */
        private boolean ceremonyPending() {
            return ceremony && received && System.nanoTime() < ceremonyAtNanos;
        }

        private boolean inCeremony() {
            return ceremony && received && System.nanoTime() >= ceremonyAtNanos;
        }

        /** Après l'arrivée des points : de quoi voir défiler le gain et la barre ; plus quand la division change. */
        private static final long RANKED_SHOWN_NANOS = 8_000_000_000L;
        private static final long RANKED_SHOWN_CHANGED_NANOS = 12_000_000_000L;

        // ── Les sons du panneau ──

        /** Le compteur de points déjà sonné, et quand. */
        private long countedShown;
        private long lastCountSoundNanos;
        private boolean divisionSoundPlayed;
        /** Dans la cérémonie : la lueur, le dernier saut du couvercle sonné, l'éclat, la tuile. */
        private int ceremonyBeat = -1;
        private boolean ceremonyResonate;
        private boolean ceremonyBurst;
        private boolean ceremonyTile;

        /**
         * Les sons, à chaque image, au rythme des animations du panneau : un
         * « tic » à chaque point du compteur (montant pour un gain,
         * descendant pour une perte), la montée de division, puis ceux de la
         * cérémonie (battements, machine à sous, éclat). Les temps sont ceux
         * du dessin (mêmes formules), pour que son et image tombent ensemble.
         */
        void tickSounds() {
            if (!received) return;
            long now = System.nanoTime();
            if (rankedReceived && !casual(shown) && rankable(shown) && !inCeremony()) {
                float since = (now - rankedAtNanos) / 1e9f;
                boolean placement = ranked[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS;
                double total = ranked[NativeCombat.RANKED_TOTAL];
                if (!placement && total != 0) {
                    long counted = Math.round(Math.abs(total) * ease(since, 0.1f, 0.8f));
                    if (counted > countedShown && now - lastCountSoundNanos > 40_000_000L) {
                        countedShown = counted;
                        lastCountSoundNanos = now;
                        float progress = (float) (counted / Math.abs(total));
                        if (total > 0) PvpSounds.play(PvpSounds.HAT, 0.9f + 0.9f * progress, 0.35f);
                        else PvpSounds.play(PvpSounds.BASS, 1.2f - 0.5f * progress, 0.35f);
                    }
                }
                // Une division franchie (pas un rang entier : la cérémonie a ses sons).
                int before = (int) ranked[NativeCombat.RANKED_BEFORE_DIVISION];
                int after = (int) ranked[NativeCombat.RANKED_AFTER_DIVISION];
                if (!placement && !ceremony && after > before && !divisionSoundPlayed && since > 1.3f) {
                    divisionSoundPlayed = true;
                    PvpSounds.play(PvpSounds.LEVEL_UP, 1.3f, 0.6f);
                }
                // Une division perdue : grave et discret, quand la barre passe le bord.
                if (!placement && after < before && !divisionSoundPlayed && since > 1.3f) {
                    divisionSoundPlayed = true;
                    PvpSounds.play(PvpSounds.DIDGERIDOO, 0.9f, 0.5f);
                }
                // Un duel de placement compté : un cran plus haut à chacun, de 1 à 10.
                int duelsAfter = (int) ranked[NativeCombat.RANKED_AFTER_DUELS];
                if (placement && !ceremony && duelsAfter > (int) ranked[NativeCombat.RANKED_BEFORE_DUELS]
                        && !divisionSoundPlayed && since > 0.4f) {
                    divisionSoundPlayed = true;
                    PvpSounds.play(PvpSounds.XYLOPHONE, 0.7f + 0.09f * Math.min(duelsAfter, PvpRank.PLACEMENT_DUELS), 0.55f);
                }
            }
            if (inCeremony()) {
                float t = (now - ceremonyAtNanos) / 1e9f;
                float u = t - Ceremony.TRAVEL_SECONDS;
                if (u >= 0f && t < Ceremony.BURST_SECONDS) {
                    // La lueur qui monte au fond du coffre, une fois au début du suspense.
                    if (!ceremonyResonate) {
                        ceremonyResonate = true;
                        PvpSounds.play(PvpSounds.RESONATE, 0.8f, 0.6f);
                    }
                    // Le cliquetis du coffre à chaque saut du couvercle, de plus en plus serrés et aigus.
                    int beat = Ceremony.beat(u);
                    if (beat != ceremonyBeat) {
                        ceremonyBeat = beat;
                        PvpSounds.play(PvpSounds.CHEST_LOCKED, 0.8f + 0.08f * beat, 0.5f + 0.05f * beat);
                    }
                } else if (t >= Ceremony.BURST_SECONDS && !ceremonyBurst) {
                    ceremonyBurst = true;
                    PvpSounds.play(PvpSounds.CHEST_OPEN, 0.9f, 0.9f);
                    PvpSounds.play(PvpSounds.CHALLENGE, 1f, 0.9f);
                    if (ranked[NativeCombat.RANKED_AFTER_DIVISION] >= PvpRank.CHAMPION) {
                        PvpSounds.play(PvpSounds.BEACON, 1.1f, 0.8f);
                    }
                } else if (t >= Ceremony.BURST_SECONDS + 0.35f && !ceremonyTile) {
                    // La tuile sort du coffre.
                    ceremonyTile = true;
                    PvpSounds.play(PvpSounds.TWINKLE, 1.1f, 0.7f);
                }
            }
        }

        /** Un nouveau duel commence : le panneau du précédent s'efface. */
        void hide() {
            received = false;
            ceremony = false;
        }

        /** Noté, avec un verdict, à la masse ou à l'épée : de quoi compter au classement — ou y avoir compté. */
        private static boolean classable(double[] report) {
            int result = (int) report[NativeCombat.REPORT_RESULT];
            int mode = (int) report[NativeCombat.REPORT_MODE];
            return report[NativeCombat.REPORT_SCORE] >= 0 && result >= 1 && result <= 3 && (mode == 1 || mode == 2);
        }

        private static boolean casual(double[] report) {
            return report[NativeCombat.REPORT_CASUAL] >= 1;
        }

        /** Ce duel compte-t-il au classement ? Classable, et joué en compétitif. */
        private static boolean rankable(double[] report) {
            return classable(report) && !casual(report);
        }

        @Override
        public float[] naturalSize() {
            return new float[]{ WIDTH, HEIGHT };
        }

        /** En jeu, seulement les secondes qui suivent un duel. L'éditeur de HUD l'affiche toujours (HudElement.hasContent). */
        @Override
        public boolean hasContent() {
            return received && System.nanoTime() - shownAtNanos < SHOWN_NANOS;
        }

        /** La carte de verre, sauf pendant la cérémonie : le panneau a quitté sa place, il dessine son propre fond au centre. */
        @Override
        public boolean glassCard() {
            return !inCeremony();
        }

        @Override
        public boolean skipBackground() {
            return inCeremony();
        }

        @Override
        public void draw(UiRenderer r, float x, float y, float w, float h, float s, int vw, int vh) {
            if (inCeremony()) {
                float t = (System.nanoTime() - ceremonyAtNanos) / 1e9f;
                Ceremony.draw(r, ranked, reveal, t, x, y, w, h, s, vw, vh);
                return;
            }
            // Jamais de duel noté dans cette partie : on n'est ici que par
            // l'éditeur de HUD, qui a besoin d'un panneau plein pour le placer.
            double[] report = received ? shown : SAMPLE;
            float top = y + h;
            float bottom = drawHeader(r, report, x, x + w, top, s, vw, vh);
            if (!received) {
                // L'exemple de l'éditeur rejoue l'animation en boucle.
                long since = System.nanoTime() % SAMPLE_LOOP_NANOS;
                drawRanked(r, report, SAMPLE_RANKED, since / 1e9f, x, x + w, bottom - GAP * s, s, vw, vh);
            } else if (rankable(report) && rankedReceived && unverified()) {
                // Compétitif, mais pas vérifié par le serveur : il ne compte pas
                // (ou pas encore). La note, le point fort, et pourquoi.
                bottom = drawScoreAndStrengths(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
                drawUnverified(r, ranked, x, x + w, bottom - GAP * s, s, vw, vh);
            } else if (rankable(report)) {
                // Les points arrivent un instant après le résumé : en attendant,
                // la même mise en page, points en attente.
                float since = rankedReceived ? (System.nanoTime() - rankedAtNanos) / 1e9f : -1f;
                drawRanked(r, report, rankedReceived ? ranked : null, since, x, x + w, bottom - GAP * s, s, vw, vh);
            } else if (classable(report) && casual(report)) {
                // Casual : la note, le point fort et ce qui est à travailler,
                // puis ce que le duel aurait rapporté en compétitif.
                bottom = drawScoreAndStrengths(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
                drawMissed(r, report, rankedReceived ? ranked : null, x, x + w, bottom - GAP * s, s, vw, vh);
            } else {
                bottom = drawScoreAndFamilies(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
                drawFigures(r, report, x, x + w, bottom - GAP * s, s, vw, vh);
            }
            drawMoment(r, received ? callouts.moment() : Lang.tr("Meilleur moment :") + " " + PvpCallouts.label(PvpCallouts.PERFECT_SMASH, 0)
                + " " + Lang.tr("à") + " 0:42", received ? callouts.momentColor() : new UiColor(255, 206, 72, 255), x, x + w, y, s, vw, vh);
        }

        /** La bande du bas : le meilleur moment du duel, ou le record battu (en or). Rien sans coup marquant. */
        private void drawMoment(UiRenderer r, String text, UiColor tone, float left, float right, float bottom, float s, int vw, int vh) {
            if (text.isEmpty()) return;
            float top = bottom + MOMENT_H * s;
            tinted(r, left, bottom, right, top, RADIUS * s, tone, 0.18f, vw, vh);
            float scale = 0.28f * s;
            centered(r, UiFont.BOLD, text, (left + right) / 2f, (bottom + top) / 2f - HALF_CAP * scale, right - left - 16f * s,
                tone, scale, vw, vh);
        }

        /**
         * Casual : la note dans son cadre, et à sa droite seulement ce qui
         * sert — la meilleure famille du duel (« TON POINT FORT ») et la plus
         * faible (« À TRAVAILLER »). Rend le bas de la bande.
         */
        private float drawScoreAndStrengths(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - BODY_H * s;
            float boxRight = drawScoreBox(r, report, left, bottom, top, s, vw, vh);
            float cardLeft = boxRight + GAP * s;
            r.drawRoundedRect(cardLeft, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
            int best = -1, worst = -1;
            for (int i = 0; i < familyRows(report); i++) {
                if (report[NativeCombat.REPORT_MY_COUNTS + i] <= 0) continue;
                double value = report[NativeCombat.REPORT_FAMILIES + i];
                if (best < 0 || value > report[NativeCombat.REPORT_FAMILIES + best]) best = i;
                if (worst < 0 || value < report[NativeCombat.REPORT_FAMILIES + worst]) worst = i;
            }
            float inner = 12f * s;
            float width = right - cardLeft - inner * 2f;
            if (best < 0) {
                r.drawText(UiFont.REGULAR, Lang.tr("Pas assez d'actions pour juger"), cardLeft + inner, (top + bottom) / 2f - HALF_CAP * 0.3f * s,
                    UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
                return bottom;
            }
            float half = (top - bottom) / 2f;
            strength(r, Lang.tr("TON POINT FORT"), best, report, cardLeft + inner, top, half, width, s, vw, vh);
            if (worst != best) {
                strength(r, Lang.tr("À TRAVAILLER"), worst, report, cardLeft + inner, top - half, half, width, s, vw, vh);
            }
            return bottom;
        }

        /** Une moitié de la carte casual : le titre en petit, la famille et sa valeur dans sa couleur. */
        private void strength(UiRenderer r, String title, int family, double[] report, float left, float top, float height, float width,
                              float s, int vw, int vh) {
            double value = report[NativeCombat.REPORT_FAMILIES + family];
            UiColor tone = PvpScoreUi.scoreColor(value);
            r.drawText(UiFont.BOLD, title, left, top - 14f * s, UiTheme.TEXT_MUTED, 0.26f * s, vw, vh);
            float scale = 0.42f * s;
            String name = Lang.tr(familyName(family, report));
            String number = String.valueOf(Math.round(value));
            float numberW = r.textWidth(UiFont.BOLD, number, scale);
            float nameScale = scale;
            float nameW = r.textWidth(UiFont.BOLD, name, nameScale);
            float room = width - numberW - 10f * s;
            if (nameW > room && nameW > 0f) nameScale *= room / nameW;
            float baseline = top - height + 9f * s;
            r.drawText(UiFont.BOLD, name, left, baseline, UiTheme.TEXT_PRIMARY, nameScale, vw, vh);
            r.drawText(UiFont.BOLD, number, left + width - numberW, baseline, tone, scale, vw, vh);
        }

        /** Le statut du classement du duel affiché ({@code NativeCombat.RANKED_STATUS_*}), lu à chaque tick. */
        volatile int rankedStatus;

        /** Un duel compétitif que le serveur n'a pas (ou pas encore) vérifié : il ne compte pas. */
        private boolean unverified() {
            int status = rankedStatus;
            return status == NativeCombat.RANKED_STATUS_REJECTED || status == NativeCombat.RANKED_STATUS_QUEUED
                || status == NativeCombat.RANKED_STATUS_NO_ACCOUNT || status == NativeCombat.RANKED_STATUS_OFFLINE_SERVER;
        }

        /**
         * Pourquoi ce duel compétitif ne compte pas (ou pas encore), et ce
         * qu'il aurait rapporté. Même case que {@link #drawMissed}.
         */
        private void drawUnverified(UiRenderer r, double[] values, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - FIGURES_H * s;
            float inner = 12f * s;
            r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
            String text, how;
            UiColor tone;
            switch (rankedStatus) {
                case NativeCombat.RANKED_STATUS_REJECTED -> {
                    text = Lang.tr("Duel non vérifié : il ne compte pas");
                    how = Lang.tr("Le serveur n'a pas trouvé le même résultat");
                    tone = PvpScoreUi.BAD;
                }
                case NativeCombat.RANKED_STATUS_QUEUED -> {
                    text = Lang.tr("En attente de vérification");
                    how = Lang.tr("Il comptera dès que le serveur répondra");
                    tone = UiTheme.TEXT_SECONDARY;
                }
                case NativeCombat.RANKED_STATUS_NO_ACCOUNT -> {
                    text = Lang.tr("Pas classé : connecte ton compte YuyuFrame");
                    how = Lang.tr("Le classement est vérifié par le serveur");
                    tone = UiTheme.ACCENT;
                }
                default -> {
                    text = Lang.tr("Pas classé sur un serveur en mode hors ligne");
                    how = Lang.tr("Ton compte Minecraft n'y est pas prouvé");
                    tone = UiTheme.TEXT_SECONDARY;
                }
            }
            if (values != null && values[NativeCombat.RANKED_BEFORE_DUELS] >= PvpRank.PLACEMENT_DUELS) {
                long total = Math.round(values[NativeCombat.RANKED_TOTAL]);
                text += " (" + (total > 0 ? "+" : total < 0 ? "-" : "") + Math.abs(total) + " RR)";
            }
            float scale = 0.3f * s;
            float full = r.textWidth(UiFont.BOLD, text, scale);
            float room = right - left - inner * 2f;
            if (full > room && full > 0f) scale *= room / full;
            r.drawText(UiFont.BOLD, text, left + inner, bottom + 19f * s, tone, scale, vw, vh);
            r.drawText(UiFont.REGULAR, how, left + inner, bottom + 6f * s, UiTheme.TEXT_MUTED, 0.25f * s, vw, vh);
        }

        /**
         * Un duel casual : « En compétitif : +24 RR » (ou ce qu'il aurait
         * coûté, ou le placement qu'il aurait fait avancer), et comment y
         * passer. {@code values} : ce que le noyau a calculé sans l'appliquer,
         * nul tant qu'il n'est pas arrivé.
         */
        private void drawMissed(UiRenderer r, double[] report, double[] values, float left, float right, float top, float s,
                                int vw, int vh) {
            float bottom = top - FIGURES_H * s;
            float inner = 12f * s;
            r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
            String text;
            UiColor tone;
            if (values == null) {
                text = Lang.tr("En compétitif : calcul…");
                tone = UiTheme.TEXT_SECONDARY;
            } else if (values[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS) {
                text = Lang.tr("En compétitif, ce duel aurait compté pour ton placement");
                tone = UiTheme.ACCENT;
            } else {
                long total = Math.round(values[NativeCombat.RANKED_TOTAL]);
                String points = (total > 0 ? "+" : total < 0 ? "-" : "") + Math.abs(total) + " RR";
                text = Lang.tr(total >= 0 ? "En compétitif, ce duel t'aurait rapporté" : "En compétitif, ce duel t'aurait coûté")
                    + " " + points;
                tone = total > 0 ? PvpScoreUi.GOOD : total < 0 ? PvpScoreUi.BAD : UiTheme.TEXT_SECONDARY;
            }
            // Rétréci s'il dépasse : une traduction plus longue reste dans la case.
            float scale = 0.3f * s;
            float full = r.textWidth(UiFont.BOLD, text, scale);
            float room = right - left - inner * 2f;
            if (full > room && full > 0f) scale *= room / full;
            r.drawText(UiFont.BOLD, text, left + inner, bottom + 19f * s, tone, scale, vw, vh);
            PvpScoreModule module = instance;
            String key = module == null ? "" : module.competitiveKey();
            String how = key.isEmpty() ? Lang.tr("Passe en compétitif depuis la page Score PvP")
                : "[" + key + "] " + Lang.tr("passer en compétitif");
            r.drawText(UiFont.REGULAR, how, left + inner, bottom + 6f * s, UiTheme.TEXT_MUTED, 0.25f * s, vw, vh);
        }

        private static float ease(float since, float delay, float duration) {
            float t = Math.max(0f, Math.min(1f, (since - delay) / duration));
            float u = 1f - t;
            return 1f - u * u * u;
        }

        /**
         * Le duel classé : les points et la note, puis le rang qui progresse.
         * {@code values} nul tant que les points ne sont pas arrivés ;
         * {@code since} : secondes depuis leur arrivée.
         */
        private void drawRanked(UiRenderer r, double[] report, double[] values, float since, float left, float right, float top,
                                float s, int vw, int vh) {
            float rowA = 66f * s;
            float gap = GAP * s;
            float aBottom = top - rowA;

            // La note, petite : elle explique le gain, elle n'est plus le sujet.
            float boxRight = left + 92f * s;
            float center = (left + boxRight) / 2f;
            double score = report[NativeCombat.REPORT_SCORE];
            UiColor scoreTone = PvpScoreUi.scoreColor(score);
            tinted(r, left, aBottom, boxRight, top, RADIUS * s, scoreTone, 0.2f, vw, vh);
            centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - 17f * s, 78f * s, UiTheme.TEXT_MUTED, 0.28f * s, vw, vh);
            centered(r, UiFont.BOLD, PvpScoreUi.scoreText(score), center, aBottom + 14f * s, 78f * s, scoreTone, 0.95f * s, vw, vh);

            // Les points du duel, en grand : c'est ce qu'on regarde d'abord.
            float cardLeft = boxRight + gap;
            double total = values == null ? 0 : values[NativeCombat.RANKED_TOTAL];
            UiColor tone = values == null ? UiTheme.TEXT_SECONDARY : total > 0 ? PvpScoreUi.GOOD : total < 0 ? PvpScoreUi.BAD : UiTheme.TEXT_SECONDARY;
            tinted(r, cardLeft, aBottom, right, top, RADIUS * s, tone, values == null ? 0.08f : 0.28f, vw, vh);
            float inner = 12f * s;
            r.drawText(UiFont.BOLD, Lang.tr("CLASSEMENT"), cardLeft + inner, top - 17f * s, UiTheme.TEXT_MUTED, 0.28f * s, vw, vh);
            if (values == null) {
                r.drawText(UiFont.REGULAR, Lang.tr("Calcul des points…"), cardLeft + inner, aBottom + 22f * s, UiTheme.TEXT_SECONDARY,
                    0.36f * s, vw, vh);
            } else if (values[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS) {
                // Un duel de placement : ses points restent cachés jusqu'au rang
                // de départ, on montre le verdict et où en est le placement.
                int result = (int) report[NativeCombat.REPORT_RESULT];
                UiColor verdict = PvpScoreUi.resultColor(result);
                float pop = 1f + 0.18f * (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, (since - 0.2f) / 0.3f)));
                r.drawText(UiFont.BOLD, PvpScoreUi.resultText(result).toUpperCase(java.util.Locale.ROOT), cardLeft + inner,
                    aBottom + 20f * s, verdict, 0.9f * s * pop, vw, vh);
                boolean counted = values[NativeCombat.RANKED_AFTER_DUELS] > values[NativeCombat.RANKED_BEFORE_DUELS];
                int done = (int) values[NativeCombat.RANKED_AFTER_DUELS];
                String line = !counted ? Lang.tr("Ne compte pas : déjà 2 duels contre lui au placement")
                    : done >= PvpRank.PLACEMENT_DUELS ? Lang.tr("Placement terminé")
                    : Lang.tr("Duel de placement") + " " + done + " / " + PvpRank.PLACEMENT_DUELS;
                r.drawText(UiFont.REGULAR, line, cardLeft + inner, aBottom + 7f * s, UiTheme.TEXT_MUTED, 0.24f * s, vw, vh);
            } else {
                long shownTotal = Math.round(total * ease(since, 0.1f, 0.8f));
                String points = (shownTotal > 0 ? "+" : shownTotal < 0 ? "-" : "") + Math.abs(shownTotal);
                // Un petit rebond à l'arrivée du chiffre.
                float pop = 1f + 0.18f * (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, (since - 0.75f) / 0.3f)));
                float big = 1.3f * s * pop;
                r.drawText(UiFont.BOLD, points, cardLeft + inner, aBottom + 18f * s, tone, big, vw, vh);
                float after = cardLeft + inner + r.textWidth(UiFont.BOLD, points, big) + 6f * s;
                r.drawText(UiFont.BOLD, "RR", after, aBottom + 18f * s, tone.multiplyAlpha(0.8f), 0.42f * s, vw, vh);
                // D'où viennent les points, en une ligne — sauf une défaite que
                // le jeu a adoucie : c'est ça qu'on dit, en vert.
                long saved = savedByPlay(values, (int) report[NativeCombat.REPORT_RESULT]);
                if (saved >= 1) {
                    r.drawText(UiFont.BOLD, Lang.tr("Ton jeu t'a sauvé") + " " + saved + " RR", cardLeft + inner, aBottom + 7f * s,
                        PvpScoreUi.GOOD, 0.26f * s, vw, vh);
                } else {
                    r.drawText(UiFont.REGULAR, breakdown(values, (int) report[NativeCombat.REPORT_RESULT]), cardLeft + inner, aBottom + 7f * s,
                        UiTheme.TEXT_MUTED, 0.24f * s, vw, vh);
                }
            }

            drawProgress(r, values, (int) report[NativeCombat.REPORT_RESULT], since, left, right, aBottom - gap, s, vw, vh);
        }

        /**
         * « Victoire +20 · Jeu +6 · Adversaire x1,20 » (et « même adversaire
         * x0,50 » s'il y a lieu). Ni « × » ni « − » : la police ne les a pas.
         */
        private static String breakdown(double[] v, int result) {
            String outcome = Lang.tr(result == 1 ? "Victoire" : result == 2 ? "Défaite" : "Nul");
            StringBuilder text = new StringBuilder(outcome).append(' ').append(signed(v[NativeCombat.RANKED_BASE]))
                .append("  ·  ").append(Lang.tr("Jeu")).append(' ').append(signed(v[NativeCombat.RANKED_PLAY]))
                .append("  ·  ").append(Lang.tr("Adversaire")).append(" x")
                .append(String.format(java.util.Locale.FRANCE, "%.2f", v[NativeCombat.RANKED_FOE]));
            if (v[NativeCombat.RANKED_REPEAT] < 0.999) {
                text.append("  ·  ").append(Lang.tr("même adversaire")).append(" x")
                    .append(String.format(java.util.Locale.FRANCE, "%.2f", v[NativeCombat.RANKED_REPEAT]));
            }
            return text.toString();
        }

        /**
         * Ce que le jeu a épargné sur une défaite : la perte sans le terme de
         * jeu, moins la perte réelle (les mêmes bornes que {@code ranked.rs} :
         * au moins 5, puis la répétition). 0 hors défaite ou sans jeu positif.
         */
        private static long savedByPlay(double[] v, int result) {
            if (result != 2 || v[NativeCombat.RANKED_PLAY] <= 0) return 0;
            double withoutPlay = Math.min(-5.0, v[NativeCombat.RANKED_BASE] * v[NativeCombat.RANKED_FOE]) * v[NativeCombat.RANKED_REPEAT];
            return Math.max(0, Math.round(v[NativeCombat.RANKED_TOTAL]) - Math.round(withoutPlay));
        }

        private static String signed(double value) {
            long rounded = Math.round(value);
            return (rounded > 0 ? "+" : "") + rounded;
        }

        /**
         * Le rang, qui progresse : l'insigne, le nom, la barre de la division
         * animée de la position d'avant à celle d'après — en points cumulés,
         * donc une montée passe le bord et recommence à la division suivante —,
         * et ce qu'il reste à faire. Pendant le placement : dix cases, celle
         * de ce duel dans la couleur de son verdict ({@code result}).
         */
        private void drawProgress(UiRenderer r, double[] v, int result, float since, float left, float right, float top, float s,
                                  int vw, int vh) {
            float bottom = top - 70f * s;
            float inner = 12f * s;
            if (v == null) {
                r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                return;
            }
            int beforeDivision = (int) v[NativeCombat.RANKED_BEFORE_DIVISION];
            int afterDivision = (int) v[NativeCombat.RANKED_AFTER_DIVISION];
            boolean placed = v[NativeCombat.RANKED_PLACED] >= 1;

            if (!placed) {
                // Le placement : le rang est caché, on voit les duels qui restent.
                r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                r.drawText(UiFont.BOLD, Lang.tr("NON CLASSÉ"), left + inner, top - 22f * s, UiTheme.TEXT_PRIMARY, 0.5f * s, vw, vh);
                int done = (int) v[NativeCombat.RANKED_AFTER_DUELS];
                boolean counted = done > (int) v[NativeCombat.RANKED_BEFORE_DUELS];
                float pipGap = 4f * s;
                float pipW = (right - left - inner * 2f - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
                float pipY = bottom + 26f * s;
                for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                    float x = left + inner + i * (pipW + pipGap);
                    boolean filled = i < done;
                    boolean current = counted && i == done - 1;
                    // La case de ce duel s'allume à son tour, dans la couleur de son verdict.
                    float light = current ? ease(since, 0.6f, 0.6f) : 1f;
                    UiColor lit = current ? PvpScoreUi.resultColor(result) : UiTheme.ACCENT;
                    UiColor color = filled ? UiColor.lerp(UiTheme.PANEL_BG, lit, light) : UiTheme.PANEL_BG;
                    r.drawRoundedRect(x, pipY, x + pipW, pipY + 8f * s, 3f * s, color, vw, vh);
                }
                int remaining = Math.max(0, PvpRank.PLACEMENT_DUELS - done);
                String goal = Lang.tr("Rang dévoilé dans") + " " + remaining + " " + Lang.tr(remaining > 1 ? "duels" : "duel");
                r.drawText(UiFont.REGULAR, goal, left + inner, bottom + 9f * s, UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
                return;
            }

            if (ceremony && reveal) {
                // Le rang sera dévoilé par la cérémonie : ici, le placement achevé, rien de plus.
                r.drawRoundedRect(left, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                r.drawText(UiFont.BOLD, Lang.tr("PLACEMENT TERMINÉ"), left + inner, top - 22f * s, UiTheme.TEXT_PRIMARY, 0.5f * s, vw, vh);
                float pipGap = 4f * s;
                float pipW = (right - left - inner * 2f - pipGap * (PvpRank.PLACEMENT_DUELS - 1)) / PvpRank.PLACEMENT_DUELS;
                float pipY = bottom + 26f * s;
                for (int i = 0; i < PvpRank.PLACEMENT_DUELS; i++) {
                    float x = left + inner + i * (pipW + pipGap);
                    r.drawRoundedRect(x, pipY, x + pipW, pipY + 8f * s, 3f * s, UiTheme.ACCENT, vw, vh);
                }
                r.drawText(UiFont.REGULAR, Lang.tr("Ton rang se dévoile…"), left + inner, bottom + 9f * s, UiTheme.TEXT_SECONDARY,
                    0.3f * s, vw, vh);
                return;
            }

            double from = PvpRank.cumulative(beforeDivision, v[NativeCombat.RANKED_BEFORE_POINTS]);
            double to = PvpRank.cumulative(afterDivision, v[NativeCombat.RANKED_AFTER_POINTS]);
            // Le rang vient d'être dévoilé (fin du placement) : pas de trajet, il apparaît.
            boolean revealed = v[NativeCombat.RANKED_BEFORE_DUELS] < PvpRank.PLACEMENT_DUELS;
            double now = revealed ? to : from + (to - from) * ease(since, 0.5f, 1.4f);
            // Un nouveau rang entier : la barre monte jusqu'au bord et s'y arrête,
            // la cérémonie dira la suite.
            boolean onTheEdge = false;
            if (ceremony && !reveal) {
                double edge = PvpRank.cumulative(PvpRank.tier(afterDivision) * PvpRank.DIVISIONS_PER_TIER, 0) - 0.5;
                onTheEdge = now >= edge;
                now = Math.min(now, edge);
            }
            int division = PvpRank.divisionAt(now);
            double points = PvpRank.pointsAt(now);
            UiColor rank = PvpRank.color(division);
            boolean promoted = division > beforeDivision && !revealed;
            boolean demoted = division < beforeDivision;
            // L'éclat d'une montée ou d'un rang dévoilé : fort à l'instant, puis qui respire.
            float glow = 0f;
            if (promoted || revealed) {
                float burst = Math.max(0f, 1f - (since - 1.2f) / 1.5f);
                glow = Math.min(1f, 0.35f + burst + 0.15f * (float) Math.sin(since * 4f));
            }
            tinted(r, left, bottom, right, top, RADIUS * s, rank, 0.22f + 0.18f * glow, vw, vh);

            float badge = 44f * s;
            float badgeX = left + inner;
            float badgeY = bottom + (70f * s - badge) / 2f;
            PvpRank.badge(r, division, badgeX, badgeY, badge, glow, 1f, vw, vh);

            float textLeft = badgeX + badge + 12f * s;
            float textRight = right - inner;
            r.drawText(UiFont.BOLD, PvpRank.name(division).toUpperCase(java.util.Locale.ROOT), textLeft, top - 24f * s, rank,
                0.52f * s, vw, vh);
            String status = onTheEdge ? Lang.tr("Et maintenant…") : revealed ? Lang.tr("RANG DÉVOILÉ !") : promoted ? Lang.tr("PROMOTION !")
                : demoted ? Lang.tr("Rétrogradé") : "";
            if (!status.isEmpty()) {
                float scale = 0.34f * s;
                UiColor statusTone = demoted ? PvpScoreUi.BAD : rank;
                r.drawText(UiFont.BOLD, status, textRight - r.textWidth(UiFont.BOLD, status, scale), top - 22f * s, statusTone, scale, vw, vh);
            }

            // La barre de la division ; en Champion, pleine.
            float barY = bottom + 25f * s;
            float barH = 8f * s;
            r.drawRoundedRect(textLeft, barY, textRight, barY + barH, barH / 2f, UiTheme.PANEL_BG, vw, vh);
            double fraction = division >= PvpRank.CHAMPION ? 1.0 : Math.min(1.0, points / PvpRank.DIVISION_POINTS);
            float filled = textLeft + (textRight - textLeft) * (float) fraction;
            if (filled > textLeft + 1f) {
                r.drawGradientRect2D(textLeft, barY, filled, barY + barH, barH / 2f,
                    UiColor.lerp(rank, new UiColor(0, 0, 0, 255), 0.25f), rank,
                    UiColor.lerp(rank, new UiColor(0, 0, 0, 255), 0.25f), rank, vw, vh);
            }

            String count = division >= PvpRank.CHAMPION
                ? Math.round(points) + " " + Lang.tr("points")
                : Math.round(points) + " / " + PvpRank.DIVISION_POINTS;
            r.drawText(UiFont.BOLD, count, textLeft, bottom + 9f * s, UiTheme.TEXT_PRIMARY, 0.3f * s, vw, vh);
            String goal = PvpRank.nextGoal(division, points);
            if (!goal.isEmpty()) {
                float scale = 0.27f * s;
                float width = Math.min(r.textWidth(UiFont.REGULAR, goal, scale), textRight - textLeft - 60f * s);
                centeredRight(r, goal, textRight, bottom + 9f * s, width, scale, vw, vh);
            }
        }

        /** Un texte calé à droite sur {@code right}, rétréci s'il dépasse {@code maxWidth}. */
        private static void centeredRight(UiRenderer r, String text, float right, float y, float maxWidth, float scale, int vw, int vh) {
            float width = r.textWidth(UiFont.REGULAR, text, scale);
            if (width > maxWidth && width > 0f) {
                scale *= maxWidth / width;
                width = maxWidth;
            }
            r.drawText(UiFont.REGULAR, text, right - width, y, UiTheme.TEXT_SECONDARY, scale, vw, vh);
        }

        /** Le bandeau, teinté par le résultat : le nom à gauche, le résultat à droite. Rend son bas. */
        private float drawHeader(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - HEADER_H * s;
            int result = (int) report[NativeCombat.REPORT_RESULT];
            UiColor tone = PvpScoreUi.resultColor(result);
            tinted(r, left, bottom, right, top, RADIUS * s, tone, 0.3f, vw, vh);
            r.drawRoundedRect(left, bottom, left + 3f * s, top, 1.5f * s, tone, vw, vh);

            float center = (top + bottom) / 2f;
            float small = 0.32f * s;
            r.drawText(UiFont.BOLD, Lang.tr("SCORE PVP"), left + 12f * s, center - HALF_CAP * small, UiTheme.TEXT_SECONDARY, small, vw, vh);
            String text = PvpScoreUi.resultText(result).toUpperCase(java.util.Locale.ROOT);
            if (!text.isEmpty()) {
                float scale = 0.46f * s;
                r.drawText(UiFont.BOLD, text, right - 12f * s - r.textWidth(UiFont.BOLD, text, scale), center - HALF_CAP * scale,
                    tone, scale, vw, vh);
            }
            return bottom;
        }

        /** La note dans son cadre, en couleur — ou pourquoi il n'y en a pas — et les six familles à sa droite. Rend le bas de la bande. */
        private float drawScoreAndFamilies(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float height = BODY_H * s;
            float bottom = top - height;
            float boxRight = drawScoreBox(r, report, left, bottom, top, s, vw, vh);
            drawFamilies(r, report, boxRight, right, bottom, top, s, vw, vh);
            return bottom;
        }

        /** La note dans son cadre, en couleur — ou pourquoi il n'y en a pas. Rend le bord droit du cadre. */
        private float drawScoreBox(UiRenderer r, double[] report, float left, float bottom, float top, float s, int vw, int vh) {
            float boxRight = left + SCORE_W * s;
            float center = (left + boxRight) / 2f;
            float fit = (SCORE_W - 14f) * s;
            double score = report[NativeCombat.REPORT_SCORE];
            UiColor tone = PvpScoreUi.scoreColor(score);
            tinted(r, left, bottom, boxRight, top, RADIUS * s, tone, 0.24f, vw, vh);
            centered(r, UiFont.BOLD, Lang.tr("NOTE"), center, top - 18f * s, fit, UiTheme.TEXT_MUTED, 0.3f * s, vw, vh);
            if (score >= 0) {
                // Le bonus bug use, à part : « 322 + 20 bug use » sous la note.
                double bonus = report[NativeCombat.REPORT_BUG_USE];
                boolean withBonus = bonus >= 0.5;
                centered(r, UiFont.BOLD, PvpScoreUi.scoreText(score), center, bottom + (withBonus ? 46f : 38f) * s, fit, tone, 1.25f * s, vw, vh);
                if (withBonus) {
                    centered(r, UiFont.BOLD, PvpScoreUi.bugUseText(report[NativeCombat.REPORT_BASE_SCORE], bonus), center, bottom + 27f * s, fit,
                        PvpScoreUi.BUG_USE, 0.3f * s, vw, vh);
                }
                String confidence = Lang.tr("Confiance") + " " + Math.round(report[NativeCombat.REPORT_CONFIDENCE] * 100) + " %";
                centered(r, UiFont.REGULAR, confidence, center, bottom + (withBonus ? 11f : 14f) * s, fit, UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
            } else {
                centered(r, UiFont.BOLD, PvpScoreUi.NO_SCORE, center, bottom + 46f * s, fit, tone, 1.1f * s, vw, vh);
                String[] reason = PvpScoreUi.reasonLines(report);
                centered(r, UiFont.REGULAR, Lang.tr(reason[0]), center, bottom + 26f * s, fit,
                    UiTheme.TEXT_SECONDARY, 0.3f * s, vw, vh);
                centered(r, UiFont.REGULAR, Lang.tr(reason[1]), center, bottom + 13f * s, fit, UiTheme.TEXT_MUTED, 0.3f * s, vw, vh);
            }
            return boxRight;
        }

        /** Les familles, à droite du cadre de la note : libellé, barre dans la couleur de sa valeur, valeur. */
        private void drawFamilies(UiRenderer r, double[] report, float boxRight, float right, float bottom, float top, float s, int vw, int vh) {
            float cardLeft = boxRight + GAP * s;
            r.drawRoundedRect(cardLeft, bottom, right, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
            float inner = 10f * s;
            float label = 0.32f * s;
            float barLeft = cardLeft + inner + 88f * s;
            float barRight = right - inner - 24f * s;
            float rowTop = top - 5f * s;
            for (int i = 0; i < NativeCombat.FAMILY_COUNT; i++) {
                // À l'épée, pas de cinquième famille : la ligne n'est pas dessinée.
                if (i >= familyRows(report) && i < NativeCombat.FAMILY_COUNT - 1) continue;
                float middle = rowTop - ROW * s / 2f;
                rowTop -= ROW * s;
                double family = Math.max(0, Math.min(NativeCombat.SCORE_MAX, report[NativeCombat.REPORT_FAMILIES + i]));
                // Le niveau de l'adversaire parle de lui, pas de nous : gris,
                // pour qu'il ne se lise pas comme une de nos familles.
                boolean opponent = i == NativeCombat.FAMILY_COUNT - 1;
                // Aucune mesure (aucun smash porté…) : « - », pas un 250 qui se lirait comme une note.
                boolean measured = opponent || report[NativeCombat.REPORT_MY_COUNTS + i] > 0;
                if (!measured) family = 0;
                UiColor familyTone = opponent ? PvpScoreUi.FOE : measured ? PvpScoreUi.scoreColor(family) : PvpScoreUi.NEUTRAL;
                float baseline = middle - HALF_CAP * label;
                r.drawText(Lang.tr(shortFamilyName(i, report)), cardLeft + inner, baseline,
                    opponent ? UiTheme.TEXT_MUTED : UiTheme.TEXT_SECONDARY, label, vw, vh);
                float barY = middle - 2.5f * s;
                float barTop = middle + 2.5f * s;
                r.drawRoundedRect(barLeft, barY, barRight, barTop, 2.5f * s, UiTheme.PANEL_BG, vw, vh);
                float filled = barLeft + (barRight - barLeft) * (float) (family / NativeCombat.SCORE_MAX);
                if (filled > barLeft + 1f) {
                    r.drawRoundedRect(barLeft, barY, filled, barTop, 2.5f * s, familyTone, vw, vh);
                }
                String value = measured ? String.valueOf(Math.round(family)) : PvpScoreUi.NO_SCORE;
                r.drawText(UiFont.BOLD, value, right - inner - r.textWidth(UiFont.BOLD, value, label), baseline, familyTone, label, vw, vh);
            }
        }

        /** Les chiffres du duel, en quatre cases égales : la valeur, son nom dessous. */
        private void drawFigures(UiRenderer r, double[] report, float left, float right, float top, float s, int vw, int vh) {
            float bottom = top - FIGURES_H * s;
            // La durée du duel, pas le seul temps au contact.
            long seconds = Math.round(report[NativeCombat.REPORT_DURATION_SECS]);
            // L'épée n'a pas d'opportunités de smash : ses deux premières cases
            // sont le W-tap et le plus long combo, le cœur du mode.
            String[][] figures = isSword(report) ? new String[][] {
                { "W-TAPS", (int) report[NativeCombat.REPORT_SWORD_WTAPS_LANDED] + " / " + (int) report[NativeCombat.REPORT_SWORD_WTAPS] },
                { "PLUS LONG COMBO", String.valueOf((int) report[NativeCombat.REPORT_SWORD_LONGEST_COMBO]) },
                { "DURÉE", seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s" },
                { "PING", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : PvpScoreUi.NO_SCORE },
            } : new String[][] {
                { "CONVERTIES", (int) report[NativeCombat.REPORT_MY_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_MY_OPENED] },
                { "SUBIES", (int) report[NativeCombat.REPORT_THEIR_CONVERTED] + " / " + (int) report[NativeCombat.REPORT_THEIR_OPENED] },
                { "DURÉE", seconds >= 60 ? (seconds / 60) + " min " + (seconds % 60) + " s" : seconds + " s" },
                // Le ping est affiché, jamais corrigé.
                { "PING", report[NativeCombat.REPORT_PING] >= 0 ? Math.round(report[NativeCombat.REPORT_PING]) + " ms" : PvpScoreUi.NO_SCORE },
            };
            float gap = 6f * s;
            float cell = (right - left - gap * (figures.length - 1)) / figures.length;
            float fit = cell - 8f * s;
            for (int i = 0; i < figures.length; i++) {
                float x = left + i * (cell + gap);
                r.drawRoundedRect(x, bottom, x + cell, top, RADIUS * s, UiTheme.CARD_BG, vw, vh);
                float center = x + cell / 2f;
                centered(r, UiFont.BOLD, figures[i][1], center, bottom + 17f * s, fit, UiTheme.TEXT_PRIMARY, 0.42f * s, vw, vh);
                centered(r, UiFont.REGULAR, Lang.tr(figures[i][0]), center, bottom + 6f * s, fit, UiTheme.TEXT_MUTED, 0.25f * s, vw, vh);
            }
        }

        /** Une carte teintée comme sur les pages : la carte grise, puis la couleur en transparence, plus franche à gauche. */
        private static void tinted(UiRenderer r, float x1, float y1, float x2, float y2, float radius, UiColor tone, float strength,
                                   int vw, int vh) {
            r.drawRoundedRect(x1, y1, x2, y2, radius, UiTheme.CARD_BG, vw, vh);
            UiColor strong = tone.withAlpha(strength);
            UiColor faint = tone.withAlpha(strength * 0.25f);
            r.drawGradientRect2D(x1, y1, x2, y2, radius, strong, faint, strong, faint, vw, vh);
        }

        /** Un texte centré sur {@code center}, rétréci s'il dépasse {@code maxWidth} : une traduction plus longue ne sort pas de sa case. */
        private static void centered(UiRenderer r, UiFont font, String text, float center, float y, float maxWidth,
                                     UiColor color, float scale, int vw, int vh) {
            float width = r.textWidth(font, text, scale);
            if (width > maxWidth && width > 0f) {
                scale *= maxWidth / width;
                width = maxWidth;
            }
            r.drawText(font, text, center - width / 2f, y, color, scale, vw, vh);
        }
    }

    /**
     * L'annonce, en haut de l'écran pendant deux secondes et demie : ce qui
     * est en jeu au début d'un duel (« DUEL CLASSÉ · Or 2 » dans la couleur du
     * rang, « DUEL CASUAL » en gris), et le choix qui vient de changer par la
     * touche ou la page. Le compétitif a la couleur, le casual reste neutre.
     */
    private static final class Banner implements HudElement.CustomRenderer {

        private static final float WIDTH = 300f;
        private static final float HEIGHT = 30f;
        private static final float SHOWN_SECONDS = 2.6f;

        private String title = "";
        private String detail = "";
        private UiColor tone = UiTheme.ACCENT;
        private long shownAtNanos;
        private boolean active;

        void duelStarted(boolean competitive, com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank rank) {
            if (competitive) show(Lang.tr("DUEL CLASSÉ"), rankLabel(rank), rankColor(rank));
            else show(Lang.tr("DUEL CASUAL"), Lang.tr("ton rang n'est pas en jeu"), PvpScoreUi.NEUTRAL);
        }

        void switched(boolean competitive, boolean inDuel, com.yuyuframe.launcheragent.runtime.combat.DuelHistory.Rank best) {
            String later = inDuel ? "  ·  " + Lang.tr("au prochain duel") : "";
            if (competitive) show(Lang.tr("COMPÉTITIF"), rankLabel(best) + later, rankColor(best));
            else show(Lang.tr("CASUAL"), Lang.tr("ton rang n'est pas en jeu") + later, PvpScoreUi.NEUTRAL);
        }

        private void show(String title, String detail, UiColor tone) {
            this.title = title;
            this.detail = detail;
            this.tone = tone;
            shownAtNanos = System.nanoTime();
            active = true;
        }

        private float since() {
            return (System.nanoTime() - shownAtNanos) / 1e9f;
        }

        @Override
        public float[] naturalSize() {
            return new float[]{ WIDTH, HEIGHT };
        }

        /** En jeu, seulement le temps de l'annonce. L'éditeur de HUD l'affiche toujours. */
        @Override
        public boolean hasContent() {
            return active && since() < SHOWN_SECONDS;
        }

        @Override
        public boolean skipBackground() {
            return true;
        }

        @Override
        public void draw(UiRenderer r, float x, float y, float w, float h, float s, int vw, int vh) {
            boolean live = active && since() < SHOWN_SECONDS;
            // Hors annonce, on n'est ici que par l'éditeur de HUD : un exemple.
            String head = live ? title : Lang.tr("DUEL CLASSÉ");
            String tail = live ? detail : PvpRank.name(10);
            UiColor color = live ? tone : PvpRank.color(10);
            float t = live ? since() : 1f;
            // Apparaît vite, s'efface en douceur.
            float alpha = live ? Math.min(1f, t / 0.15f) * Math.min(1f, (SHOWN_SECONDS - t) / 0.45f) : 1f;
            if (alpha <= 0.01f) return;

            float titleScale = 0.44f * s;
            float detailScale = 0.36f * s;
            String separator = "  ·  ";
            float titleW = r.textWidth(UiFont.BOLD, head, titleScale);
            float sepW = r.textWidth(UiFont.REGULAR, separator, detailScale);
            float detailW = r.textWidth(UiFont.REGULAR, tail, detailScale);
            float content = titleW + sepW + detailW;
            float pad = 16f * s;
            float pillW = Math.min(w, content + pad * 2f + 10f * s);
            float left = x + (w - pillW) / 2f;
            float right = left + pillW;
            r.drawRoundedRect(left, y, right, y + h, h / 2f, new UiColor(14, 12, 20, 215).multiplyAlpha(alpha), vw, vh);
            r.drawGradientRect2D(left, y, right, y + h, h / 2f, color.withAlpha(0.35f * alpha), color.withAlpha(0.08f * alpha),
                color.withAlpha(0.35f * alpha), color.withAlpha(0.08f * alpha), vw, vh);
            // Le point de couleur, à gauche : le compétitif a la sienne, le casual reste gris.
            float dot = 6f * s;
            float dotX = left + pad - 2f * s;
            r.drawRoundedRect(dotX, y + (h - dot) / 2f, dotX + dot, y + (h + dot) / 2f, dot / 2f, color.multiplyAlpha(alpha), vw, vh);

            float baseline = y + h / 2f - HALF_CAP * titleScale;
            float textX = dotX + dot + 8f * s;
            r.drawText(UiFont.BOLD, head, textX, baseline, color.multiplyAlpha(alpha), titleScale, vw, vh);
            float detailBaseline = y + h / 2f - HALF_CAP * detailScale;
            r.drawText(UiFont.REGULAR, separator + tail, textX + titleW, detailBaseline,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(alpha), detailScale, vw, vh);
        }

        /** Ce qu'un texte occupe au-dessus de sa ligne de base, par unité d'échelle — comme dans le panneau. */
        private static final float HALF_CAP = 11f;
    }

    /**
     * La cérémonie d'un nouveau rang entier (Bois → Pierre, Or → Diamant…) ou
     * du rang dévoilé à la fin du placement — pas d'une simple division. Le
     * panneau de fin de duel quitte sa place et vient au centre de l'écran,
     * qui s'assombrit, puis :
     *
     * <ol>
     *   <li><b>suspense</b> (2,2 s) : le coffre du jeu tremble de plus en
     *       plus fort, son couvercle se soulève par à-coups sur des battements
     *       de plus en plus serrés, et une lueur s'échappe de la fente ;</li>
     *   <li><b>éclat</b> : le couvercle s'ouvre d'un coup, la lumière
     *       jaillit — rayons, flash, onde et éclats dans la couleur du nouveau
     *       rang — et la tuile du rang sort du coffre en tournant ;</li>
     *   <li><b>félicitations</b> : « FÉLICITATIONS ! », « TU PASSES EN OR »
     *       dans sa couleur, et la part des joueurs devancés ;</li>
     *   <li>tout s'efface.</li>
     * </ol>
     *
     * Même scène pour un rang dévoilé et pour une montée, seul le texte
     * change. Le coffre et la tuile sont de vrais modèles 3D
     * ({@link RankChest}, {@link RankTiles}), éclairés par une lumière posée
     * au fond du coffre. Maquette : l'artifact « Cérémonie des rangs ».
     *
     * Sans état : tout se déduit du temps écoulé {@code t} et des points du
     * duel ({@link NativeCombat#RANKED_LEN} nombres). Repère du moteur :
     * l'origine est en bas à gauche, y monte.
     */
    private static final class Ceremony {

        /** Durée totale, effacement compris. */
        static final float LENGTH = 9.0f;
        private static final float TRAVEL = 0.6f;
        private static final float SUSPENSE = 2.2f;
        /** L'éclat : après le voyage et le suspense. */
        private static final float BURST = TRAVEL + SUSPENSE;
        private static final float FADE = 0.6f;
        private static final int SPARKS = 34;
        private static final int RAYS = 11;
        /** Pour les sons du panneau : le suspense commence après le voyage, l'éclat après le suspense. */
        static final float TRAVEL_SECONDS = TRAVEL;
        static final float BURST_SECONDS = BURST;
        /** La lueur du suspense : le rang n'est pas encore connu, elle est d'un blanc chaud. */
        private static final UiColor WARM = new UiColor(255, 214, 140, 255);
        /** Texture des rayons : du blanc, teinté par sommet. */
        private static final java.awt.image.BufferedImage WHITE = white();

        private Ceremony() {
        }

        /** Le saut de couvercle en cours, {@code u} secondes après le début du suspense : un par battement, de plus en plus serrés. */
        static int beat(float u) {
            return (int) Math.floor(1.4 * u + 2.2 * u * u);
        }

        /** L'ouverture du couvercle, en degrés, au temps {@code t}. */
        private static float lid(float t) {
            if (t < TRAVEL) return 0f;
            if (t < BURST) {
                float u = t - TRAVEL, p = clamp01(u / SUSPENSE);
                float wave = (float) Math.sin(2 * Math.PI * (1.4 * u + 2.2 * u * u));
                return Math.max(0f, wave) * (4f + 14f * p);
            }
            float b = t - BURST;
            return 112f * easeOut(b / 0.35f) - 8f * (float) Math.sin(b * 9f) * clamp01(1f - b / 0.8f);
        }

        private static java.awt.image.BufferedImage white() {
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, 0xFFFFFFFF);
            return img;
        }

        private static float clamp01(float v) {
            return Math.max(0f, Math.min(1f, v));
        }

        private static float easeOut(float u) {
            float v = 1f - clamp01(u);
            return 1f - v * v * v;
        }

        /** Dépasse un peu sa cible avant d'y revenir : l'insigne qui surgit. */
        private static float easeOutBack(float u) {
            float v = clamp01(u) - 1f;
            float c = 2.2f;
            return 1f + (c + 1f) * v * v * v + c * v * v;
        }

        static void draw(UiRenderer r, double[] v, boolean reveal, float t, float x, float y, float w, float h, float s,
                         int vw, int vh) {
            int before = (int) v[NativeCombat.RANKED_BEFORE_DIVISION];
            int after = (int) v[NativeCombat.RANKED_AFTER_DIVISION];
            int newTier = PvpRank.tier(after);
            UiColor oldTone = reveal ? UiTheme.ACCENT : PvpRank.color(before);
            UiColor newTone = PvpRank.color(after);
            float fade = clamp01((LENGTH - t) / FADE);
            if (fade <= 0f) return;

            // L'écran s'assombrit, plus encore pendant le suspense.
            float dim = (0.45f + 0.15f * clamp01((t - TRAVEL) / SUSPENSE) * (t < BURST ? 1f : 0.6f)) * easeOut(t / TRAVEL) * fade;
            r.drawRoundedRect(0f, 0f, vw, vh, 0f, new UiColor(4, 3, 8, 255).withAlpha(dim), vw, vh);

            // Le panneau voyage de sa place au centre, en grandissant.
            float k = s * 1.35f;
            float pw = 420f * k, ph = 330f * k;
            float cx = vw / 2f, cy = vh / 2f;
            float travel = easeOut(t / TRAVEL);
            float left = x + (cx - pw / 2f - x) * travel;
            float bottom = y + (cy - ph / 2f - y) * travel;
            float right = left + w + (pw - w) * travel;
            float top = bottom + h + (ph - h) * travel;
            UiColor frame = t < BURST ? oldTone : newTone;
            r.drawShadow(left, bottom, right, top, 18f * k, 30f * k, 0f, new UiColor(0, 0, 0, 160).withAlpha(0.6f * fade), vw, vh);
            r.drawRoundedRect(left - 1.5f, bottom - 1.5f, right + 1.5f, top + 1.5f, 14f * k + 1.5f, frame.withAlpha(0.7f * fade), vw, vh);
            r.drawRoundedRect(left, bottom, right, top, 14f * k, new UiColor(16, 14, 24, 255).withAlpha(0.96f * fade), vw, vh);
            r.drawGradientRect2D(left, bottom, right, top, 14f * k, frame.withAlpha(0.05f * fade), frame.withAlpha(0.22f * fade),
                frame.withAlpha(0.05f * fade), frame.withAlpha(0.22f * fade), vw, vh);
            if (t < TRAVEL * 0.5f) return;

            boolean burst = t >= BURST;
            float b = t - BURST;
            float u = t - TRAVEL;
            float panelCx = (left + right) / 2f;
            float grow = 0.45f + 0.55f * travel;
            float open = lid(t);
            UiColor light = burst ? newTone : WARM;

            // ── Le coffre ── il tremble de plus en plus pendant le suspense, puis
            // s'écarte un peu vers le bas pour laisser la place à la tuile.
            float shakeX = 0f, shakeY = 0f;
            if (!burst && u >= 0f) {
                float p = clamp01(u / SUSPENSE);
                float amplitude = 5f * k * p * p;
                shakeX = amplitude * (float) Math.sin(u * 61.0);
                shakeY = amplitude * (float) Math.cos(u * 47.0);
            }
            float sink = burst ? 0.09f * ph * easeOut(b / 0.6f) : 0f;
            float chestCy = bottom + (top - bottom) * 0.40f - sink;
            float[] center = RankChest.center();
            UiView3D view = new UiView3D()
                .center(panelCx + shakeX, chestCy + shakeY)
                .scale(4.6f * k * grow)
                .origin(center[0], center[1], center[2])
                .rotation((float) Math.toRadians(-28.0 + 6.0 * Math.sin(t * 0.8)), (float) Math.toRadians(24.0), 0f)
                .eyeDistance(70f);
            float[] slit = RankChest.slitPoint(view, open);

            // La lumière au fond du coffre : elle suit le couvercle, puis éclate.
            float inner = burst
                ? 2.6f * clamp01(b / 0.15f) * (0.7f + 0.3f * clamp01(1f - (b - 0.4f) / 1.5f))
                : 1.6f * clamp01(open / 12f);

            // Les rayons, derrière le coffre.
            float rays = burst
                ? 0.9f * clamp01(b / 0.2f) * (0.75f + 0.25f * (float) Math.sin(b * 4f)) * fade
                : 0.5f * clamp01(open / 18f) * fade;
            if (rays > 0.01f) {
                drawRays(r, slit[0], slit[1], ph * 0.62f * grow, 3f * k, t, light, rays, vw, vh);
            }

            UiLighting lighting = UiLighting.gui();
            if (inner > 0.01f) lighting.point(8f, 11.5f, 8f, light.r, light.g, light.b, inner, 20f);
            float chestAlpha = fade * (burst ? 1f - 0.25f * clamp01((b - 1f) / 1f) : 1f);
            if (RankChest.available()) {
                RankChest.draw(r, view, open, lighting, chestAlpha, vw, vh);
            } else if (!reveal) {
                // Sans le coffre du jeu (modèle illisible, déjà journalisé) : l'ancien insigne tremble à sa place.
                float size = 70f * k * grow;
                PvpRank.badge(r, before, panelCx - size / 2f + shakeX, chestCy - size / 2f + shakeY, size,
                    clamp01(open / 12f), chestAlpha, vw, vh);
            }

            // La lueur qui s'échappe de la fente pendant le suspense.
            if (!burst && open > 0.5f) {
                float gw = 26f * k * grow, gh = 5f * k * grow;
                r.drawGlow(slit[0] - gw, slit[1] - gh, slit[0] + gw, slit[1] + gh, 12f * k, clamp01(open / 10f),
                    light.multiplyAlpha(fade), vw, vh);
            }

            if (!burst) {
                // ── Le suspense ──
                String hint = reveal ? Lang.tr("Ton rang est…") : Lang.tr("Quelque chose se prépare…");
                centered(r, UiFont.BOLD, hint, cx, bottom + 44f * k, pw - 40f * k,
                    UiTheme.TEXT_SECONDARY.multiplyAlpha((0.6f + 0.4f * (float) Math.abs(Math.sin(u * 3.0))) * fade), 0.42f * k, vw, vh);
                if (!reveal) {
                    centered(r, UiFont.REGULAR, PvpRank.name(before), cx, bottom + 24f * k, pw - 40f * k,
                        oldTone.multiplyAlpha(fade), 0.32f * k, vw, vh);
                }
                return;
            }

            // ── L'éclat ──
            float flash = clamp01(1f - b / 0.35f);
            if (flash > 0f) {
                r.drawRoundedRect(left, bottom, right, top, 14f * k, new UiColor(255, 255, 255, 255).withAlpha(0.55f * flash * fade), vw, vh);
            }
            if (b < 1.2f) {
                r.drawRipple(slit[0], slit[1], pw * 0.75f, clamp01(b / 1.2f), 0.85f * fade, newTone, vw, vh);
            }
            if (b < 1.6f) {
                float spread = easeOut(b / 1.1f) * pw * 0.5f;
                float sparkAlpha = clamp01(1f - b / 1.6f) * fade;
                float spark = 5f * k;
                for (int i = 0; i < SPARKS; i++) {
                    double angle = 2 * Math.PI * i / SPARKS + 0.37 * (i % 3);
                    float reach = spread * (0.6f + 0.4f * ((i * 7) % 5) / 4f);
                    float px = slit[0] + (float) Math.cos(angle) * reach;
                    float py = slit[1] + (float) Math.sin(angle) * reach * 0.75f;
                    UiColor color = i % 3 == 0 ? new UiColor(255, 255, 255, 255) : newTone;
                    r.drawRoundedRect(px - spark / 2f, py - spark / 2f, px + spark / 2f, py + spark / 2f, 1.5f * k,
                        color.withAlpha(sparkAlpha), vw, vh);
                }
            }

            // La tuile sort du coffre en tournant, monte, puis flotte.
            float rise = easeOut(b / 0.7f);
            float hover = (float) Math.sin(b * 2.4) * 3f * k * clamp01((b - 0.9f) / 0.4f);
            float tileCx = slit[0] + (panelCx - slit[0]) * rise;
            float tileCy = slit[1] + (bottom + ph * 0.68f - slit[1]) * rise + hover;
            float tileSize = 0.22f * ph * (0.15f + 0.85f * easeOutBack(b / 0.65f));
            float spin = (float) (4 * Math.PI) * (1f - easeOut(b / 0.9f));
            float halo = 0.55f + 0.45f * clamp01(1f - b / 1.5f) + 0.12f * (float) Math.sin(b * 3.0);
            r.drawGlow(tileCx - tileSize / 2f, tileCy - tileSize / 2f, tileCx + tileSize / 2f, tileCy + tileSize / 2f,
                tileSize * 0.3f, Math.min(1f, halo), newTone.multiplyAlpha(fade), vw, vh);
            UiLighting tileLight = new UiLighting().ambient(0.5f, 0.5f, 0.52f)
                .directional(-0.45f, 0.8f, 0.75f, 1f, 0.98f, 0.94f, 0.72f)
                // La lumière du coffre, par en dessous, qui s'apaise.
                .directional(0f, -1f, 0.35f, newTone.r, newTone.g, newTone.b, 0.35f + 0.9f * clamp01(1f - b / 1.5f));
            RankTiles.draw(r, newTier, tileCx, tileCy, tileSize, 0.3f + spin, 0.2f, 0f, tileLight, fade, vw, vh);
            if (after < PvpRank.CHAMPION && rise > 0.95f) {
                // La division, comme sur l'insigne.
                String division = String.valueOf(after % PvpRank.DIVISIONS_PER_TIER + 1);
                float scale = 0.55f * k;
                float dw = r.textWidth(UiFont.BOLD, division, scale);
                float dx = tileCx + tileSize * 0.42f - dw, dy = tileCy - tileSize * 0.5f;
                float divisionAlpha = clamp01((b - 0.9f) / 0.3f) * fade;
                r.drawText(UiFont.BOLD, division, dx + 1.5f * k, dy - 1.5f * k, new UiColor(63, 63, 63, 255).withAlpha(divisionAlpha), scale, vw, vh);
                r.drawText(UiFont.BOLD, division, dx, dy, new UiColor(255, 255, 255, 255).withAlpha(divisionAlpha), scale, vw, vh);
            }

            // Les félicitations.
            float words = clamp01((b - 0.5f) / 0.5f) * fade;
            if (words <= 0f) return;
            centered(r, UiFont.BOLD, Lang.tr("FÉLICITATIONS !"), cx, top - 36f * k, pw - 40f * k,
                UiTheme.TEXT_PRIMARY.multiplyAlpha(words), 0.62f * k, vw, vh);
            String tierName = Lang.tr(PvpRank.TIERS[newTier]).toUpperCase(java.util.Locale.ROOT);
            String headline = reveal ? Lang.tr("TON RANG :") + " " + PvpRank.name(after).toUpperCase(java.util.Locale.ROOT)
                : after >= PvpRank.CHAMPION ? Lang.tr("TU DEVIENS") + " " + tierName
                : Lang.tr("TU PASSES EN") + " " + tierName;
            centered(r, UiFont.BOLD, headline, cx, bottom + 46f * k, pw - 40f * k, newTone.multiplyAlpha(words), 0.6f * k, vw, vh);
            double ahead = PvpRank.percentBelow(PvpRank.axisPosition(after, v[NativeCombat.RANKED_AFTER_POINTS]));
            float detailAlpha = clamp01((b - 0.7f) / 0.5f) * fade;
            if (ahead >= 1 && detailAlpha > 0f) {
                centered(r, UiFont.REGULAR, Lang.tr("Devant") + " " + Math.round(ahead) + " % " + Lang.tr("des joueurs"), cx,
                    bottom + 24f * k, pw - 40f * k, UiTheme.TEXT_SECONDARY.multiplyAlpha(detailAlpha), 0.36f * k, vw, vh);
            }
        }

        /**
         * Les rayons qui sortent du coffre : un éventail de faisceaux vers le
         * haut, lumineux à leur pied et qui s'éteignent au bout, balancés
         * lentement. Des quads libres (la brique 3D les dessine), parce qu'aucune
         * primitive alignée sur l'écran ne sait faire un faisceau incliné.
         */
        private static void drawRays(UiRenderer r, float x, float y, float length, float baseHalf, float t,
                                     UiColor color, float alpha, int vw, int vh) {
            com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch batch =
                new com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshBatch();
            batch.reset();
            float sway = 0.07f * (float) Math.sin(t * 0.7);
            float spread = 1.9f;
            int foot = (Math.round(alpha * 0.55f * 255f) << 24) | rgb(color);
            int tip = rgb(color);
            for (int i = 0; i < RAYS; i++) {
                float angle = (float) (Math.PI / 2) + spread * (i / (float) (RAYS - 1) - 0.5f) + sway
                    + 0.05f * (float) Math.sin(t * 1.3 + i);
                float reach = length * (0.7f + 0.3f * ((i * 5) % 3) / 2f);
                float tipHalf = reach * 0.1f * (0.5f + 0.5f * ((i * 3) % 4) / 3f);
                float dx = (float) Math.cos(angle), dy = (float) Math.sin(angle);
                float nx = -dy, ny = dx;
                float x0 = x - nx * baseHalf, y0 = y - ny * baseHalf;
                float x1 = x + nx * baseHalf, y1 = y + ny * baseHalf;
                float x2 = x + dx * reach + nx * tipHalf, y2 = y + dy * reach + ny * tipHalf;
                float x3 = x + dx * reach - nx * tipHalf, y3 = y + dy * reach - ny * tipHalf;
                // Sens inverse des aiguilles d'une montre, comme le reste de la brique 3D.
                float area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
                if (area >= 0f) {
                    batch.add(x0, y0, x1, y1, x2, y2, x3, y3, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                        foot, foot, tip, tip);
                } else {
                    batch.add(x0, y0, x3, y3, x2, y2, x1, y1, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
                        foot, tip, tip, foot);
                }
            }
            r.drawTexturedQuads("ceremony-rays", WHITE, batch, vw, vh);
        }

        private static int rgb(UiColor c) {
            return (Math.round(c.r * 255f) << 16) | (Math.round(c.g * 255f) << 8) | Math.round(c.b * 255f);
        }

        /** Un texte centré, rétréci s'il dépasse {@code maxWidth}. */
        private static void centered(UiRenderer r, UiFont font, String text, float center, float y, float maxWidth,
                                     UiColor color, float scale, int vw, int vh) {
            float width = r.textWidth(font, text, scale);
            if (width > maxWidth && width > 0f) {
                scale *= maxWidth / width;
                width = maxWidth;
            }
            r.drawText(font, text, center - width / 2f, y, color, scale, vw, vh);
        }
    }
}
