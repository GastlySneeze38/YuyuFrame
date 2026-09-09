package com.yuyuframe.launcheragent.apigraphic.text;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;

import java.util.ArrayList;
import java.util.List;

/**
 * Layout + dessin d'un paragraphe multi-style (roadmap Phase 5.3) — liste de
 * {@link UiTextSpan} (gras/couleur/lien mélangés) découpée en lignes par un
 * word-wrap glouton classique à une largeur maximale donnée.
 *
 * Séparé en {@link #layout} (pur calcul, pas de dessin — coûteux à refaire
 * à chaque frame pour un long texte, l'appelant doit mettre le résultat en
 * cache et ne relayouter que si le texte/la largeur change, même principe
 * que {@code TaffyBridge.computeLayout}) et {@link #draw} (rejoue un
 * résultat déjà calculé). {@link #hitTestLink} permet de savoir si un point
 * (souris) survole un span cliquable, pour {@code onClick}/tooltip côté
 * appelant.
 */
public final class UiRichText {
    private UiRichText() {}

    /** Toggle via {@code /yf richtextpoc} (voir {@code YfCommands}) — vérifié chaque frame par {@code GlobalUiRenderMixin261}, jamais actif par défaut. */
    public static volatile boolean testEnabled = false;
    private static Layout testLayout;
    private static String testHoveredLink;

    /** Paragraphe de test fixe (gras/couleur/lien mélangés, forcé à wrapper sur plusieurs lignes) — dessiné en direct, POC autonome comme {@code UiSolidPipelinePoc}/{@code Blaze3DBlur.drawTestPanel}. */
    public static void drawTestParagraph(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (testLayout == null) {
            List<UiTextSpan> spans = new ArrayList<>();
            spans.add(UiTextSpan.plain("YuyuFrame ", new UiColor(232, 232, 238, 255)));
            spans.add(UiTextSpan.bold("Phase 5.3", new UiColor(139, 124, 255, 255)));
            spans.add(UiTextSpan.plain(" — preuve de mécanisme du rich text : ce paragraphe mélange du ", new UiColor(232, 232, 238, 255)));
            spans.add(UiTextSpan.bold("gras", new UiColor(232, 232, 238, 255)));
            spans.add(UiTextSpan.plain(", de la ", new UiColor(232, 232, 238, 255)));
            spans.add(UiTextSpan.plain("couleur", new UiColor(230, 95, 95, 255)));
            spans.add(UiTextSpan.plain(" et un ", new UiColor(232, 232, 238, 255)));
            spans.add(UiTextSpan.link("lien cliquable", new UiColor(139, 124, 255, 255), "https://yuyuframe.eu"));
            spans.add(UiTextSpan.sized("GROS", new UiColor(255, 255, 255, 255), 1.6f));
            spans.add(UiTextSpan.plain(" mot (taille de span) dans une seule chaîne, avec un vrai retour à la ligne automatique (word-wrap) à largeur fixe.\nEt un saut de paragraphe explicite ici.", new UiColor(150, 150, 163, 255)));
            testLayout = layout(spans, 340f, 0.4f);
        }
        float x = 40f, yTop = vpHeight - 120f;
        testHoveredLink = hitTestLink(testLayout, x, yTop, 0.4f, mouseX, mouseY);
        draw(renderer, testLayout, x, yTop, vpWidth, vpHeight);
    }

    /** Un mot (ou fragment insécable) déjà positionné dans le paragraphe layouté. */
    public static final class Run {
        public final UiTextSpan span;
        public final String text;
        public final float x, y; // y = ligne de base, mêmes conventions que UiRenderer#drawText
        public final float width;
        public final float scale; // = échelle de base du paragraphe × span.sizeScale (voir UiTextSpan#sizeScale)

        Run(UiTextSpan span, String text, float x, float y, float width, float scale) {
            this.span = span; this.text = text; this.x = x; this.y = y; this.width = width; this.scale = scale;
        }
    }

    public static final class Layout {
        public final List<Run> runs;
        /** Hauteur totale du bloc (nombre de lignes × hauteur de ligne). */
        public final float height;
        final float ascent, descentPlusLine;

