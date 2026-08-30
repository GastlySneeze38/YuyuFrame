package com.yuyuframe.launcheragent.apigraphic.render.vanillagui;

import com.yuyuframe.launcheragent.apigraphic.core.UiColor;
import com.yuyuframe.launcheragent.apigraphic.core.UiFont;
import com.yuyuframe.launcheragent.apigraphic.render.blaze3d.Blaze3DVanillaProbe;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

/**
 * <b>Échafaudage temporaire — étape 1 de la refonte du rendu (2026-08-30).</b>
 * À SUPPRIMER une fois la question tranchée.
 *
 * <h2>Ce qu'on cherche à savoir</h2>
 *
 * <ol>
 *   <li><b>Le z-order suit-il le point d'émission ?</b> Les rectangles sont
 *       émis depuis un hook d'extraction du HUD, donc tôt dans la GUI, bien
 *       AVANT le chat. Ils sont dessinés exprès par-dessus la zone du chat :
 *       s'ils passent DERRIÈRE les messages, la refonte tient.</li>
 *   <li><b>Vanilla accepte-t-il un pipeline shader à nous ?</b> Rectangle de
 *       GAUCHE = pipeline vanilla, rectangle de DROITE = le nôtre. Deux
 *       inconnues séparées : si seul le gauche apparaît, le z-order est bon et
 *       c'est notre pipeline qui est refusé.</li>
 * </ol>
 *
 * <h2>Historique — première tentative infructueuse (v918)</h2>
 *
 * La sonde était accrochée à {@code HUD_EXTRACT_ARMOR}. Le mixin était bien
 * tissé, mais {@code Gui.extractArmor} n'est appelé que lorsque le joueur
 * PORTE de l'armure : sans armure, le handler ne tourne jamais et rien
 * n'apparaissait. Déplacée sur {@code HUD_EXTRACT_CROSSHAIR}, appelé à chaque
 * frame en vue subjective, sans condition.
 *
 * <p>Deuxième leçon de cette tentative : tous les points de sortie anticipée
 * étaient MUETS, donc l'absence de rectangle ne disait pas OÙ ça s'arrêtait.
 * Chacun journalise désormais sa raison, une fois.
 */
public final class VanillaGuiProbe {
    private VanillaGuiProbe() {}

    /** Passer à {@code false} pour éteindre la sonde sans retirer le câblage. */
    // Éteinte depuis la v929 : les quatre primitives sont validées (quad
    // vanilla, quad pipeline maison, rect arrondi format maison, texte SDF)
    // et le HUD réel est désormais émis par cette voie — les rectangles de
    // test n'apporteraient plus que du bruit à l'écran. Toute la classe est
    // à supprimer une fois le portage terminé.
    public static boolean ENABLED = false;

    /**
     * Deuxième moitié de la sonde (rectangle magenta, pipeline maison) —
     * <b>DÉSACTIVÉE</b> depuis le crash de la v919.
     *
     * <h3>Ce que la v919 a appris</h3>
     *
     * Le rectangle cyan (pipeline vanilla) s'est bien affiché, et le chat est
     * passé PAR-DESSUS : <b>le z-order suit bien le point d'émission</b>, la
     * question de fond est tranchée. Mais le client a crashé sur :
     *
     * <pre>IllegalStateException: Missing elements in vertex: UV0, UV2
     *   at ColoredRectangleRenderState.buildVertices
     *   at GuiRenderer.addElementToMesh</pre>
     *
     * C'est exactement le risque annoncé : {@code ShaderPipelineFactory}
     * copie le format de sommet de {@code GUI_TEXT}, qui est un pipeline
     * TEXTURÉ (Position + Color + UV0 + UV2 — le message d'erreur le confirme),
     * alors que {@code ColoredRectangleRenderState} n'écrit que Position et
     * Color.
     *
     * <p>Détail qui a son importance : le log montre {@code émis=false}, donc
     * {@code fillWithPipeline} n'a JAMAIS été appelé (la précompilation avait
     * échoué avant, {@code Blaze3DCore.mGetDevice} étant encore null). La
     * seule chose que notre code ait faite est donc de CONSTRUIRE le pipeline
     * — et ça a suffi à casser le passage de maillage de vanilla. Cette
     * constante coupe la construction elle-même, pas seulement l'émission.
     *
     * <p>Ne la remettre à {@code true} qu'une fois le format de sommet réglé
     * (étape 1b) : emprunter celui d'un pipeline vanilla NON texturé au lieu
     * de {@code GUI_TEXT}, ou déclarer un {@code VertexFormat} à nous.
     */
    public static boolean TEST_OWN_PIPELINE = true;

