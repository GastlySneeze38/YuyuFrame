package com.yuyuframe.launcheragent.apigraphic.core;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

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
        List<Run> runs = new ArrayList<>();
        UiFont refFont = UiFont.REGULAR; // ascent/descent quasi identiques regular/bold (même famille/taille de base), sert de référence pour l'espacement de ligne
        float lineHeight = refFont.lineHeight(scale);
        float spaceWidth = refFont.textWidth(" ", scale);

        float cursorX = 0f;
        float lineTopY = 0f; // Y CROISSANT VERS LE HAUT (voir Blaze3DCore/ensureProjectionBuffer) — une ligne suivante a un lineTopY PLUS PETIT.
        int lineCount = 1;
        boolean lineHasContent = false;

        for (UiTextSpan span : spans) {
            UiFont font = span.bold ? UiFont.BOLD : UiFont.REGULAR;
            float spanScale = scale * span.sizeScale;
            // Paragraphes explicites : \n dans le texte du span force un saut de ligne.
            String[] paragraphs = span.text.split("\n", -1);
            for (int p = 0; p < paragraphs.length; p++) {
                if (p > 0) {
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

        float height = lineCount * lineHeight;
        return new Layout(runs, height, refFont.ascent, lineHeight);
    }

    /**
     * Rejoue un {@link Layout} déjà calculé à l'origine {@code (x, yTop)} —
     * {@code yTop} = haut du bloc (première ligne juste en-dessous). Pas de
     * paramètre {@code scale} ici : chaque {@link Run} porte déjà sa propre
     * échelle effective (voir {@link UiTextSpan#sizeScale}), figée au moment
     * de {@link #layout}.
     */
    public static void draw(UiRenderer renderer, Layout layout, float x, float yTop, int vpWidth, int vpHeight) {
        for (Run run : layout.runs) {
            UiFont font = run.span.bold ? UiFont.BOLD : UiFont.REGULAR;
            renderer.drawText(font, run.text, x + run.x, yTop + run.y, run.span.color, run.scale, vpWidth, vpHeight);
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
