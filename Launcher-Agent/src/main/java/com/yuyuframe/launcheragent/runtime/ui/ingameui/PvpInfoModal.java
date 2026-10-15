package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.combat.NativeCombat;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.module.gameplay.PvpScoreModule;

import java.util.ArrayList;
import java.util.List;

/**
 * « Comment ça marche ? » : la fenêtre qui explique la note, un sujet à la
 * fois — la liste des sujets à gauche, le texte à droite.
 *
 * <p>Ouverte depuis les deux pages du Score PvP. Depuis la page d'un duel,
 * elle reçoit le résumé de ce duel et chaque famille y dit en plus ce qu'elle
 * valait ce jour-là ; un clic sur une famille de la page ouvre directement son
 * sujet ({@link #FAMILY_TOPICS}).
 *
 * <p><b>Chaque phrase décrit ce que fait le code</b> ({@code content-core/src/combat/}) :
 * un seuil, une référence ou une règle qui change s'y reporte. Pas de tiret
 * long ni de symbole mathématique : la police ne les a pas.
 */
final class PvpInfoModal {

    /** Un sujet : son titre dans la liste, et ses paragraphes ; un paragraphe qui commence par « # » est un intertitre. */
    private static final class Topic {
        final String title;
        final List<String> paragraphs = new ArrayList<String>();

        Topic(String title, String... paragraphs) {
            this.title = title;
            for (String p : paragraphs) this.paragraphs.add(p);
        }
    }

    /** Les intertitres : l'accent du thème, éclairci pour se lire sur le verre sombre. */
    private static final UiColor HEADING = new UiColor(168, 152, 255, 255);

    static final int OVERVIEW = 0;
    /** Le sujet de chacune de mes cinq familles, dans l'ordre de {@link NativeCombat#REPORT_FAMILIES} ; puis celui du niveau adverse. */
    static final int[] FAMILY_TOPICS = { 2, 3, 4, 5, 6, 7 };

    private final List<Topic> topics;
    private final List<UiWidget> widgets = new ArrayList<UiWidget>();
    /** Position de chaque widget fenêtre posée : la fenêtre monte en s'ouvrant, ils montent avec elle. */
    private final List<Float> widgetBaseY = new ArrayList<Float>();
    private int selected;
    private float px, py, pw, ph, listRight;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;

    /** Ouverture, fermeture (0 tant qu'elle est ouverte), et dernier changement de sujet : les trois animations de la fenêtre. */
    private final long openedAt = System.nanoTime();
    private long closingAt;
    private long topicAt = openedAt;
    private static final float OPEN_SECS = 0.35f;
    private static final float CLOSE_SECS = 0.2f;

    /**
     * @param report le résumé du duel dont on vient, ou {@code null} depuis l'historique
     * @param topic  le sujet ouvert d'abord
     */
    PvpInfoModal(double[] report, int topic) {
        this.topics = topics(report);
        this.selected = Math.max(0, Math.min(topics.size() - 1, topic));
    }

    /** Les widgets de la fenêtre : tant qu'elle est là, eux seuls reçoivent les clics. */
    List<UiWidget> widgets() {
        return widgets;
    }

    /** Lance la fermeture : la fenêtre s'efface, puis {@link #closed} le dit à l'écran, qui la retire. */
    void close() {
        if (closingAt == 0L) closingAt = System.nanoTime();
    }

    boolean closed() {
        return closingAt != 0L && (System.nanoTime() - closingAt) / 1e9f >= CLOSE_SECS;
    }

    /** 0 fermée, 1 ouverte : monte à l'ouverture, redescend à la fermeture. */
    private float presence() {
        if (closingAt != 0L) return 1f - PvpScoreUi.ease(closingAt, 0f, CLOSE_SECS);
        return PvpScoreUi.ease(openedAt, 0f, OPEN_SECS);
    }

    private void select(int index) {
        if (index == selected) return;
        selected = index;
        topicAt = System.nanoTime();
    }

