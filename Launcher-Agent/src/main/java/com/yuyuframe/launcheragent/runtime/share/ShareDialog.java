package com.yuyuframe.launcheragent.runtime.share;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.HudConfigStore;
import com.yuyuframe.launcheragent.runtime.ui.config.SettingModal;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiTextField;

import java.util.ArrayList;
import java.util.List;

/**
 * La fenêtre « Partager mes paramètres » : en haut son propre lien, à copier ;
 * en dessous un champ où coller celui de quelqu'un d'autre, à appliquer. Ce
 * que chaque geste a donné s'écrit sous lui, en vert quand c'est fait, en
 * rouge sinon.
 *
 * <p>Le lien lui-même est fait de caractères que la police de l'interface ne
 * sait pas dessiner (des idéogrammes : c'est ce qui le rend si court). On en
 * montre donc le début lisible — {@code yuyuframe://options/} — et sa
 * longueur, et le champ où l'on colle écrit des points à la place, comme un
 * mot de passe : une ligne de « ? » ne dirait rien de plus.
 *
 * <p>Tout le travail est fait par le noyau natif ({@link NativeShare}).
 */
public final class ShareDialog implements SettingModal.Dialog {

    private static final float PANEL_W = 540f;
    private static final float PANEL_H = 312f;
    private static final String LINK_PREFIX = "yuyuframe://";

    private final List<UiWidget> widgets = new ArrayList<UiWidget>();
    private Runnable close;

    /** Son propre lien ({@code null} s'il n'a pas pu être fabriqué : {@link #copyNote} dit pourquoi). */
    private String link;
    private String copyNote;
    private boolean copyOk;
    /** Ce qui est dans le champ de collage — gardé ici, le champ étant refait à chaque mise en page. */
    private String pasted = "";
    private String applyNote;
    private boolean applyOk;

    private UiTextField field;
    private float px, py, pw, ph;
    private float boxX, boxY, boxW, boxH;
    private int lastW = -1, lastH = -1;
    private float lastScale = -1f;

    public ShareDialog() {
        buildLink();
    }

    private void buildLink() {
        NativeShare.Outcome outcome = NativeShare.link(HudConfigStore.exportText());
        link = outcome.ok ? outcome.text : null;
        if (!outcome.ok) {
            copyOk = false;
            copyNote = Lang.tr(outcome.text);
        }
    }

    @Override
    public void opened(Runnable close) {
        this.close = close;
    }

    @Override
    public List<UiWidget> widgets() {
        return widgets;
    }

    private static float s(float v) {
        return UiTheme.scaled(v);
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        if (vpWidth != lastW || vpHeight != lastH || UiTheme.UI_SCALE != lastScale) {
            build(vpWidth, vpHeight);
            lastW = vpWidth;
            lastH = vpHeight;
            lastScale = UiTheme.UI_SCALE;
        }

        // Voile plein écran : il assombrit le fond, et les clics à côté de la
        // fenêtre ne vont nulle part puisque ses widgets sont seuls actifs.
        renderer.drawRoundedRect(0, 0, vpWidth, vpHeight, 0f, new UiColor(0, 0, 0, 130), vpWidth, vpHeight);
        renderer.drawGlassPanel(px, py, px + pw, py + ph, UiTheme.RADIUS_MD,
            UiTheme.GLASS_TINT, UiTheme.GLASS_STRENGTH_MODAL, UiTheme.PANEL_BG,
            UiTheme.GLASS_BORDER, Math.max(1f, s(1f)), vpWidth, vpHeight);

        float pad = s(22f);
        float top = py + ph;
        renderer.drawText(UiFont.BOLD, Lang.tr("Partager mes paramètres"), px + pad, top - s(34f),
            UiTheme.TEXT_PRIMARY, s(0.6f), vpWidth, vpHeight);

        // ── Mon lien ──
        renderer.drawText(Lang.tr("Mon lien"), px + pad, top - s(66f), UiTheme.TEXT_SECONDARY, s(0.44f), vpWidth, vpHeight);
        renderer.drawRoundedRect(boxX, boxY, boxX + boxW, boxY + boxH, UiTheme.RADIUS_SM, UiTheme.CARD_BG, vpWidth, vpHeight);
        float textScale = s(0.42f);
        float baseline = boxY + boxH / 2f - s(4f);
        float inner = s(10f);
        if (link != null) {
            String shown = shownLink();
            renderer.drawText(shown, boxX + inner, baseline, UiTheme.TEXT_PRIMARY, textScale, vpWidth, vpHeight);
            String size = sizeOf(link);
            float sizeW = renderer.textWidth(UiFont.REGULAR, size, textScale);
            renderer.drawText(size, boxX + boxW - inner - sizeW, baseline, UiTheme.TEXT_MUTED, textScale, vpWidth, vpHeight);
        } else {
            renderer.drawText("--", boxX + inner, baseline, UiTheme.TEXT_MUTED, textScale, vpWidth, vpHeight);
        }
        note(renderer, copyNote, copyOk, px + pad, boxY - s(16f), vpWidth, vpHeight);

        // ── Appliquer un lien ──
        renderer.drawText(Lang.tr("Appliquer un lien"), px + pad, top - s(162f), UiTheme.TEXT_SECONDARY, s(0.44f), vpWidth, vpHeight);
        String under = applyNote;
        boolean underOk = applyOk;
        if (under == null && pasted.contains(LINK_PREFIX)) {
            // Rien d'appliqué encore, mais le champ contient bien un lien : le dire, puisqu'on n'en voit que des points.
            // Deux-points, pas un tiret long : la police de l'interface ne le dessine pas (il sortait en « ? »).
            under = Lang.tr("Lien reconnu") + " : " + sizeOf(pasted);
            underOk = true;
        }
        if (field != null) note(renderer, under, underOk, px + pad, field.y - s(16f), vpWidth, vpHeight);

        for (UiWidget widget : widgets) {
            try {
                widget.draw(renderer, mouseX, mouseY, vpWidth, vpHeight);
            } catch (Throwable error) {
                if (!drawErrorLogged) {
                    drawErrorLogged = true;
                    LauncherLog.err("[SHARE] Dessin de la fenêtre de partage", error);
                }
            }
        }
    }

