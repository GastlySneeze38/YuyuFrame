package com.yuyuframe.launcheragent.apigraphic.draw.item;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Les deux files d'attente du pont vers les renderers vanilla — icônes d'objet
 * et blits de texture GUI — plus la conversion de coordonnées qui les alimente.
 *
 * <h2>Pourquoi une file du tout</h2>
 *
 * Nos modules dessinent dans la passe DU MOD, c'est-à-dire APRÈS que vanilla a
 * fini d'envoyer sa GUI au GPU pour cette frame. On ne peut donc pas dessiner
 * sur-le-champ : on met en file, et l'ère vide la file au tout début de la
 * frame SUIVANTE, dans le vrai contexte de rendu vivant que son point
 * d'accroche lui fournit. Décalage d'une frame (~8-16 ms), imperceptible ;
 * technique standard pour participer à une passe déjà terminée.
 *
 * <h2>Une file PAR ÈRE, et non plus une file partagée</h2>
 *
 * Ces deux listes étaient {@code static} et partagées LEXICALEMENT entre les
 * ères gl3 et blaze3d — un seul producteur et un seul consommateur à
 * l'exécution, puisqu'une seule ère tourne, mais un partage qui empêchait tout
 * découpage. Chaque ère instancie désormais la sienne, ce qui ne coûte rien
 * pour la même raison : l'autre n'est jamais chargée. Même raisonnement, et
 * même précédent, que {@code IconTextures} lors du découpage gl2/gl3.
 *
 * <p>Classe de CALCUL et d'ÉTAT : elle ne connaît ni type du jeu, ni
 * réflexion, ni ère. Elle ne fait que convertir des coordonnées (via
 * {@link VanillaGuiScale}) et garder ce qui attend.
 */
public final class VanillaItemQueue {

    /** Nom de l'ère, pour les traces — ex. {@code "differe"} / {@code "immediat"}. */
    private final String path;

    private final List<VanillaItemIcon> icons = new ArrayList<>();
    private final List<VanillaGuiBlit> blits = new ArrayList<>();

    /**
     * Plafond par file. Une frame réelle en met quelques dizaines ; au-delà,
     * c'est que PERSONNE ne vide — cas de la 1.8.9 en ère gl3 (2026-09-14) :
     * cette ère vide sur un {@code DrawContext} que la 1.8.9 n'a pas, et sans
     * plafond la file grossissait à chaque frame, sans fin. On jette alors le
     * contenu (les icônes ne s'affichent pas, ce qui est déjà le cas) et on le
     * dit une fois.
     */
    private static final int MAX_PENDING = 1024;
    private boolean overflowReported;

    public VanillaItemQueue(String path) {
        this.path = path;
    }

    // ── Mise en file ──────────────────────────────────────────────────────

    /**
     * Convertit puis met en file une icône.
     *
     * <p>{@code x}/{@code y} arrivent en NOTRE convention (coin BAS-gauche,
     * origine bas-gauche de l'écran, pixels framebuffer) et repartent en
     * convention vanilla (coin HAUT-gauche, pixels GUI).
     *
     * <p>BUG HISTORIQUE dont ce flip est le correctif (test utilisateur,
     * « icônes dans le mauvais ordre » par rapport au texte de durabilité) :
     * le chemin moderne faisait {@code guiY = y / echelle} — un simple ratio,
     * JAMAIS de flip — alors que le chemin legacy faisait
     * {@code vpHeight - y - taille} correctement depuis le début. Sans lui, la
     * pile entière d'icônes apparaît dans l'ordre inverse du texte (qui, lui,
     * passe par notre propre pipeline et n'est donc jamais concerné).
     */
    public void enqueueIcon(Object itemStack, float x, float y, float size,
                            boolean vanillaExtras, int vpWidth, int vpHeight) {
        float guiScale = VanillaGuiScale.of(vpWidth);
        int guiX = Math.round(x / guiScale);
        int guiY = Math.round((vpHeight - y - size) / guiScale);
        reportFirstEnqueue(guiScale, guiX, guiY);
        synchronized (icons) {
            if (icons.size() >= MAX_PENDING) dropUnflushed(icons, "icônes");
            icons.add(new VanillaItemIcon(itemStack, guiX, guiY, vanillaExtras));
        }
    }