        Layout(List<Run> runs, float height, float ascent, float descentPlusLine) {
            this.runs = runs; this.height = height; this.ascent = ascent; this.descentPlusLine = descentPlusLine;
        }
    }

    /**
     * @param spans    le paragraphe, dans l'ordre de lecture — un {@code \n} À L'INTÉRIEUR du texte d'un span force un saut de ligne (paragraphes).
     * @param maxWidth largeur de wrap en pixels — un seul mot plus large que {@code maxWidth} déborde plutôt que d'être coupé en plein milieu (pas de coupure de mot, comme la plupart des moteurs de texte).
     * @param scale    même échelle que {@link UiRenderer#drawText}.
     */
    public static Layout layout(List<UiTextSpan> spans, float maxWidth, float scale) {
        return layout(spans, maxWidth, scale, 0);
    }

    /**
     * Variante BORNÉE en hauteur — s'arrête après {@code maxLines} lignes et
     * termine la dernière par un caractère de troncature.
     *
     * <p>Indispensable dès qu'un texte riche vit dans une boîte de hauteur
     * FIXE (une carte, une cellule de liste) : la version non bornée passe à
     * autant de lignes que le texte l'exige, donc un texte long déborde
     * silencieusement sous la boîte, par-dessus ce qui suit. Le seul recours
     * sans ce paramètre serait de pré-tronquer le texte à l'aveugle côté
     * appelant, en devinant combien de caractères tiennent sur N lignes —
     * exactement le calcul que ce moteur est censé faire.
     *
     * @param maxLines {@code <= 0} = illimité (comportement de la surcharge à 3 arguments).
     */
    public static Layout layout(List<UiTextSpan> spans, float maxWidth, float scale, int maxLines) {
        List<Run> runs = new ArrayList<>();
        UiFont refFont = UiFont.REGULAR; // ascent/descent quasi identiques regular/bold (même famille/taille de base), sert de référence pour l'espacement de ligne
        float lineHeight = refFont.lineHeight(scale);
        float spaceWidth = refFont.textWidth(" ", scale);

        float cursorX = 0f;
        float lineTopY = 0f; // Y CROISSANT VERS LE HAUT (voir Blaze3DCore/ensureProjectionBuffer) — une ligne suivante a un lineTopY PLUS PETIT.
        int lineCount = 1;
        boolean lineHasContent = false;

        boolean truncated = false;
        outer:
        for (UiTextSpan span : spans) {
            UiFont font = span.bold ? UiFont.BOLD : UiFont.REGULAR;
            float spanScale = scale * span.sizeScale;
            // Paragraphes explicites : \n dans le texte du span force un saut de ligne.
            String[] paragraphs = span.text.split("\n", -1);
            for (int p = 0; p < paragraphs.length; p++) {
                if (p > 0) {
                    if (maxLines > 0 && lineCount >= maxLines) { truncated = true; break outer; }
                    lineCount++;
                    lineTopY -= lineHeight;
                    cursorX = 0f;
                    lineHasContent = false;
                }
                String[] words = paragraphs[p].split(" ", -1);
                for (int w = 0; w < words.length; w++) {
                    if (w > 0) {
                        // Le séparateur entre deux "mots" du split est un espace d'origine.
                        if (lineHasContent) cursorX += spaceWidth;
                    }
                    String word = words[w];
                    if (word.isEmpty()) continue;
                    float wordWidth = font.textWidth(word, spanScale);
                    if (lineHasContent && cursorX + wordWidth > maxWidth) {
                        // Le mot ne tient pas sur cette ligne ET il n'y a plus
                        // de ligne disponible : on s'arrête ici plutôt que de
                        // déborder sous la boîte.
                        if (maxLines > 0 && lineCount >= maxLines) { truncated = true; break outer; }
                        lineCount++;
                        lineTopY -= lineHeight;
                        cursorX = 0f;
                        lineHasContent = false;
                    }
                    float baselineY = lineTopY - font.ascent * scale * UiFont.SIZE_CORRECTION;
                    runs.add(new Run(span, word, cursorX, baselineY, wordWidth, spanScale));
                    cursorX += wordWidth;
                    lineHasContent = true;
                }
            }
        }

        if (truncated && !runs.isEmpty()) appendEllipsis(runs, maxWidth, scale);

        float height = lineCount * lineHeight;
        return new Layout(runs, height, refFont.ascent, lineHeight);
    }

