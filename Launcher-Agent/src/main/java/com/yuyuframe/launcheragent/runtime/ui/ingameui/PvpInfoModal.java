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
 * valait ce jour-là. Le bouton « Comment c'est compté » du détail d'une
 * famille ({@link PvpFamilyModal}) ouvre directement son sujet
 * ({@link #FAMILY_TOPICS}).
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
    private static final float OPEN_SECS = 0.45f;
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
        for (int i = 0; i < widgets.size(); i++) widgets.get(i).y = widgetBaseY.get(i);

        r.drawRoundedRect(0f, 0f, vw, vh, 0f, UiTheme.GLASS_SCRIM.multiplyAlpha(p), vw, vh);
        float y1 = py, y2 = py + ph;
        // La fenêtre entière dans un calque : elle jaillit du centre avec un
        // petit rebond en s'ouvrant, et recule en s'effaçant en se fermant.
        float scale = closingAt != 0L
            ? 0.92f + 0.08f * p
            : 0.84f + 0.16f * PvpScoreUi.backOut(PvpScoreUi.linear(openedAt, 0f, OPEN_SECS));
        r.pushLayer(p, scale, px + pw / 2f, py + ph / 2f, 0f, -(1f - p) * UiTheme.scaled(16f));
        try {
            r.drawShadow(px, y1, px + pw, y2, UiTheme.scaled(14f), 30f, 0f, new UiColor(0, 0, 0, 150), vw, vh);
            // L'opacité du repli pilote aussi celle du verre (voir UiRenderer.drawGlassPanel).
            r.drawGlassPanel(px, y1, px + pw, y2, UiTheme.scaled(14f),
                UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, UiTheme.PANEL_BG,
                UiTheme.GLASS_BORDER, Math.max(1f, UiTheme.scaled(1f)), vw, vh);
            drawBody(r, mouseX, mouseY, vw, vh, y1, y2);
        } finally {
            r.popLayer();
        }
    }

    private void drawBody(UiRenderer r, double mouseX, double mouseY, int vw, int vh, float y1, float y2) {
        PvpScoreUi.alpha = 1f;
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
            float t = PvpScoreUi.ease(topicAt, 0f, 0.38f);
            PvpScoreUi.alpha = t;
            float slide = (1f - t) * UiTheme.scaled(28f);
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
        if (report != null && PvpScoreModule.isSword(report)) return swordTopics(report);
        List<Topic> list = new ArrayList<Topic>();
        list.add(new Topic("Vue d'ensemble",
            "La note résume un duel à la masse sur 500. 250, c'est le joueur moyen : au-dessus, vous avez mieux joué que lui ; en dessous, moins bien. Elle monte en ligne droite : 50 points de plus valent le même progrès, que vous soyez à 200 ou à 400. 500 est rare.",
            "# Chaque action rapporte",
            "Chaque action du duel (une ouverture, un smash, une esquive, une charge de vent…) est comparée à ce qu'en tire un joueur moyen dans la même situation. Mieux que lui, elle rapporte des points ; moins bien, elle en coûte. Réussir une ouverture difficile rapporte beaucoup, une facile peu, et rater une facile coûte.",
            "Le détail de chaque famille montre les actions qui ont le plus compté et leurs points.",
            "# Les familles",
            "La note est faite de vos cinq familles, qui pèsent autant les unes que les autres : conversion, qualité du smash, défense, contrôle vertical et horizontal, ressources. Elle juge votre jeu seulement : la force de votre adversaire n'y entre pas.",
            "S'y ajoute un bonus, le bug use, pour les techniques les plus dures : il ne fait jamais baisser la note et s'affiche à part, par exemple 322 + 20 bug use.",
            "Tout est calculé sur votre PC, à partir de ce que votre jeu voit pendant le duel. Si le partage de vos duels est activé (en haut de cette page), ils sont envoyés anonymisés pour calibrer la note. Il n'y a pas de classement.",
            "# Les couleurs",
            "Vert à partir de 290 : un bon duel. Ambre de 235 à 289 : dans la moyenne. Rouge en dessous de 235 : un duel difficile. Gris : pas de note. Les barres bleues sont celles de l'adversaire.",
            "# Quand un duel est reconnu",
            "Un duel commence au premier échange de coups avec un vrai joueur. Il se termine quand l'un des deux perd son équipement à la fin de la manche. Un adversaire qui quitte le duel compte comme un match nul : il ne s'est pas vraiment battu.",
            "Le mode se reconnaît à l'arme en main : dès qu'une masse apparaît dans la main de l'un des deux, c'est un duel à la masse. Un duel où chacun n'a que son épée est noté en mode épée (voir « Mode épée »). Les autres sont enregistrés, mais pas notés."));
        list.add(new Topic("Opportunités",
            "Une opportunité, c'est un moment où un smash est possible : vous tombez d'au moins 1,5 bloc, vous êtes en train de descendre, vous êtes au-dessus de l'adversaire et à moins de 3,5 blocs de lui à l'horizontale.",
            "Elle est convertie si un coup de masse touche pendant ce moment. Les mêmes règles valent pour l'adversaire : ses opportunités sont celles que vous lui laissez.",
            "# Ce qui n'en est pas une",
            "Un passage de moins de 3 ticks au-dessus de lui, sans coup : il ne fait que traverser la zone. Et une chute face à son bouclier levé : le coup ne pouvait pas passer, ne pas frapper n'est pas un raté. Dans son dos, en revanche, c'est une opportunité, et une difficile.",
            "# Le bonus du smash",
            "Le smash ajoute des dégâts selon la hauteur de chute : 4 par bloc pour les 3 premiers, 2 par bloc jusqu'à 8, puis 1 par bloc au-delà, plus l'enchantement Densité.",
            "Les dégâts de smash d'un duel comparent la somme des bonus réellement portés à celle qu'auraient donnée toutes les opportunités. Les grosses ouvertures (14 blocs de chute et plus) et les petites sont comptées à part, hors note."));
        list.add(family(report, 0, "Conversion",
            "Exploitez-vous vos ouvertures, compte tenu de leur difficulté et de leur valeur ? Chaque opportunité a une chance de réussite, celle d'un joueur moyen sur la même situation, et un poids, son bonus de smash possible.",
            "La chance baisse quand vous êtes loin de lui à l'ouverture, quand il bouge vite, quand la fenêtre est courte, quand la chute est grande (on arrive vite, il a le temps de se replacer) et quand il faut passer dans le dos de son bouclier.",
            "Chaque ouverture est une action : convertie, elle rapporte d'autant plus que sa chance était faible ; ratée, elle coûte d'autant plus que sa chance était forte. Une grosse chute pèse plus qu'une petite. Faire comme un joueur moyen sur ces mêmes opportunités donne 250.",
            "Les chances sont posées à la main en attendant d'être apprises sur les duels partagés par les joueurs.",
            "Pour la monter : ne laissez pas passer les ouvertures faciles, collé à lui et sur une chute courte."));
        list.add(family(report, 1, "Qualité du smash",
            "Ce que vous tirez de vos gros coups. Pour chaque smash réussi : les dégâts portés divisés par les dégâts possibles. Possibles : masse chargée à fond, coup critique, et la chute qu'aurait eue le coup en attendant que vos pieds arrivent à la hauteur de sa tête.",
            "Deux erreurs y coûtent : frapper trop tôt, avec moins de chute que vous pouviez en prendre, et frapper mal chargé (moins de dégâts de base, pas de critique). La charge est celle que le jeu compte au coup : un attribute swap qui garde la charge de l'arme d'avant compte comme un coup bien chargé.",
            "La charge de la lance compte aussi : les dégâts à la vitesse de l'impact divisés par ceux que permettait la plus grande vitesse de l'approche.",
            "Aucun smash réussi alors que des ouvertures se sont présentées, c'est 0. Sans aucune ouverture, il n'y a rien à juger.",
            "La hauteur elle-même n'y compte pas : elle est déjà dans la conversion et le contrôle. Les coups ordinaires ne rapportent rien. Un joueur moyen tire 63 % de son smash : il part presque toujours en sprint, et le sprint empêche le critique. Chaque smash au-dessus rapporte, chaque smash en dessous coûte.",
            "Pour la monter : attendez le bas de la chute pour frapper, avec la barre d'attaque pleine et sans sprinter."));
        list.add(family(report, 2, "Défense",
            "Empêchez-vous l'adversaire de vous mettre ses gros coups, et tenez-vous les échanges ? Sept mesures : les échanges (un tiers), puis le bouclier, les ouvertures laissées, le temps vulnérable, l'esquive, atterrir proprement et la reprise après un coup.",
            "Échanges : chaque coup du duel, toutes armes confondues, rapporte s'il est porté et coûte s'il est reçu. C'est ce qui sépare deux joueurs sur un duel long, quand les taux de réussite de chacun se ressemblent.",
            "Bouclier : chaque smash arrêté rapporte selon ce qu'il valait, dix fois plus si vous étiez à 4 blocs ou plus au-dessus du sol ; chaque smash reçu coûte. Une garde cassée par une hache coûte autant qu'un smash de 8 blocs. Rester au sol bouclier levé sous lui coûte un peu.",
            "Ouvertures laissées : l'envers exact de la conversion, sur ses opportunités. Chaque descente esquivée se juge face à sa chance d'être esquivée. Temps vulnérable : au sol pendant qu'il est au-dessus de vous et à portée. Esquive : sortir de sa zone pendant ses descentes. Atterrir proprement : les dégâts de chute après un de vos smashs ratés. Reprise : vous dégager après un coup reçu.",
            "Les dégâts évités, affichés à part, résument ce qu'il pouvait vous mettre et qu'il n'a pas mis. Ils ne comptent pas dans la note.",
            "Pour la monter : levez le bouclier quand il tombe sur vous en l'air, pas au sol en permanence, et ne restez pas sous lui."));
        list.add(family(report, 3, "Contrôle vertical et horizontal",
            "Vous déplacez-vous pour mettre le coup ? On juge le déplacement obtenu, pas la technique : un combo qui ne vous amène nulle part ne rapporte rien.",
            "L'initiative (un tiers de la famille) : chaque ouverture du duel rapporte si c'est vous qui vous l'êtes créée, et coûte si c'est lui. Celui qui domine se crée plus d'ouvertures, même s'il en rate une partie.",
            "Suivre pendant la chute (35 %) : vos descentes qui arrivent à portée, la distance rattrapée quand il fuit, et les gros coups qui passent du premier coup. Prendre la hauteur (20 %) : vitesse de montée, temps pour repasser au-dessus de lui, hauteur gagnée par charge de vent ou par combo perle et charge de vent, et la domination de la hauteur.",
            "Se placer au sommet (15 %) : être juste au-dessus de lui quand vous commencez à redescendre. Se déplacer à l'horizontale (15 %) : vitesse de rapprochement face à sa fuite, élans de Lunge qui vous mettent à portée, vols en élytre qui finissent en opportunité. Enchaîner (15 %) : un smash suivi d'une nouvelle opportunité sans toucher le sol, et le temps pour reconstruire après chaque engagement.",
            "Une armure en netherite ou la protection contre les explosions font monter moins haut : la hauteur attendue en tient compte. Chaque descente, montée ou enchaînement est comparé à ce qu'en tire un joueur moyen.",
            "Pour la monter : suivez-le pendant votre chute pour que le coup passe du premier coup."));
        list.add(family(report, 4, "Ressources",
            "Utilisez-vous vos objets au bon moment, pour une bonne raison ? Charges de vent et perles (30 %), barre d'inventaire (20 %), nourriture et vie (20 %), totems (15 %), potions (10 %), fusées d'élytre (5 %). Une partie absente du duel ne compte pas.",
            "Chaque charge de vent ou perle doit avoir une raison : monter pour attaquer, casser son smash, fuir quand vous êtes bas ou sous lui, le toucher, annuler une chute qui allait faire mal, ou une perle qui vous place. Sans raison, elle est gâchée.",
            "Barre d'inventaire : le délai pour avoir le bon objet en main quand il sert (la masse à l'ouverture, la charge de vent après l'atterrissage, la pomme quand vous passez sous 4 cœurs). Pomme d'or : sous 4 cœurs ou juste après un totem. Potions : force et vitesse seulement. Totems : le délai pour en remettre un.",
            "Chaque objet utilisé est comparé à ce qu'en tire un joueur moyen. Chaque totem que vous lui faites sauter rapporte, chaque totem perdu coûte.",
            "Pour la monter : une charge de vent sert à quelque chose, pas à attendre."));

        Topic opponent = new Topic("Niveau adverse",
            "# Pendant le duel",
            "Le niveau de votre adversaire, tel qu'on peut le lire de ce qu'il a fait contre vous : ses quatre familles mesurables (conversion, qualité du smash, défense, contrôle vertical et horizontal), jugées avec les mêmes attendus que les vôtres, puis combinées comme une note. C'est ce que montre le face-à-face.",
            "Il n'entre pas dans la note de ce duel. On ne le voit jouer que contre vous : sa conversion est l'envers de votre défense, sa défense l'envers de votre conversion. Ce niveau est donc en grande partie le miroir de votre jeu ; le compter reviendrait à vous enlever une partie de ce que vous avez bien fait.",
            "# Avant le duel",
            "Sa force connue avant le duel est la moyenne de son niveau dans vos duels précédents contre lui, reconnu à son compte même s'il change de pseudo. Elle est tirée vers 250 tant que vous l'avez peu affronté : un duel passé compte pour un tiers, deux pour la moitié, huit pour les quatre cinquièmes.",
            "Elle n'entre pas dans votre note, qui ne juge que votre jeu. Elle est gardée pour le classement, où battre un adversaire fort comptera plus.",
            "# Ce qu'il faut savoir",
            "Ses ressources ne sont pas mesurées : ses objets ne se voient pas d'ici. Ce niveau ne vaut que sur votre PC et face à vous : ce n'est pas un classement.");
        if (report != null) {
            opponent.paragraphs.add("# Dans ce duel");
            int met = (int) report[NativeCombat.REPORT_FOE_KNOWN_DUELS];
            opponent.paragraphs.add(met > 0
                ? Lang.tr("Force connue avant le duel :") + " " + Math.round(report[NativeCombat.REPORT_FOE_KNOWN]) + ", " + Lang.tr("sur") + " " + met + " "
                    + Lang.tr(met > 1 ? "duels passés contre lui." : "duel passé contre lui.")
                : Lang.tr("Premier duel contre lui : sa force n'est pas encore connue."));
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
            "Un bonus pour les techniques les plus dures du PvP à la masse. Il ne fait jamais baisser la note : il s'y ajoute, jusqu'à 50 points, et la note reste plafonnée à 500. Une tentative ratée ne coûte rien.",
            "# Les techniques",
            "La famille de l'attribute swap, où l'on change d'objet dans le tick du coup : cooldown swap et lunge swap, les plus courants ; breach swap, density swap et reach swap. Et les techniques au tick près : deux totems coup sur coup, stun slam et instant pearl catch, les plus rares, qui rapportent le plus.",
            "Le bonus monte lentement : une technique rare réussie rapporte environ 5 points, une courante un peu plus d'un point, et répéter la même technique rapporte chaque fois moitié moins. Il faut plusieurs techniques rares dans un même duel pour approcher les 20 points.",
            "Une technique ne compte que réussie : le coup confirmé par le serveur, ou l'effet obtenu. Un breach ou density swap qui porte un smash compte double, un stun slam vaut plus selon le smash qui passe, un instant pearl catch plus selon la hauteur gagnée quand il mène à une opportunité.",
            "Passer de l'élytre au plastron en vol n'en fait pas partie : c'est un simple clic droit, que tout le monde fait.",
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
            "# 1. Chaque action",
            "Chaque action est comparée à ce qu'en tire un joueur moyen dans la même situation (sa chance, pour une ouverture, une esquive ou une descente), en nombre d'écarts habituels entre joueurs. Une famille fait la somme de ses actions.",
            "# 2. Lissage",
            "Une famille mesurée sur peu de situations est tirée vers la moyenne : on compte 2 situations moyennes de plus. Une seule situation compte donc pour un tiers, et deux smashs réussis sur deux ne valent pas le maximum.",
            "# 3. Combinaison",
            "Les cinq familles sont moyennées, puis élargies de 1,4 fois : une moyenne est plus resserrée qu'une famille seule.",
            "# 4. Note",
            "La note vaut 250, plus 100 par écart, en ligne droite, de 0 à 500. Chaque barre de famille suit la même échelle. Comme tout y est une somme, la note se découpe exactement en points : chaque action dit ce qu'elle a rapporté.",
            "# 5. Bonus",
            "Le bonus bug use s'ajoute ensuite à la note, sans jamais la faire baisser."));
        list.add(new Topic("Confiance et seuils",
            "La confiance dit sur combien d'opportunités la note repose : n / (n + 5), où n compte les opportunités des deux joueurs. 5 opportunités donnent 50 %, 20 en donnent 80 %.",
            "Un duel n'est pas noté s'il a duré moins de 20 secondes au contact, s'il compte moins de 3 opportunités en tout, ou s'il n'est pas à la masse. Il reste enregistré, mais l'historique ne montre que les duels notés.",
            "La note moyenne de l'historique ne compte que les duels notés."));
        list.add(new Topic("Limites",
            "La note n'est pas encore calibrée : ce qu'on attend de chaque action a été posé à partir de quelques duels. Les duels partagés par les joueurs servent à l'ajuster, et les notes pourront bouger.",
            "Elle est pensée pour la masse et l'épée en 1 contre 1. Un duel qui mêle d'autres objets (épée et bouclier, pommes, totems…) est enregistré mais pas noté.",
            "Le ping est affiché, jamais corrigé. Un ping élevé peut faire baisser la conversion.",
            "La note ne regarde que ce que votre jeu voit : positions, chutes, coups confirmés, sons du bouclier, objets en main. Elle ne sait rien de ce qui se passe hors de vue.",
            "Les duels enregistrés avant l'arrivée de ces mesures n'ont ni les coups arrêtés au bouclier ni l'objet en main au moment du coup : les parties qui en dépendent (bouclier, charge, barre d'inventaire, bug use) n'y comptent pas.",
            "Les journaux de vos duels sont dans le dossier de l'instance, sous yuyuframe/matches."));
        if (report == null) list.add(swordOverview());
        return list;
    }

    // ── L'épée ───────────────────────────────────────────────────────────────

    /** Le mode épée en un sujet : celui que montre l'historique, qui mêle les deux modes. */
    private static Topic swordOverview() {
        return new Topic("Mode épée",
            "Un duel où chacun n'a que son épée en main (ou la main vide), rien dans l'autre main et aucun objet utilisé, est noté en mode épée. Même échelle sur 500, même principe : chaque action est comparée à ce qu'en tire un joueur moyen.",
            "Quatre familles, qui pèsent autant : combos, qualité du coup, défense, placement. Pas de ressources : le seul objet est l'épée.",
            "Ouvrez un duel à l'épée pour voir le détail de chaque famille et du W-tap.");
    }

    /**
     * Les sujets d'un duel à l'épée, aux mêmes places que ceux de la masse
     * ({@link #FAMILY_TOPICS}) : la cinquième place, celle des ressources,
     * dit pourquoi il n'y en a pas.
     */
    private static List<Topic> swordTopics(double[] report) {
        List<Topic> list = new ArrayList<Topic>();
        list.add(new Topic("Vue d'ensemble",
            "La note résume un duel à l'épée sur 500. 250, c'est le joueur moyen : au-dessus, vous avez mieux joué que lui ; en dessous, moins bien. Elle monte en ligne droite. 500 est rare.",
            "# Chaque action rapporte",
            "Chaque coup, chaque W-tap, chaque combo est comparé à ce qu'en tire un joueur moyen. Mieux que lui, il rapporte des points ; moins bien, il en coûte. Le détail de chaque famille montre les actions qui ont le plus compté.",
            "# Les familles",
            "Quatre familles, qui pèsent autant : combos, qualité du coup, défense, placement. Elle juge votre jeu seulement : la force de votre adversaire n'y entre pas. Pas de bonus bug use en épée.",
            "# Quel duel est noté",
            "Seul l'épée pure est notée : chacun n'a que son épée en main (ou la main vide), rien dans l'autre main, et n'utilise aucun objet. Un bouclier, une pomme, un totem, une perle : le duel est enregistré mais pas noté.",
            "# Les couleurs",
            "Vert à partir de 290, ambre de 235 à 289, rouge en dessous. Les barres bleues sont celles de l'adversaire."));
        list.add(new Topic("Le W-tap",
            "Un coup porté en sprint, barre pleine, donne un recul en plus. Mais juste après, le jeu coupe votre sprint et vous ralentit. Pour que le coup suivant pousse encore autant, il faut relancer le sprint entre les deux : c'est le W-tap. Il garde l'adversaire à distance et vous laisse frapper le premier, encore et encore.",
            "# Comment il est jugé",
            "Pas à la touche, à son effet dans le combo. Après chacun de vos coups : si votre coup suivant est sprinté et arrive avant le sien, le W-tap est réussi. S'il vous touche avant, il est raté, et ça coûte.",
            "Un coup suivant sans sprint n'est pas un W-tap. Rien dans le délai (pas de coup suivant) : c'est un W-tap dans le vide, il ne compte pas.",
            "Chaque W-tap a une chance de réussite, celle d'un joueur moyen : elle monte quand le coup part loin de lui et quand il revient vite vers vous. Un joueur moyen en réussit environ un sur quatre : c'est le geste difficile du mode.",
            "# Pourquoi il rate",
            "Le détail donne la raison de chaque W-tap raté : rentré dans l'adversaire (moins d'un demi-bloc entre vous, la distance est perdue), barre pas rechargée quand il a frappé, ou coup suivant trop tard alors que la barre était pleine.",
            "Les W-taps de l'adversaire ne se jugent qu'avec des journaux récents, qui entendent le son de ses coups."));
        list.add(family(report, 0, "Combos",
            "Gardez-vous la main ? Trois mesures : garder la main (35 %), le W-tap (40 %), la longueur des combos (25 %).",
            "Garder la main : après chacun de vos coups, le coup suivant du duel est encore le vôtre. Un joueur moyen la garde 57 % du temps.",
            "Un combo, ce sont vos coups enchaînés à moins d'une seconde et demie, sans qu'il vous touche entre deux. Un combo de 4 coups ou plus vaut le maximum.",
            "Pour la monter : relancez le sprint entre chaque coup et frappez dès que la barre est pleine."));
        list.add(family(report, 1, "Qualité du coup",
            "Ce que vaut chacun de vos coups (60 %) et le recul réellement donné (40 %).",
            "Un coup complet a la barre pleine et est critique ou sprinté. Barre pleine sans l'un ni l'autre, il vaut 0,6. Barre pas pleine, il vaut la part des dégâts qu'il garde. Un joueur moyen est à 70 %.",
            "Le recul se lit sur ses déplacements juste après le coup, face à celui d'un coup sprinté. Ses positions arrivent avec un peu de retard : cette mesure est approximative.",
            "Pour la monter : ne frappez pas avant que la barre soit pleine."));
        list.add(family(report, 2, "Défense",
            "Cassez-vous ses combos et tenez-vous les échanges ? Trois mesures : casser ses combos (30 %), les échanges (40 %), le jump reset (30 %).",
            "Casser : après chacun de ses coups, le coup suivant est le vôtre. Échanges : chaque coup du duel rapporte s'il est porté et coûte s'il est reçu.",
            "Jump reset : sauter au moment où un coup vous touche au sol, ce qui réduit le recul. Un coup reçu en l'air ne compte pas : il n'y avait pas de saut possible.",
            "Pour la monter : sautez au moment de son coup et répondez tout de suite."));
        list.add(family(report, 3, "Placement",
            "Où êtes-vous quand le coup part ? Quatre mesures : le premier coup de l'échange (35 %), la portée (25 %), l'espacement (20 %), le strafe (20 %).",
            "Un échange s'arrête après 2 secondes sans coup. Le premier coup de chacun rapporte s'il est à vous, coûte s'il est à lui.",
            "Portée : frapper près des 3 blocs, là où il ne touche pas encore, plutôt que collé à lui (1,5 bloc ou moins). Espacement : entre deux de vos coups, ne pas lui rentrer dedans. Strafe : quand il est à moins de 5 blocs, la part de votre déplacement de côté plutôt que droit sur lui.",
            "Pour la monter : gardez la distance de portée et tournez autour de lui."));
        list.add(new Topic("Pas de ressources",
            "En épée, le seul objet est l'épée : il n'y a pas de ressources à juger. Cette famille n'existe qu'en masse, et la note de l'épée n'est faite que des quatre autres."));

        Topic opponent = new Topic("Niveau adverse",
            "# Pendant le duel",
            "Le niveau de votre adversaire, lu de ce qu'il a fait contre vous : ses quatre familles, jugées avec les mêmes attendus que les vôtres. Il n'entre pas dans votre note : on ne le voit jouer que contre vous, c'est en partie le miroir de votre jeu.",
            "# Avant le duel",
            "Sa force connue avant le duel est la moyenne de son niveau dans vos duels précédents contre lui. Elle n'entre pas dans votre note ; elle est gardée pour le classement.",
            "# Ce qu'il faut savoir",
            "Sa barre et son sprint se lisent au son de ses coups, seulement dans les journaux récents. Sans eux, ses W-taps ne sont pas jugés.");
        opponent.paragraphs.add("# Dans ce duel");
        int met = (int) report[NativeCombat.REPORT_FOE_KNOWN_DUELS];
        opponent.paragraphs.add(met > 0
            ? Lang.tr("Force connue avant le duel :") + " " + Math.round(report[NativeCombat.REPORT_FOE_KNOWN]) + ", " + Lang.tr("sur") + " " + met + " "
                + Lang.tr(met > 1 ? "duels passés contre lui." : "duel passé contre lui.")
            : Lang.tr("Premier duel contre lui : sa force n'est pas encore connue."));
        StringBuilder detail = new StringBuilder(Lang.tr("Niveau pendant le duel") + " " + Math.round(report[NativeCombat.REPORT_FAMILIES + 5]) + " : ");
        for (int i = 0; i < NativeCombat.FOE_FAMILY_COUNT; i++) {
            if (i > 0) detail.append(", ");
            detail.append(Lang.tr(PvpScoreModule.familyName(i, report)).toLowerCase(java.util.Locale.ROOT)).append(" ")
                .append(Math.round(report[NativeCombat.REPORT_FOE_FAMILIES + i]));
        }
        detail.append(".");
        opponent.paragraphs.add(detail.toString());
        list.add(opponent);

        list.add(new Topic("Du chiffre à la note",
            "# 1. Chaque action",
            "Chaque action est comparée à ce qu'en tire un joueur moyen, en nombre d'écarts habituels entre joueurs. Une famille fait la somme de ses actions.",
            "# 2. Lissage",
            "Une famille mesurée sur peu de coups est tirée vers la moyenne : on compte 2 situations moyennes de plus.",
            "# 3. Combinaison",
            "Les quatre familles sont moyennées, puis élargies de 2 fois : une moyenne de quatre est plus resserrée qu'une famille seule.",
            "# 4. Note",
            "La note vaut 250, plus 100 par écart, en ligne droite, de 0 à 500. Chaque action dit ce qu'elle a rapporté."));
        list.add(new Topic("Confiance et seuils",
            "La confiance dit sur combien de coups la note repose : n / (n + 12), où n compte les coups du duel. 12 coups donnent 50 %, 48 en donnent 80 %.",
            "Un duel n'est pas noté s'il a duré moins de 20 secondes au contact, ou s'il compte moins de 6 coups en tout."));
        list.add(new Topic("Limites",
            "La note de l'épée a été posée sur peu de duels : ce qu'on attend de chaque action sera ajusté avec les duels partagés, et les notes pourront bouger.",
            "Le recul, la portée et le strafe se lisent dans ses positions, qui arrivent avec du retard : ce sont les mesures les moins précises.",
            "Le ping est affiché, jamais corrigé.",
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
            topic.paragraphs.add(value + " " + Lang.tr("sur 500, mesurée sur") + " " + events + " "
                + Lang.tr(events > 1 ? "événements." : "événement.")
                + (events < 5 ? " " + Lang.tr("C'est peu : la valeur reste proche de la moyenne.") : ""));
            if (index < NativeCombat.FOE_FAMILY_COUNT) {
                topic.paragraphs.add(Lang.tr("Votre adversaire :") + " " + Math.round(report[NativeCombat.REPORT_FOE_FAMILIES + index]) + ".");
            }
        }
        return topic;
    }
}
