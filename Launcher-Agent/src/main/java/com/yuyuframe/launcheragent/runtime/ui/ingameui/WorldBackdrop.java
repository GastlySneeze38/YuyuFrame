package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.widget.UiDrawable;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Le « faux monde » derrière les écrans du client quand il n'y a pas de monde.
 *
 * <p>Ces écrans sont pensés pour s'ouvrir en jeu : leur fond est le monde,
 * flouté et assombri, et leurs panneaux de verre le laissent deviner. Ouverts
 * depuis l'écran-titre, il n'y a rien derrière. On y pose donc un paysage —
 * la première face du panorama de l'écran-titre, une vraie vue du jeu lue
 * dans ses ressources (elle suit le pack actif) — étirée pour couvrir la
 * fenêtre. L'éditeur de HUD, dont le fond est presque transparent, y gagne un
 * décor sur lequel placer ses éléments.
 *
 * <p>Dessiné avant le flou du verre de la frame
 * ({@link UiRenderer#frameBackdrop}), et seulement quand l'écran courant est
 * un écran du client hors d'un monde.
 */
public final class WorldBackdrop {

    private WorldBackdrop() {
    }

    private static final String KEY = "yf-backdrop:panorama_0";

    private static BufferedImage image;
    /** Nombre d'essais de lecture restants : les ressources du jeu peuvent ne pas être prêtes à la toute première image. */
    private static int attempts = 20;

    /** À appeler une fois : inscrit le décor auprès du moteur. */
    public static void install() {
        UiRenderer.frameBackdrop = WorldBackdrop::draw;
    }

    private static void draw(UiRenderer renderer, int vw, int vh) {
        if (PlayerData.inGame() || !(ClientData.screenObject() instanceof UiDrawable)) {
            return;
        }
        BufferedImage landscape = landscape();
        if (landscape == null || vw <= 0 || vh <= 0) {
            return;
        }
        // Couvrir la fenêtre sans déformer : l'image est carrée, son côté est le plus grand des deux.
        float side = Math.max(vw, vh * (float) landscape.getWidth() / Math.max(1, landscape.getHeight()));
        float height = side * landscape.getHeight() / Math.max(1, landscape.getWidth());
        renderer.drawIcon(KEY, landscape, (vw - side) / 2f, (vh - height) / 2f, side, height, vw, vh);
    }

    private static BufferedImage landscape() {
        if (image != null || attempts <= 0) {
            return image;
        }
        attempts--;
        try {
            byte[] bytes = ClientData.resourceBytes("minecraft", "textures/gui/title/background/panorama_0.png");
            if (bytes != null) {
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            }
        } catch (Throwable error) {
            attempts = 0;
            LauncherLog.err("[WorldBackdrop] panorama illisible", error);
        }
        return image;
    }
}