    /**
     * Remplace le dernier {@link Run} par une version suffixée de « … »,
     * en rognant sa fin autant que nécessaire pour que le tout tienne encore
     * dans {@code maxWidth}.
     *
     * <p>Sans ce rognage, ajouter le caractère de troncature ferait déborder
     * la dernière ligne de la largeur de wrap — la troncature créerait
     * elle-même le débordement qu'elle est censée empêcher.
     */
    private static void appendEllipsis(List<Run> runs, float maxWidth, float scale) {
        int lastIdx = runs.size() - 1;
        Run last = runs.get(lastIdx);
        UiFont font = last.span.bold ? UiFont.BOLD : UiFont.REGULAR;
        String base = last.text;
        while (true) {
            String candidate = base + "…";
            float width = font.textWidth(candidate, last.scale);
            if (last.x + width <= maxWidth || base.isEmpty()) {
                runs.set(lastIdx, new Run(last.span, candidate, last.x, last.y, width, last.scale));
                return;
            }
            base = base.substring(0, base.length() - 1);
        }
    }

    /**
     * Rejoue un {@link Layout} déjà calculé à l'origine {@code (x, yTop)} —
     * {@code yTop} = haut du bloc (première ligne juste en-dessous). Pas de
     * paramètre {@code scale} ici : chaque {@link Run} porte déjà sa propre
     * échelle effective (voir {@link UiTextSpan#sizeScale}), figée au moment
     * de {@link #layout}.
     */
    public static void draw(UiRenderer renderer, Layout layout, float x, float yTop, int vpWidth, int vpHeight) {
        draw(renderer, layout, x, yTop, 1f, vpWidth, vpHeight);
    }

    /**
     * Variante avec opacité GLOBALE — multiplie l'alpha de chaque span sans
     * toucher au {@link Layout}.
     *
     * <p>Séparer l'opacité du layout est indispensable dès qu'un texte riche
     * vit dans une UI qui s'estompe (carte au bord d'une zone défilante,
     * apparition en cascade) : la couleur, elle, ne change RIEN aux positions
     * calculées. Sans ce paramètre, un appelant devrait recréer ses spans avec
     * des couleurs pré-multipliées à chaque frame, donc relancer {@link
     * #layout} (coûteux, à mettre en cache) à chaque frame — exactement ce que
     * la javadoc de {@link #layout} demande d'éviter.
     */
    public static void draw(UiRenderer renderer, Layout layout, float x, float yTop, float alpha, int vpWidth, int vpHeight) {
        for (Run run : layout.runs) {
            UiFont font = run.span.bold ? UiFont.BOLD : UiFont.REGULAR;
            UiColor color = alpha >= 1f ? run.span.color : run.span.color.multiplyAlpha(alpha);
            renderer.drawText(font, run.text, x + run.x, yTop + run.y, color, run.scale, vpWidth, vpHeight);
        }
    }

    /**
     * @return l'{@code linkId} du span cliquable sous {@code (mouseX,mouseY)} (coordonnées écran, même repère que {@code x/yTop} passés à {@link #draw}), ou {@code null}.
     */
    public static String hitTestLink(Layout layout, float x, float yTop, float scale, double mouseX, double mouseY) {
        for (Run run : layout.runs) {
            if (run.span.linkId == null) continue;
            float runX = x + run.x;
            float runTop = yTop + run.y + layout.ascent * scale * UiFont.SIZE_CORRECTION;
            float runBottom = runTop - layout.descentPlusLine;
            if (mouseX >= runX && mouseX <= runX + run.width && mouseY <= runTop && mouseY >= runBottom) {
                return run.span.linkId;
            }
        }
        return null;
    }
}
