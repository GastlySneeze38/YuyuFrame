package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.value.UiColor;
import com.yuyuframe.launcheragent.apigraphic.value.UiFont;
import com.yuyuframe.launcheragent.apigraphic.platform.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.asset.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.widget.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.apigraphic.value.UiTheme;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.Block;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.DividerBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.HeadingBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImageRowBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ImgRef;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ListItemBlock;
import com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.ParagraphBlock;
import static com.yuyuframe.launcheragent.runtime.content.ModrinthMarkdown.parseBlocks;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Extrait de ModrinthProjectDetailScreen le 2026-08-27 : ce widget était
// une classe interne STATIQUE, donc sans lien avec l'instance de l'écran —
// sa sortie est un simple déplacement, pas un changement de comportement.
/** Rangée de petites images (badges/logos) côte à côte, hauteur fixe, ratio d'aspect préservé par image — voir {@link #parseBlocks} pour le regroupement. */
public final class InlineIconRow extends UiWidget {
    private final List<ImgRef> imgs;

    public InlineIconRow(float x, float y, float w, float h, List<ImgRef> imgs) {
        super(x, y, w, h);
        this.imgs = imgs;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        float targetH = h - 8f;
        float cx = x;
        for (ImgRef ref : imgs) {
            BufferedImage img = UiRemoteImage.get(ref.url);
            float iw;
            if (img != null && img.getHeight() > 0) {
                iw = targetH * ((float) img.getWidth() / img.getHeight());
            } else {
                iw = ref.declaredW != null ? Math.min(ref.declaredW, 120) : targetH;
            }
            if (cx + iw > x + w) break; // pas de retour à la ligne — troncature défensive, rare en pratique
            if (img != null) {
                renderer.drawIcon(ref.cacheKey, img, cx, y + 4f, iw, targetH, vpWidth, vpHeight);
            } else {
                renderer.drawRoundedRect(cx, y + 4f, cx + iw, y + 4f + targetH, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT.multiplyAlpha(clipFade), vpWidth, vpHeight);
            }
            cx += iw + 10f;
        }
    }
}
