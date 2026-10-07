package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.runtime.presence.PlayerStyle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

/**
 * « Mon pseudo » du module « Joueur » : l'étiquette de son propre joueur, que
 * vanilla ne dessine jamais ({@code LivingEntityRenderer.hasLabel} écarte
 * l'entité depuis laquelle on regarde).
 *
 * <p>Seulement en vue extérieure — à la première personne, son joueur n'est
 * dessiné qu'en poupée dans l'inventaire — et jamais sur cette poupée, même
 * en vue extérieure : elle passe par le même dessin d'entité que le monde,
 * d'où le compteur tenu par {@code InventoryDollMixin189}.
 */
@YarnNamed
public final class OwnLabel189 {

    /** Dessins de poupée en cours (inventaire, écran du cheval) ; lu et écrit sur le thread de rendu. */
    private static int dolls;

    private OwnLabel189() {
    }

    public static void dollBegins() {
        dolls++;
    }

    public static void dollEnds() {
        if (dolls > 0) {
            dolls--;
        }
    }

    /** Vrai : cette entité est notre joueur et son étiquette doit être dessinée. Faux : à vanilla de décider. */
    public static boolean wanted(Object entity) {
        if (!PlayerStyle.ownLabel || dolls > 0 || !(entity instanceof Entity)) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity != client.player || client.options.perspective == 0) {
            return false;
        }
        // Les deux conditions que vanilla garde pour toute étiquette : interface affichée (F1), joueur visible.
        return MinecraftClient.isHudEnabled() && !((Entity) entity).isInvisible();
    }
}
