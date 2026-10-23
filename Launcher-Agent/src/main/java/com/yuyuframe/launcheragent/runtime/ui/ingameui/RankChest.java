package com.yuyuframe.launcheragent.runtime.ui.ingameui;

import com.yuyuframe.launcheragent.apigraphic.UiRenderer;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiLighting;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiMeshProjector;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiModel;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiModelPose;
import com.yuyuframe.launcheragent.apigraphic.draw.mesh.UiView3D;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.ClientData;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

/**
 * Le coffre du placement : le vrai coffre du jeu, modèle et texture lus dans
 * la version qui tourne (pack de ressources compris), dessiné par la brique
 * 3D de l'API graphique. Le rang est dedans ; il s'ouvre au dixième duel.
 *
 * <p>Le couvercle et le loquet tournent ensemble autour de l'arête arrière,
 * comme dans le jeu ({@code ChestModel.setupAnim} : angle négatif autour de X).
 */
public final class RankChest {

    private RankChest() {
    }

    private static final String TEXTURE = "textures/entity/chest/normal.png";
    private static final String TEXTURE_KEY = "rank-chest";

    private static UiModel model;
    private static BufferedImage texture;
    private static boolean loaded;
    private static final UiModelPose POSE = new UiModelPose();

    /** Le coffre est-il disponible (modèle et texture lus) ? */
    public static boolean available() {
        load();
        return model != null && texture != null;
    }

    private static void load() {
        if (loaded) return;
        loaded = true;
        model = ClientData.vanillaModel("chest");
        try {
            byte[] bytes = ClientData.resourceBytes("minecraft", TEXTURE);
            if (bytes != null) texture = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (texture == null) LauncherLog.err("[RankChest] texture du coffre introuvable : " + TEXTURE);
        } catch (Throwable t) {
            LauncherLog.err("[RankChest] texture du coffre : " + t);
        }
    }

    /** Le centre du coffre fermé, dans le repère du modèle (pixels, Y vers le haut). */
    public static float[] center() {
        load();
        return model == null ? new float[]{ 8f, 7f, 8.5f } : model.center();
    }

    /**
     * Pose le couvercle : {@code openDegrees} positif = ouvert vers l'arrière
     * (90 = droit, au-delà il bascule en arrière).
     */
    public static UiModelPose pose(float openDegrees) {
        float angle = (float) -Math.toRadians(openDegrees);
        POSE.rotate("lid", angle, 0f, 0f).rotate("lock", angle, 0f, 0f);
        return POSE;
    }

    /** Dessine le coffre selon {@code view} ; {@code lighting} {@code null} = éclairage d'interface. */
    public static void draw(UiRenderer r, UiView3D view, float openDegrees, UiLighting lighting, float alpha, int vw, int vh) {
        if (!available()) return;
        r.drawModel(model, pose(openDegrees), TEXTURE_KEY, texture, view,
            lighting != null ? lighting : UiLighting.gui(), alpha, vw, vh);
    }

    /**
     * Où tombe à l'écran le milieu de la fente avant (le bord du couvercle qui
     * se soulève) — pour y poser la lueur qui s'échappe.
     */
    public static float[] slitPoint(UiView3D view, float openDegrees) {
        load();
        if (model == null) return UiMeshProjector.projectPoint(view, 8f, 10f, 15f);
        float[] p = UiMeshProjector.partPoint(model, pose(openDegrees), "lid", 8f, 0f, 14f);
        if (p == null) return UiMeshProjector.projectPoint(view, 8f, 10f, 15f);
        // À mi-chemin entre le bord du couvercle et le bord avant de la caisse.
        return UiMeshProjector.projectPoint(view, (p[0] + 8f) / 2f, (p[1] + 10f) / 2f, (p[2] + 15f) / 2f);
    }

    /**
     * Petit coffre fermé, centré sur {@code (cx, cy)}, d'environ {@code pixels}
     * de large — l'insigne « non classé ».
     */
    public static void icon(UiRenderer r, float cx, float cy, float pixels, float alpha, int vw, int vh) {
        if (!available()) return;
        float[] c = center();
        UiView3D view = new UiView3D().center(cx, cy).scale(pixels / 19f)
            .origin(c[0], c[1], c[2]).rotation(-0.42f, 0.36f, 0f).eyeDistance(70f);
        draw(r, view, 0f, null, alpha, vw, vh);
    }
}