    private void layout(int vw, int vh) {
        widgets.clear();
        pw = Math.min(UiTheme.scaled(1040f), vw - UiTheme.scaled(80f));
        ph = Math.min(UiTheme.scaled(660f), vh - UiTheme.scaled(80f));
        px = (vw - pw) / 2f;
        py = (vh - ph) / 2f;

        float pad = UiTheme.scaled(20f);
        float listW = UiTheme.scaled(250f);
        listRight = px + pad + listW;
        float closeH = UiTheme.scaled(38f);
        float top = py + ph - pad - UiTheme.scaled(36f);
        float rowGap = UiTheme.scaled(4f);
        float rowH = Math.min(UiTheme.scaled(38f), (top - (py + pad + closeH + pad)) / topics.size() - rowGap);
        for (int i = 0; i < topics.size(); i++) {
            float y = top - (i + 1) * (rowH + rowGap);
            final int index = i;
            widgets.add(new TopicItem(px + pad, y, listW, rowH, index));
        }
        float closeW = UiTheme.scaled(140f);
        widgets.add(new CloseButton(px + pw - pad - closeW, py + pad, closeW, closeH));
        widgetBaseY.clear();
        for (UiWidget w : widgets) widgetBaseY.add(w.y);
    }

    /**
     * La fenêtre : le voile s'assombrit, le panneau monte en fondu ; à la
     * fermeture, l'inverse. Tout ce qu'elle contient suit son opacité
     * ({@link PvpScoreUi#alpha}).
     */
    void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
        if (vw != lastW || vh != lastH || UiTheme.UI_SCALE != lastScale) {
            layout(vw, vh);
            lastW = vw; lastH = vh; lastScale = UiTheme.UI_SCALE;
        }
        float p = presence();
        float dy = -(1f - p) * UiTheme.scaled(36f);
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).y = widgetBaseY.get(i) + dy;

        r.drawRoundedRect(0f, 0f, vw, vh, 0f, UiTheme.GLASS_SCRIM.multiplyAlpha(p), vw, vh);
        float y1 = py + dy, y2 = py + ph + dy;
        // L'opacité du repli pilote aussi celle du verre (voir UiRenderer.drawGlassPanel).
        r.drawGlassPanel(px, y1, px + pw, y2, UiTheme.scaled(14f),
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, UiTheme.PANEL_BG.multiplyAlpha(p),
            UiTheme.GLASS_BORDER.multiplyAlpha(p), Math.max(1f, UiTheme.scaled(1f)), vw, vh);

        PvpScoreUi.alpha = p;
        try {
            float pad = UiTheme.scaled(20f);
            PvpScoreUi.left(r, UiFont.BOLD, Lang.tr("COMMENT ÇA MARCHE"), px + pad, y2 - pad - UiTheme.scaled(18f),
                listRight - px - pad, UiTheme.TEXT_MUTED, UiTheme.scaled(0.4f), vw, vh);
            // Un trait entre la liste des sujets et le texte.
            PvpScoreUi.rect(r, listRight + pad / 2f, y1 + pad, listRight + pad / 2f + Math.max(1f, UiTheme.scaled(1f)), y2 - pad,
                0f, UiTheme.GLASS_BORDER, vw, vh);
            for (UiWidget w : widgets) {
                w.draw(r, mouseX, mouseY, vw, vh);
            }
            // Le texte d'un sujet entre en glissant un peu, à chaque changement.
            float t = PvpScoreUi.ease(topicAt, 0f, 0.3f);
            PvpScoreUi.alpha = p * t;
            float slide = (1f - t) * UiTheme.scaled(18f);
            drawTopic(r, topics.get(selected), listRight + pad * 1.6f + slide, px + pw - pad * 1.4f + slide,
                y2 - pad, y1 + pad + UiTheme.scaled(48f), vw, vh);
        } finally {
            PvpScoreUi.alpha = 1f;
        }
    }

    /** « Fermer » : un bouton à nous, pour qu'il suive le fondu de la fenêtre. */
    private final class CloseButton extends UiWidget {
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);

        CloseButton(float x, float y, float w, float h) {
            super(x, y, w, h);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            float t = hover.get();
            float radius = UiTheme.scaled(8f);
            PvpScoreUi.rect(r, x - 1f, y - 1f, x + w + 1f, y + h + 1f, radius + 1f, UiColor.lerp(UiTheme.GLASS_BORDER, UiTheme.ACCENT, t), vw, vh);
            PvpScoreUi.rect(r, x, y, x + w, y + h, radius, UiColor.lerp(UiTheme.CARD_BG, UiTheme.ACCENT.withAlpha(0.5f), t), vw, vh);
            float scale = UiTheme.scaled(0.46f);
            PvpScoreUi.centered(r, UiFont.BOLD, Lang.tr("Fermer"), x + w / 2f, PvpScoreUi.middle(y + h / 2f, scale), w,
                UiTheme.TEXT_PRIMARY, scale, vw, vh);
        }

        @Override
        public void onClick() {
            close();
        }
    }

    /**
     * Le texte d'un sujet. Il doit tenir sans défiler (le découpage aux bords
     * d'une zone ne marche pas sur toutes les versions du jeu) : s'il dépasse,
     * il est redessiné un peu plus petit jusqu'à tenir.
     */
    private void drawTopic(UiRenderer r, Topic topic, float left, float right, float top, float bottom, int vw, int vh) {
        float width = right - left;
        float body = UiTheme.scaled(0.5f);
        List<Object[]> lines = null;
        float lineH = 0f;
        for (int attempt = 0; attempt < 12; attempt++) {
            lineH = body * 46f;
            lines = new ArrayList<Object[]>();
            float height = body * 1.6f * 52f;
            for (String paragraph : topic.paragraphs) {
                boolean heading = paragraph.startsWith("# ");
                String text = heading ? paragraph.substring(2) : paragraph;
                UiFont font = heading ? UiFont.BOLD : UiFont.REGULAR;
                float scale = heading ? body * 1.08f : body;
                height += heading ? lineH * 0.7f : lineH * 0.45f;
                for (String line : PvpScoreUi.wrap(r, font, text, scale, width)) {
                    lines.add(new Object[]{ line, font, scale, heading });
                    height += lineH;
                }
                lines.add(null);
            }
            if (height <= top - bottom) break;
            body *= 0.92f;
        }

        float titleScale = body * 1.6f;
        float y = top - titleScale * 32f;
        PvpScoreUi.left(r, UiFont.BOLD, Lang.tr(topic.title), left, y, width, UiTheme.TEXT_PRIMARY, titleScale, vw, vh);
        y -= titleScale * 22f;
        boolean paragraphStart = true;
        for (Object[] line : lines) {
            if (line == null) {
                paragraphStart = true;
                continue;
            }
            boolean heading = (Boolean) line[3];
            if (paragraphStart) {
                y -= heading ? lineH * 0.7f : lineH * 0.45f;
                paragraphStart = false;
            }
            y -= lineH;
            PvpScoreUi.text(r, (UiFont) line[1], (String) line[0], left, y + lineH * 0.25f,
                heading ? HEADING : UiTheme.TEXT_SECONDARY, (Float) line[2], vw, vh);
        }
    }

    /**
     * Une ligne de la liste des sujets. Celle qui est ouverte est surlignée :
     * le surlignage et la barre d'accent s'allument en fondu, et le titre se
     * décale un peu vers la droite.
     */
    private final class TopicItem extends UiWidget {
        private final int index;
        private final UiAnimatedFloat hover = new UiAnimatedFloat(0f, 16f);
        private final UiAnimatedFloat on;

        TopicItem(float x, float y, float w, float h, int index) {
            super(x, y, w, h);
            this.index = index;
            this.on = new UiAnimatedFloat(index == selected ? 1f : 0f, 14f);
        }

        @Override
        public void draw(UiRenderer r, double mouseX, double mouseY, int vw, int vh) {
            hover.setTarget(contains(mouseX, mouseY) ? 1f : 0f);
            on.setTarget(index == selected ? 1f : 0f);
            float s = on.get();
            float hv = hover.get() * (1f - s);
            float radius = UiTheme.scaled(8f);
            if (hv > 0.01f) {
                PvpScoreUi.rect(r, x, y, x + w, y + h, radius, new UiColor(255, 255, 255, 18).multiplyAlpha(hv), vw, vh);
            }
            if (s > 0.01f) {
                PvpScoreUi.rect(r, x, y, x + w, y + h, radius, UiTheme.ACCENT.withAlpha(0.28f * s), vw, vh);
                float bar = h * 0.3f * (1f - s);
                PvpScoreUi.rect(r, x, y + h * 0.2f + bar, x + UiTheme.scaled(3f), y + h * 0.8f - bar, UiTheme.scaled(1.5f),
                    UiTheme.ACCENT.multiplyAlpha(s), vw, vh);
            }
            float scale = UiTheme.scaled(0.44f);
            boolean selectedNow = index == selected;
            PvpScoreUi.left(r, selectedNow ? UiFont.BOLD : UiFont.REGULAR, Lang.tr(topics.get(index).title),
                x + UiTheme.scaled(14f) + s * UiTheme.scaled(4f), PvpScoreUi.middle(y + h / 2f, scale), w - UiTheme.scaled(24f),
                UiColor.lerp(UiTheme.TEXT_SECONDARY, UiTheme.TEXT_PRIMARY, Math.max(s, hover.get())), scale, vw, vh);
        }

        @Override
        public void onClick() {
            select(index);
        }
    }

    // ── Les sujets ───────────────────────────────────────────────────────────

    private static List<Topic> topics(double[] report) {
        List<Topic> list = new ArrayList<Topic>();
        list.add(new Topic("Vue d'ensemble",
            "La note résume un duel à la masse sur 100. 50, c'est le joueur de référence : au-dessus, vous avez mieux joué que lui ; en dessous, moins bien.",
            "Tout est calculé sur votre PC, à partir de ce que votre jeu voit pendant le duel. Rien n'est envoyé à un serveur et il n'y a pas de classement.",
            "La note est faite de six familles qui pèsent autant les unes que les autres : vos cinq familles (conversion, qualité du smash, défense, contrôle vertical et horizontal, ressources) et la force de votre adversaire, telle que vos duels précédents contre lui la font connaître.",
            "S'y ajoute un bonus, le bug use, pour les techniques les plus dures : il ne fait jamais baisser la note et s'affiche à part, par exemple 62 + 4 bug use.",
            "# Les couleurs",
            "Vert à partir de 65 : un bon duel. Ambre de 45 à 64 : dans la moyenne. Rouge en dessous de 45 : un duel difficile. Gris : pas de note. Les barres bleues sont celles de l'adversaire.",
            "# Quand un duel est reconnu",
            "Un duel commence au premier échange de coups avec un vrai joueur. Il se termine quand l'un des deux perd son équipement à la fin de la manche. Un adversaire qui quitte le duel compte comme un match nul : il ne s'est pas vraiment battu.",
            "Le mode se reconnaît à l'arme en main : dès qu'une masse apparaît dans la main de l'un des deux, c'est un duel à la masse. Les autres (à l'épée par exemple) sont enregistrés, mais pas notés."));
        list.add(new Topic("Opportunités",
            "Une opportunité, c'est un moment où un smash est possible : vous tombez d'au moins 1,5 bloc, vous êtes en train de descendre, vous êtes au-dessus de l'adversaire et à moins de 3,5 blocs de lui à l'horizontale.",
            "Elle est convertie si un coup de masse touche pendant ce moment. Les mêmes règles valent pour l'adversaire : ses opportunités sont celles que vous lui laissez.",
            "# Ce qui n'en est pas une",
            "Un passage de moins de 3 ticks au-dessus de lui, sans coup : il ne fait que traverser la zone. Et une chute face à son bouclier levé : le coup ne pouvait pas passer, ne pas frapper n'est pas un raté. Dans son dos, en revanche, c'est une opportunité, et une difficile.",
            "# Le bonus du smash",
            "Le smash ajoute des dégâts selon la hauteur de chute : 4 par bloc pour les 3 premiers, 2 par bloc jusqu'à 8, puis 1 par bloc au-delà, plus l'enchantement Densité.",
            "Les dégâts de smash d'un duel comparent la somme des bonus réellement portés à celle qu'auraient donnée toutes les opportunités. Les grosses ouvertures (8 blocs de chute et plus) et les petites sont comptées à part, hors note."));
        list.add(family(report, 0, "Conversion",
            "Exploitez-vous vos ouvertures, compte tenu de leur difficulté et de leur valeur ? Chaque opportunité a une chance de réussite, celle d'un joueur moyen sur la même situation, et un poids, son bonus de smash possible.",
            "La chance baisse quand vous êtes loin de lui à l'ouverture, quand il bouge vite, quand la fenêtre est courte, quand la chute est grande (on arrive vite, il a le temps de se replacer) et quand il faut passer dans le dos de son bouclier.",
            "La famille compare ce que vous avez converti à ce qu'un joueur moyen aurait converti sur ces mêmes opportunités : 50 si vous faites pareil. Réussir une ouverture difficile rapporte, rater une ouverture facile coûte, et une grosse chute pèse plus qu'une petite.",
            "Les chances sont posées à la main en attendant d'être apprises sur vos duels.",
            "Pour la monter : ne laissez pas passer les ouvertures faciles, collé à lui et sur une chute courte."));
        list.add(family(report, 1, "Qualité du smash",
            "Ce que vous tirez de vos gros coups. Pour chaque smash réussi : les dégâts portés divisés par les dégâts possibles. Possibles : masse chargée à fond, coup critique, et la chute qu'aurait eue le coup en attendant que vos pieds arrivent à la hauteur de sa tête.",
            "Deux erreurs y coûtent : frapper trop tôt, avec moins de chute que vous pouviez en prendre, et frapper mal chargé (moins de dégâts de base, pas de critique). La charge est celle que le jeu compte au coup : un attribute swap qui garde la charge de l'arme d'avant compte comme un coup bien chargé.",
            "La charge de la lance compte aussi : les dégâts à la vitesse de l'impact divisés par ceux que permettait la plus grande vitesse de l'approche.",
            "La hauteur elle-même n'y compte pas : elle est déjà dans la conversion et le contrôle. Les coups ordinaires ne rapportent rien. La valeur de référence est 75 %.",
            "Pour la monter : attendez le bas de la chute pour frapper, avec la barre d'attaque pleine et sans sprinter."));
        list.add(family(report, 2, "Défense",
            "Empêchez-vous l'adversaire de vous mettre ses gros coups ? Six mesures : le bouclier (30 %), les ouvertures laissées (25 %), le temps vulnérable (15 %), l'esquive (15 %), atterrir proprement (10 %) et la reprise après un coup (5 %).",
            "Bouclier : chaque smash arrêté rapporte selon ce qu'il valait, dix fois plus si vous étiez à 4 blocs ou plus au-dessus du sol. Une garde cassée par une hache coûte autant qu'un smash de 8 blocs arrêté. Rester au sol bouclier levé sous lui coûte un peu.",
            "Ouvertures laissées : l'envers exact de la conversion, sur ses opportunités. Temps vulnérable : au sol pendant qu'il est au-dessus de vous et à portée. Esquive : sortir de sa zone pendant ses descentes. Atterrir proprement : les dégâts de chute après un de vos smashs ratés. Reprise : vous dégager après un coup reçu.",
            "Les dégâts évités, affichés à part, résument ce qu'il pouvait vous mettre et qu'il n'a pas mis. Ils ne comptent pas dans la note.",
            "Pour la monter : levez le bouclier quand il tombe sur vous en l'air, pas au sol en permanence, et ne restez pas sous lui."));
        list.add(family(report, 3, "Contrôle vertical et horizontal",
            "Vous déplacez-vous pour mettre le coup ? On juge le déplacement obtenu, pas la technique : un combo qui ne vous amène nulle part ne rapporte rien.",
            "Suivre pendant la chute (35 %) : vos descentes qui arrivent à portée, la distance rattrapée quand il fuit, et les gros coups qui passent du premier coup. Prendre la hauteur (20 %) : vitesse de montée, temps pour repasser au-dessus de lui, hauteur gagnée par charge de vent ou par combo perle et charge de vent, et la domination de la hauteur.",
            "Se placer au sommet (15 %) : être juste au-dessus de lui quand vous commencez à redescendre. Se déplacer à l'horizontale (15 %) : vitesse de rapprochement face à sa fuite, élans de Lunge qui vous mettent à portée, vols en élytre qui finissent en opportunité. Enchaîner (15 %) : un smash suivi d'une nouvelle opportunité sans toucher le sol, et le temps pour reconstruire après chaque engagement.",
            "Une armure en netherite ou la protection contre les explosions font monter moins haut : la hauteur attendue en tient compte. La valeur de référence est 50 %.",
            "Pour la monter : suivez-le pendant votre chute pour que le coup passe du premier coup."));
        list.add(family(report, 4, "Ressources",
            "Utilisez-vous vos objets au bon moment, pour une bonne raison ? Charges de vent et perles (30 %), barre d'inventaire (20 %), nourriture et vie (20 %), totems (15 %), potions (10 %), fusées d'élytre (5 %). Une partie absente du duel ne compte pas.",
            "Chaque charge de vent ou perle doit avoir une raison : monter pour attaquer, casser son smash, fuir quand vous êtes bas ou sous lui, le toucher, annuler une chute qui allait faire mal, ou une perle qui vous place. Sans raison, elle est gâchée.",
            "Barre d'inventaire : le délai pour avoir le bon objet en main quand il sert (la masse à l'ouverture, la charge de vent après l'atterrissage, la pomme quand vous passez sous 4 cœurs). Pomme d'or : sous 4 cœurs ou juste après un totem. Potions : force et vitesse seulement. Totems : le délai pour en remettre un.",
            "La valeur de référence est 55 %.",
            "Pour la monter : une charge de vent sert à quelque chose, pas à attendre."));

        Topic opponent = new Topic("Niveau adverse",
            "# Pendant le duel",
            "Le niveau de votre adversaire, tel qu'on peut le lire de ce qu'il a fait contre vous : ses quatre familles mesurables (conversion, qualité du smash, défense, contrôle vertical et horizontal), jugées avec les mêmes références que les vôtres, puis combinées comme une note. C'est ce que montre le face-à-face.",
            "Il n'entre pas dans la note de ce duel. On ne le voit jouer que contre vous : sa conversion est l'envers de votre défense, sa défense l'envers de votre conversion. Ce niveau est donc en grande partie le miroir de votre jeu ; le compter reviendrait à vous enlever une partie de ce que vous avez bien fait.",
            "# Avant le duel",
            "Ce qui compte dans la note, c'est sa force connue avant le duel : la moyenne de son niveau dans vos duels précédents contre lui, reconnu à son compte même s'il change de pseudo. Elle est tirée vers 50 tant que vous l'avez peu affronté : un duel passé compte pour un tiers, deux pour la moitié, huit pour les quatre cinquièmes.",
            "Elle entre dans votre note comme une sixième famille : bien jouer contre un adversaire connu pour être fort vaut plus, contre un adversaire connu pour être faible vaut moins. Contre un inconnu, elle vaut 50 et ne change rien.",
            "# Ce qu'il faut savoir",
            "Ses ressources ne sont pas mesurées : ses objets ne se voient pas d'ici. Ce niveau ne vaut que sur votre PC et face à vous : ce n'est pas un classement.");
        if (report != null) {
            opponent.paragraphs.add("# Dans ce duel");
            int met = (int) report[NativeCombat.REPORT_FOE_KNOWN_DUELS];
            opponent.paragraphs.add(met > 0
                ? Lang.tr("Force connue avant le duel :") + " " + Math.round(report[NativeCombat.REPORT_FOE_KNOWN]) + ", " + Lang.tr("sur") + " " + met + " "
                    + Lang.tr(met > 1 ? "duels passés contre lui." : "duel passé contre lui.")
                : Lang.tr("Premier duel contre lui : sa force connue vaut 50 et ne change pas la note."));
            StringBuilder detail = new StringBuilder(Lang.tr("Niveau pendant le duel") + " " + Math.round(report[NativeCombat.REPORT_FAMILIES + 5]) + " : ");
            for (int i = 0; i < NativeCombat.FOE_FAMILY_COUNT; i++) {
                if (i > 0) detail.append(", ");
                detail.append(Lang.tr(PvpScoreModule.FAMILIES[i]).toLowerCase(java.util.Locale.ROOT)).append(" ")
                    .append(Math.round(report[NativeCombat.REPORT_FOE_FAMILIES + i]));
            }
            detail.append(". ").append(Lang.tr("Votre jeu seul, sans son niveau :")).append(" ").append(Math.round(report[NativeCombat.REPORT_OWN])).append(".");
            opponent.paragraphs.add(detail.toString());
        }
        list.add(opponent);

        Topic bugUse = new Topic("Bug use",
            "Un bonus pour les techniques les plus dures du PvP à la masse. Il ne fait jamais baisser la note : il s'y ajoute, jusqu'à 10 points, et la note reste plafonnée à 100. Une tentative ratée ne coûte rien.",
            "# Les techniques",
            "La famille de l'attribute swap, où l'on change d'objet dans le tick du coup : cooldown swap (1 point), lunge swap (1), breach swap (2), density swap (2), reach swap (2). Et les techniques au tick près : elytra swap (2), deux totems coup sur coup (3), stun slam (4), instant pearl catch (4).",
            "Une technique ne compte que réussie : le coup confirmé par le serveur, ou l'effet obtenu. Un breach ou density swap qui porte un smash compte double, un stun slam vaut plus selon le smash qui passe, un instant pearl catch plus selon la hauteur gagnée quand il mène à une opportunité.",
            "# L'adversaire",
            "Ce qu'on voit de ses techniques est affiché, jamais compté : ni dans sa force, ni dans votre note.",
            "Les points sont posés au jugé et seront ajustés avec plus de duels.");
        if (report != null) {
            bugUse.paragraphs.add("# Dans ce duel");
            bugUse.paragraphs.add(Lang.tr("Votre bonus :") + " +" + Math.round(report[NativeCombat.REPORT_BUG_USE]) + ". "
                + Lang.tr("Ses techniques :") + " " + Math.round(report[NativeCombat.REPORT_FOE_BUG_USE]) + " " + Lang.tr("points, non comptés."));
        }
        list.add(bugUse);

        list.add(new Topic("Du chiffre à la note",
            "# 1. Lissage",
            "Une famille mesurée sur peu d'événements est tirée vers la référence : on compte la référence comme 5 événements de plus. Deux smashs réussis sur deux ne valent donc pas 100 %.",
            "# 2. Écart",
            "La valeur lissée est comparée à la référence, en nombre d'écarts habituels entre joueurs. Un écart de 0, c'est exactement la référence.",
            "# 3. Combinaison",
            "Les six écarts sont moyennés, puis élargis de 1,3 fois : une moyenne est plus resserrée qu'une famille seule.",
            "# 4. Note",
            "L'écart devient une note par la loi normale : 50 au niveau de la référence, environ 84 un écart au-dessus, environ 16 un écart en dessous. Une note de 90, ou de 10, est donc rare. Chaque barre de famille suit la même échelle.",
            "# 5. Bonus",
            "Le bonus bug use s'ajoute ensuite à la note, sans jamais la faire baisser."));
        list.add(new Topic("Confiance et seuils",
            "La confiance dit sur combien d'opportunités la note repose : n / (n + 5), où n compte les opportunités des deux joueurs. 5 opportunités donnent 50 %, 20 en donnent 80 %.",
            "Un duel n'est pas noté s'il a duré moins de 20 secondes au contact, s'il compte moins de 3 opportunités en tout, ou s'il n'est pas à la masse. Il reste enregistré, mais l'historique ne montre que les duels notés.",
            "La note moyenne de l'historique ne compte que les duels notés."));
        list.add(new Topic("Limites",
            "La note n'est pas encore calibrée : les références ont été posées à la main à partir de quelques duels. Elles seront ajustées avec plus de matchs, et les notes pourront bouger.",
            "Elle est pensée pour la masse en 1 contre 1. Un duel à l'épée est reconnu à l'arme en main et n'est pas noté.",
            "Le ping est affiché, jamais corrigé. Un ping élevé peut faire baisser la conversion.",
            "La note ne regarde que ce que votre jeu voit : positions, chutes, coups confirmés, sons du bouclier, objets en main. Elle ne sait rien de ce qui se passe hors de vue.",
            "Les duels enregistrés avant l'arrivée de ces mesures n'ont ni les coups arrêtés au bouclier ni l'objet en main au moment du coup : les parties qui en dépendent (bouclier, charge, barre d'inventaire, bug use) n'y comptent pas.",
            "Les journaux de vos duels sont dans le dossier de l'instance, sous yuyuframe/matches."));
        return list;
    }

    /** Le sujet d'une de mes familles ; depuis un duel, ce qu'elle y valait. */
    private static Topic family(double[] report, int index, String title, String... paragraphs) {
        Topic topic = new Topic(title, paragraphs);
        if (report != null) {
            long value = Math.round(report[NativeCombat.REPORT_FAMILIES + index]);
            long events = Math.round(report[NativeCombat.REPORT_MY_COUNTS + index]);
            topic.paragraphs.add("# Dans ce duel");
            topic.paragraphs.add(value + " " + Lang.tr("sur 100, mesurée sur") + " " + events + " "
                + Lang.tr(events > 1 ? "événements." : "événement.")
                + (events < 5 ? " " + Lang.tr("C'est peu : la valeur reste proche de la référence.") : ""));
            if (index < NativeCombat.FOE_FAMILY_COUNT) {
                topic.paragraphs.add(Lang.tr("Votre adversaire :") + " " + Math.round(report[NativeCombat.REPORT_FOE_FAMILIES + index]) + ".");
            }
        }
        return topic;
    }
}