    private boolean drawErrorLogged;

    /** Vert : c'est fait. Rouge : ça n'a pas marché, et pourquoi. */
    private static void note(UiRenderer renderer, String text, boolean ok, float x, float baseline, int vpWidth, int vpHeight) {
        if (text == null || text.isEmpty()) return;
        renderer.drawText(text, x, baseline, ok ? UiTheme.SUCCESS : UiTheme.DANGER, s(0.42f), vpWidth, vpHeight);
    }

    /** Le début lisible du lien ; la suite est faite de caractères que la police ne dessine pas. */
    private String shownLink() {
        int slash = link.indexOf('/', LINK_PREFIX.length());
        String head = slash > 0 ? link.substring(0, slash + 1) : LINK_PREFIX;
        return head + "...";
    }

    private static String sizeOf(String text) {
        int parts = text.split(LINK_PREFIX, -1).length - 1;
        int length = text.replace("\n", "").replace(" ", "").length();
        String size = length + " " + Lang.tr("caractères");
        return parts > 1 ? parts + " " + Lang.tr("parties") + ", " + size : size;
    }

    private void build(int vpWidth, int vpHeight) {
        widgets.clear();
        pw = s(PANEL_W);
        ph = s(PANEL_H);
        px = (vpWidth - pw) / 2f;
        py = (vpHeight - ph) / 2f;

        float pad = s(22f);
        float gap = s(8f);
        float rowH = s(30f);
        float top = py + ph;

        // Mon lien : la case, et « Copier » à sa droite.
        float copyW = s(96f);
        boxX = px + pad;
        boxY = top - s(108f);
        boxW = pw - pad * 2f - copyW - gap;
        boxH = rowH;
        widgets.add(new UiButton(boxX + boxW + gap, boxY, copyW, rowH, Lang.tr("Copier"), this::copy));

        // Appliquer un lien : le champ, puis « Coller » et « Appliquer ».
        float pasteW = s(84f), applyW = s(104f);
        float fieldY = top - s(204f);
        float fieldW = pw - pad * 2f - pasteW - applyW - gap * 2f;
        field = new UiTextField(px + pad, fieldY, fieldW, rowH, Lang.tr("Collez un lien ici (Ctrl+V)"), value -> {
            pasted = value;
            // Un autre lien : ce qu'on avait écrit sous le champ ne le concerne plus.
            applyNote = null;
        }).masked().onSubmit(this::apply);
        field.setText(pasted);
        widgets.add(field);
        widgets.add(new UiButton(px + pad + fieldW + gap, fieldY, pasteW, rowH, Lang.tr("Coller"), this::paste));
        widgets.add(new UiButton(px + pad + fieldW + gap + pasteW + gap, fieldY, applyW, rowH, Lang.tr("Appliquer"), this::apply));

        float closeW = s(110f);
        widgets.add(new UiButton(px + pw - pad - closeW, py + s(20f), closeW, s(28f), Lang.tr("Fermer"), () -> {
            if (close != null) close.run();
        }));
    }

    private void copy() {
        if (link == null) {
            buildLink();
            if (link == null) return;
        }
        NativeShare.Outcome outcome = NativeShare.copy(link);
        copyOk = outcome.ok;
        copyNote = !outcome.ok ? Lang.tr(outcome.text)
            : link.contains("\n") ? Lang.tr("Lien copié en plusieurs parties : collez-les toutes.")
            : Lang.tr("Lien copié dans le presse-papiers.");
    }

    private void paste() {
        NativeShare.Outcome outcome = NativeShare.clipboard();
        if (!outcome.ok) {
            applyOk = false;
            applyNote = Lang.tr(outcome.text);
            return;
        }
        // Sur une seule ligne : c'est un champ, et le lecteur de liens ignore les espaces.
        pasted = outcome.text.replace("\r", "").replace("\n", " ").trim();
        applyNote = null;
        if (field != null) field.setText(pasted);
    }

    private void apply() {
        if (pasted.trim().isEmpty()) {
            applyOk = false;
            applyNote = Lang.tr("Collez d'abord un lien.");
            return;
        }
        NativeShare.Outcome outcome = NativeShare.settings(pasted);
        if (!outcome.ok) {
            applyOk = false;
            applyNote = Lang.tr(outcome.text);
            return;
        }
        int applied = HudConfigStore.importText(outcome.text);
        applyOk = applied > 0;
        applyNote = applied > 0 ? applied + " " + Lang.tr("réglages appliqués.")
            : Lang.tr("Ce lien ne contient pas de réglages du client");
        if (applied > 0) {
            // Mes réglages viennent de changer : mon lien aussi.
            buildLink();
            if (link != null) copyNote = null;
        }
    }
}
