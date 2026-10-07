package com.yuyuframe.launcheragent.apimixin.v1_8_9.hud;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.entity.PresenceLabel189;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.font.TextRenderer;
import org.lwjgl.opengl.GL11;

/**
 * La ligne d'un joueur YuyuFrame dans la liste Tab : même fond, même logo et
 * mêmes couleurs que son étiquette au-dessus de la tête — c'est le même code
 * qui les dessine ({@link PlayerStyle}), ici posé à l'écran.
 *
 * <p>Deux temps, branchés par {@code PlayerListStyleMixin189} :
 * <ol>
 *   <li>{@link #reserve} quand vanilla demande le nom d'une ligne : celui d'un
 *       joueur à styliser revient précédé d'espaces. Vanilla mesure alors la
 *       colonne AVEC la place du logo, sans rien savoir de lui ;</li>
 *   <li>{@link #draw} quand vanilla dessine ce nom : on reconnaît la chaîne
 *       qu'on vient de rendre et on dessine à sa place.</li>
 * </ol>
 * Vanilla demande le nom d'une ligne juste avant de la dessiner : la dernière
 * chaîne rendue est donc toujours celle de la ligne en cours. Tout autre texte
 * de la liste (en-tête, pied, scores, joueur en spectateur dont le nom est
 * recomposé) n'est pas cette chaîne et part chez vanilla tel quel.
 */
@YarnNamed
public final class PlayerListStyle189 {

    private PlayerListStyle189() {
    }

    /** Trois espaces de 4 pixels : la place du logo et de son écart. */
    private static final String PAD = "   ";
    private static final float PAD_WIDTH = 12.0F;

    /** La dernière chaîne rendue pour un joueur à styliser, reconnue par identité. */
    private static String marked;
    private static boolean markedPadded;

    /** @return le nom à rendre à vanilla, ou {@code null} pour laisser le sien */
    public static String reserve(Object entry, String name) {
        if (name == null || !PresenceLabel189.styledEntry(entry)) {
            return null;
        }
        markedPadded = PlayerStyle.logo;
        // Une chaîne neuve dans les deux cas : c'est son identité qui la désigne au dessin.
        marked = markedPadded ? PAD + name : new String(name);
        return marked;
    }

    private static final class Screen implements PlayerStyle.Sink {
        float originX, originY;

        @Override
        public void vertex(float x, float y, int rgb, int hidden, int visible) {
            // À l'écran rien n'est « derrière un mur » : le fond n'a que son opacité cachée.
            int alpha = visible > 0 ? visible : hidden;
            GL11.glColor4f((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F, (rgb & 255) / 255.0F, alpha / 255.0F);
            GL11.glVertex3f(this.originX + x, this.originY + y, 0.0F);
        }
    }

    private static final Screen SCREEN = new Screen();

    /** À la place de {@code TextRenderer.drawWithShadow} dans le dessin de la liste. */
    public static int draw(Object renderer, String text, float x, float y, int color) {
        TextRenderer textRenderer = (TextRenderer) renderer;
        if (text != marked || text == null) {
            return textRenderer.drawWithShadow(text, x, y, color);
        }
        String name = markedPadded ? text.substring(PAD.length()) : text;
        float lead = markedPadded ? PAD_WIDTH : 0.0F;
        float now = PlayerStyle.time();
        int width = textRenderer.getStringWidth(name);

        boolean plate = PlayerStyle.plate != PlayerStyle.PLATE_VANILLA;
        if (plate || markedPadded) {
            boolean blending = GL11.glIsEnabled(GL11.GL_BLEND);
            // Lus puis rétablis tels quels : ces deux états ne passent pas par
            // GlStateManager ici, son cache doit rester juste.
            int shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);
            boolean culling = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            GlStateManager.disableTexture();
            GlStateManager.enableBlend();
            GlStateManager.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glDisable(GL11.GL_CULL_FACE);

            SCREEN.originX = x;
            SCREEN.originY = y;
            GL11.glBegin(GL11.GL_QUADS);
            if (plate) {
                // Une ligne de la liste fait 8 pixels et la suivante commence au 9e : le fond ne déborde pas.
                PlayerStyle.plate(SCREEN, -1.0F, lead + width + 1.0F, -0.5F, 8.5F, now);
            }
            if (markedPadded) {
                PlayerStyle.logo(SCREEN, 1.0F, now);
            }
            GL11.glEnd();

            GL11.glShadeModel(shadeModel);
            if (culling) {
                GL11.glEnable(GL11.GL_CULL_FACE);
            }
            GlStateManager.enableTexture();
            if (!blending) {
                GlStateManager.disableBlend();
            }
            // Des couleurs posées sommet par sommet laissent la couleur courante
            // indéfinie : on la repose en dur, le cache pouvant la croire blanche.
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        return drawName(textRenderer, name, x + lead, y, color, width, now);
    }

    /**
     * Le pseudo, lettre par lettre, pour que chacune prenne sa couleur dans le
     * dégradé. Les codes du serveur sont suivis au passage : une lettre qu'il a
     * colorée garde sa couleur, sauf si le réglage demande de tout recolorer.
     */
    private static int drawName(TextRenderer textRenderer, String name, float x, float y, int color, int width, float now) {
        if (PlayerStyle.textMode == PlayerStyle.TEXT_UNCHANGED || width <= 0) {
            return textRenderer.drawWithShadow(name, x, y, color);
        }
        String colorCode = "";
        StringBuilder styleCodes = new StringBuilder();
        int serverRgb = 0xFFFFFF;
        float at = x;
        int length = name.length();
        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            if (c == '§' && i + 1 < length) {
                char code = Character.toLowerCase(name.charAt(++i));
                if (code == 'r') {
                    colorCode = "";
                    styleCodes.setLength(0);
                    serverRgb = 0xFFFFFF;
                } else if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
                    // Une couleur efface les styles en cours, comme dans le jeu.
                    colorCode = "§" + code;
                    styleCodes.setLength(0);
                    serverRgb = code == 'f' ? 0xFFFFFF : 0;
                } else {
                    styleCodes.append('§').append(code);
                }
                continue;
            }
            boolean recolor = PlayerStyle.recolors(serverRgb);
            String piece = (recolor ? "" : colorCode) + styleCodes + c;
            int pieceColor = recolor ? 0xFF000000 | PlayerStyle.textColor((at - x) / width, now) : color;
            textRenderer.drawWithShadow(piece, at, y, pieceColor);
            at += textRenderer.getStringWidth(piece);
        }
        return (int) at;
    }
}
