package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout;
import com.yuyuframe.launcheragent.apigraphic.layout.UiLayout.Box;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.i18n.Lang;
import com.yuyuframe.launcheragent.runtime.ui.ConfigScreenBuilder;
import com.yuyuframe.launcheragent.runtime.ui.LauncherModule;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiPanel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;

import java.util.LinkedHashMap;

import static com.yuyuframe.launcheragent.apigraphic.value.UiTheme.px;

/**
 * Les réglages d'un module : une seule liste qui défile, une section par
 * catégorie (cliquer une catégorie à gauche y fait défiler, façon Sodium), et
 * l'aperçu du module à droite quand il en a un et que la place le permet.
 *
 * <p>Cet écran ne connaît aucun module en particulier : les lignes viennent
 * des réglages DÉCLARÉS par le module ({@link ConfigScreenBuilder}). Elles sont
 * refaites à chaque mise en page depuis les valeurs du module, qui reste la
 * seule source de vérité.
 */
public class UiModConfigScreen extends UiConfigScreenBase {

    private final LauncherModule module;
    private int lastSettingsRevision = -1;
    /** Cadre de l'aperçu ; {@code previewSize} nul = pas d'aperçu. */
    private float previewX, previewY, previewW, previewH, previewSize;

    public UiModConfigScreen(Object lastScreen, LauncherModule module) {
        super(module.name, lastScreen);
        this.module = module;
    }

    @Override
    protected float sidebarWidth() { return 180f; }

    /** Un module peut changer le NOMBRE de ses lignes (ajout d'une macro) sans que la taille ne bouge. */
    @Override
    protected boolean contentChanged() {
        return module.settingsRevision != lastSettingsRevision;
    }

    @Override
    protected LinkedHashMap<String, Float> buildContent(UiScrollContainer scroll, float rowX, float rowW) {
        lastSettingsRevision = module.settingsRevision;
        ConfigScreenBuilder.Result result = ConfigScreenBuilder.buildContinuous(module, rowX, rowW);
        for (UiWidget w : result.rows) scroll.add(w);
        return result.anchors;
    }

    /** L'aperçu : 300 au plus, et rien s'il reste moins de 150 (la liste ne lui cède rien). */
    @Override
    protected Box extraColumn() {
        if (!module.hasConfigPreview()) return null;
        return UiLayout.box().width(px(300f)).shrink(1f).minWidth(0f).heightPct(100f);
    }

    @Override
    protected void extraLaidOut(TaffyLayoutResult.Rect rect, TaffyLayoutResult.Rect viewport) {
        previewSize = 0f;
        if (rect == null || rect.w < px(150f)) return;
        float pad = px(UiTheme.Space.S14), title = px(UiTheme.Space.S32);
        previewX = rect.x;
        previewW = rect.w;
        previewH = Math.min(rect.w + title, viewport.h);
        previewY = viewport.y + viewport.h - previewH;
        previewSize = Math.min(previewW - 2f * pad, previewH - title - pad);
    }

    @Override
    protected void drawExtras(UiRenderer renderer) {
        if (previewSize <= 0f) return;
        UiPanel.draw(renderer, previewX, previewY, previewW, previewH, Lang.tr("Aperçu"), screenWidth, screenHeight);
        module.drawConfigPreview(renderer, previewX + (previewW - previewSize) / 2f,
            previewY + px(UiTheme.Space.S14), previewSize, screenWidth, screenHeight);
    }

    /** Une fenêtre du module vient de se fermer : le module relit sa configuration, puis on l'écrit. */
    @Override
    protected void onSettingModalClosed() {
        module.onConfigChanged();
        super.onSettingModalClosed();
    }
}
