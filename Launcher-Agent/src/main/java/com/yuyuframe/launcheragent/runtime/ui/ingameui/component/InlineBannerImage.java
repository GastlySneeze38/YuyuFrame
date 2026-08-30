package com.yuyuframe.launcheragent.runtime.ui.ingameui.component;

import com.yuyuframe.launcheragent.runtime.content.ContentBridge;
import com.yuyuframe.launcheragent.runtime.content.ModrinthJson;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAnimatedFloat;
import com.yuyuframe.launcheragent.apigraphic.anim.UiAsyncFade;
import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.input.UiInputPoller;
import com.yuyuframe.launcheragent.apigraphic.core.UiRemoteImage;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.layout.LayoutSolver;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyLayoutResult;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyNode;
import com.yuyuframe.launcheragent.apigraphic.layout.TaffyStyle;
import com.yuyuframe.launcheragent.apigraphic.core.UiWidget;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.UiScreenBase;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiButton;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiLabel;
import com.yuyuframe.launcheragent.runtime.ui.ingameui.component.UiScrollContainer;
import com.yuyuframe.launcheragent.apigraphic.core.UiTheme;
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
/** Bannière pleine largeur (capture d'écran...) — ratio d'aspect TOUJOURS préservé (jamais de crop/étirement), jamais agrandie au-delà de sa taille source (évite le flou d'une petite image forcée en grand). */
public final class InlineBannerImage extends UiWidget {
    private final String url;
    private final String cacheKey; // précalculé une fois — voir ImgRef.cacheKey pour le même motif
    // Fondu d'entrée ajouté (voir audit runtime/ui/ : apparition brute
    // dès que le fetch HTTP termine) — une instance par bannière, markReady()
    // idempotent, voir javadoc de UiAsyncFade.
    private final UiAsyncFade fade = new UiAsyncFade();

    public InlineBannerImage(float x, float y, float w, float h, String url) {
        super(x, y, w, h);
        this.url = url;
        this.cacheKey = "banner:" + url;
    }

    @Override
    public void draw(UiRenderer renderer, double mouseX, double mouseY, int vpWidth, int vpHeight) {
        BufferedImage img = UiRemoteImage.get(url);
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) {
            renderer.drawRoundedRect(x, y, x + w, y + h, UiTheme.RADIUS_SM, UiTheme.PANEL_BG_ALT.multiplyAlpha(clipFade), vpWidth, vpHeight);
            return;
        }
        fade.markReady();
        float scale = Math.min(Math.min(w / img.getWidth(), h / img.getHeight()), 1f);
        float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
        renderer.drawIcon(cacheKey, img, x + (w - dw) / 2f, y + (h - dh) / 2f, dw, dh,
            fade.alpha() * clipFade, vpWidth, vpHeight);
    }
}
