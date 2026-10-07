package com.yuyuframe.launcheragent.apimixin.v1_8_9.entity;

import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.freelook.FreelookOffset189;
import net.minecraft.client.render.entity.EntityRenderDispatcher;

/**
 * Remet face à la caméra tout ce que le jeu oriente d'après
 * {@code EntityRenderDispatcher.yaw}/{@code pitch} : les étiquettes de nom
 * (celles de vanilla comme celles de {@code LabelBatch189}), les orbes
 * d'expérience, les projectiles dessinés à plat.
 *
 * <p>Vanilla recopie dans ces deux champs la rotation de l'entité caméra, ce
 * qui est faux dans deux cas :
 * <ul>
 *   <li>la vue de face (second F5) : il ajoute 180° au lacet mais oublie que
 *       la caméra regarde alors dans l'autre sens — le tangage doit être
 *       inversé, sinon l'étiquette penche à l'envers dès qu'on lève ou baisse
 *       la tête ;</li>
 *   <li>le freelook : la caméra tourne sans l'entité, voir
 *       {@link FreelookOffset189}.</li>
 * </ul>
 */
@YarnNamed
public final class LabelFacing189 {

    private LabelFacing189() {
    }

    /** À la sortie de {@code EntityRenderDispatcher.updateCamera}. */
    public static void afterVanilla(Object dispatcherObject) {
        EntityRenderDispatcher dispatcher = (EntityRenderDispatcher) dispatcherObject;
        dispatcher.yaw += FreelookOffset189.yaw;
        float pitch = dispatcher.pitch + FreelookOffset189.pitch;
        boolean frontView = dispatcher.options != null && dispatcher.options.perspective == 2;
        dispatcher.pitch = frontView ? -pitch : pitch;
    }
}