    private static final UiColor VANILLA_RECT = new UiColor(0, 220, 255, 170);
    /** Vert franc — troisième teinte reconnaissable, pour le rect arrondi. */
    private static final UiColor ROUNDED_RECT = new UiColor(120, 255, 90, 190);
    /** Texte posé sur le rect vert — noir pour trancher sur lui. */
    private static final UiColor TEXT_COLOR = new UiColor(10, 10, 10, 255);

    private static boolean installed;
    private static boolean resultLogged;
    private static String lastReason;

    /**
     * Enregistre la sonde sur {@code HUD_EXTRACT_CROSSHAIR}. Le handler
     * renvoie TOUJOURS {@code false} : il ne doit jamais annuler le rendu du
     * crosshair vanilla, il ne fait que s'insérer dans la frame pour dessiner.
     *
     * <p>Appelé depuis le constructeur d'{@code ArmorDurabilityModule} —
     * n'importe quel module ferait l'affaire, celui-ci est simplement déjà
     * touché par cet échafaudage.
     */
    public static void install() {
        if (installed) return;
        installed = true;
        VanillaHookRegistry.register(HookPoint.HUD_EXTRACT_CROSSHAIR, ctx -> {
            drawFrom(ctx);
            return false;
        });
        LauncherLog.info("[VanillaGuiProbe] sonde enregistrée sur HUD_EXTRACT_CROSSHAIR");
    }

    private static void reason(String r) {
        if (r.equals(lastReason)) return;
        lastReason = r;
        LauncherLog.info("[VanillaGuiProbe] " + r);
    }

    private static void drawFrom(Object hookContext) {
        if (!ENABLED) return;
        try {
            if (hookContext == null) { reason("contexte null — le hook ne transporte rien"); return; }
            if (!VanillaGuiLayer.isAvailable(hookContext)) {
                reason("contexte inexploitable, classe reçue = " + hookContext.getClass().getName());
                return;
            }

            int h = VanillaGuiLayer.guiHeight(hookContext);
            int w = VanillaGuiLayer.guiWidth(hookContext);
            if (h <= 0) { reason("guiHeight=" + h + " (inattendu)"); return; }

            // Zone du chat : bas-gauche. Coordonnées en pixels GUI, origine en
            // HAUT à gauche (convention vanilla) — voir VanillaGuiLayer.fill.
            int top = h - 70, bottom = h - 30;

            boolean vanillaOk = VanillaGuiLayer.fill(hookContext, 4, top, 120, bottom, VANILLA_RECT);

            // Volontairement court-circuité tant que TEST_OWN_PIPELINE est
            // faux : on ne CONSTRUIT même pas le pipeline maison, sa seule
            // construction ayant suffi à faire crasher la v919 (voir sa
            // javadoc). Un seul inconnu à la fois.
            boolean compiled = false, ownOk = false;
            if (TEST_OWN_PIPELINE) {
                compiled = Blaze3DVanillaProbe.ensureCompiled();
                ownOk = compiled && VanillaGuiLayer.fillWithPipeline(hookContext,
                    Blaze3DVanillaProbe.pipeline(), 128, top, 244, bottom);
            }

            // Troisième rectangle : RECT ARRONDI, notre GuiElementRenderState
            // avec notre VertexFormat maison. C'est la primitive qui décide si
            // tout le HUD peut être porté — un quad plein ne prouvait que le
            // z-order et l'acceptation d'un pipeline.
            boolean roundedOk = VanillaGuiLayer.roundedRect(hookContext,
                252, top, 368, bottom, 10f, ROUNDED_RECT);

            // Quatrième test : TEXTE SDF du moteur, dernière primitive à
            // valider avant de pouvoir basculer tout le HUD. Posé SUR le rect
            // arrondi, donc émis après lui — l'ordre d'insertion fait le
            // z-order, exactement comme dans l'état de GUI de vanilla.
            boolean textOk = VanillaGuiLayer.text(hookContext, UiFont.BOLD, "Yuyu 123",
                262, bottom - 14, 0.5f, TEXT_COLOR);

            if (!resultLogged) {
                resultLogged = true;
                LauncherLog.info("[VanillaGuiProbe] émission OK — gui=" + w + "x" + h
                    + ", rect vanilla=" + vanillaOk
                    + ", pipeline maison testé=" + TEST_OWN_PIPELINE
                    + " compilé=" + compiled + " émis=" + ownOk
                    + ", RECT ARRONDI (format maison)=" + roundedOk
                    + ", TEXTE SDF=" + textOk);
            }
        } catch (Throwable t) {
            reason("exception: " + t);
        }
    }
}