    /** Convertit puis met en file un blit de texture GUI brute — même conversion que {@link #enqueueIcon}. */
    public void enqueueBlit(String texturePath, float x, float y, float w, float h,
                            float u, float v, float texW, float texH, int vpWidth, int vpHeight) {
        float guiScale = VanillaGuiScale.of(vpWidth);
        int guiX = Math.round(x / guiScale);
        int guiY = Math.round((vpHeight - y - h) / guiScale);
        int guiW = Math.round(w / guiScale);
        int guiH = Math.round(h / guiScale);
        synchronized (blits) {
            if (blits.size() >= MAX_PENDING) dropUnflushed(blits, "blits");
            blits.add(new VanillaGuiBlit(texturePath, guiX, guiY, guiW, guiH, u, v, texW, texH));
        }
    }

    /** Appelé sous le verrou de la liste concernée — voir {@link #MAX_PENDING}. */
    private void dropUnflushed(List<?> queue, String what) {
        queue.clear();
        if (overflowReported) return;
        overflowReported = true;
        LauncherLog.warn("[UiRenderer] file de " + what + " (chemin " + path + ") jamais vidée — "
            + MAX_PENDING + " éléments jetés ; l'ère active n'a pas d'hôte de vidage sur cette version");
    }

    // ── Vidage ────────────────────────────────────────────────────────────

    /** Prend et VIDE les icônes en attente. Liste vide si rien n'attendait. */
    public List<VanillaItemIcon> drainIcons() {
        synchronized (icons) {
            if (icons.isEmpty()) return Collections.emptyList();
            List<VanillaItemIcon> batch = new ArrayList<>(icons);
            icons.clear();
            return batch;
        }
    }

    /** Prend et VIDE les blits en attente. Liste vide si rien n'attendait. */
    public List<VanillaGuiBlit> drainBlits() {
        synchronized (blits) {
            if (blits.isEmpty()) return Collections.emptyList();
            List<VanillaGuiBlit> batch = new ArrayList<>(blits);
            blits.clear();
            return batch;
        }
    }

    // ── Diagnostics, une ligne chacun par session ─────────────────────────
    //
    // Ces sorties étaient MUETTES, et c'est exactement ce qui a empêché de
    // diagnostiquer les icônes d'armure absentes en 1.21.11 (v1101-v1105) :
    // impossible de distinguer « la file n'a jamais été remplie » de « la file
    // est pleine mais le dessin échoue ». Elles ont fini par trancher en un
    // seul lancement. Elles restent ici, au point exact où l'information
    // existe, plutôt que dans chaque ère.

    private boolean firstEnqueueReported, emptyBatchReported, firstBatchReported;

    /**
     * Trace de la PREMIÈRE icône mise en file. Porte l'échelle et les
     * coordonnées GUI calculées, ce qui rend visible un placement hors écran —
     * le symptôme du repli d'échelle à 1 (voir {@link VanillaGuiScale}).
     */
    private void reportFirstEnqueue(float guiScale, int guiX, int guiY) {
        if (firstEnqueueReported) return;
        firstEnqueueReported = true;
        LauncherLog.info("[UiRenderer] itemIconModern: première icône en file (chemin "
            + path + ") — echelleGUI=" + guiScale + " guiX=" + guiX + " guiY=" + guiY);
    }

    /** À appeler par l'ère quand les deux lots ressortent vides. */
    public void reportEmptyBatch() {
        if (emptyBatchReported) return;
        emptyBatchReported = true;
        LauncherLog.info("[UiRenderer] itemIconModern: file VIDE au vidage —"
            + " aucune icône n'a été mise en file (l'appelant ne dessine pas,"
            + " ou son chemin d'ajout a échoué plus tôt)");
    }

    /** À appeler par l'ère au premier vidage non vide. */
    public void reportFirstBatch(int iconCount, int blitCount) {
        if (firstBatchReported) return;
        firstBatchReported = true;
        LauncherLog.info("[UiRenderer] itemIconModern: premier vidage — "
            + iconCount + " icône(s), " + blitCount + " blit(s)");
    }
}
