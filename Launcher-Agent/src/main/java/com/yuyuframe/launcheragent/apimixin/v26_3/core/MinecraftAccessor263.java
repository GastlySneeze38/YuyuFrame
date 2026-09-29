package com.yuyuframe.launcheragent.apimixin.v26_3.core;

import net.minecraft.client.MouseHandler;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.Options;
import net.minecraft.client.User;
import com.mojang.blaze3d.platform.TextInputManager;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor Mixin (Sponge) pour 3 champs privés de {@code Minecraft} —
 * remplace la réflexion manuelle de l'ancien {@code
 * mixin.client.v26_1.GlobalUiRenderBridge263} (getDeclaredField +
 * setAccessible, cache manuel de Field) par des getters synthétisés
 * directement par Mixin au tissage : ZÉRO réflexion au runtime pour ces 3
 * accès (audit ROADMAP-agent.md §3.3 — "presque plus de réflexion en
 * 26.1.2, tout passe par apimixin").
 *
 * Non déclaré dans {@code MixinHookPointRegistry} pour l'instant — voir
 * {@code core.GlobalUiRenderBridge263} (même dossier), équivalent apimixin de
 * l'ancien pont du package {@code mixin/} (supprimé le 2026-09-16).
 *
 * Noms de champs vérifiés via javap sur le jar client 26.1.2 réel (voir
 * javadoc historique de {@code mixin.client.v26_1.GlobalUiRenderBridge263}) :
 * {@code window}, {@code screen} (RENOMMÉ depuis "currentScreen" en Yarn),
 * {@code mouseHandler}.
 *
 * Une instance de {@code Minecraft} (obtenue via {@code Minecraft.getInstance()})
 * implémente CETTE interface une fois le Mixin tissé — cast direct
 * {@code (MinecraftAccessor263) mc}, idiome standard Sponge Mixin.
 *
 * <p>26.2 : plus de champ {@code screen} ici — l'écran courant est passé dans
 * le nouveau {@code Gui}, voir {@link GuiAccessor263} (via {@link #la$gui()}).
 */
@Mixin(targets = "net.minecraft.client.Minecraft")
public interface MinecraftAccessor263 {

    @Accessor("window")
    Window la$window();

    @Accessor("mouseHandler")
    MouseHandler la$mouseHandler();

    /**
     * 26.3 : saisie de texte SDL, que {@code SdlNativeInput263} démarre pour
     * les champs de l'agent — voir {@link com.yuyuframe.launcheragent.apimixin.v26_3.input.TextInputManagerInvoker263}.
     * Champ privé {@code textInputManager} (javap).
     */
    @Accessor("textInputManager")
    TextInputManager la$textInputManager();

    /** Ajouté pour {@code FreelookModule} (forçage/restauration de la vue 3e personne) — voir sa javadoc. */
    @Accessor("options")
    Options la$options();

    /**
     * Ajouté pour {@code FpsModule}. {@code static} REQUIS : le champ l'est
     * ({@code private static int fps}, vérifié au javap sur le jar 26.1.2), et
     * Sponge Mixin exige que l'accesseur ait la même staticité que sa cible.
     * Tant que cette méthode était déclarée comme les autres (méthode
     * d'instance), Mixin avertissait à chaque lancement :
     * <pre>@Accessor[FIELD_GETTER]::la$fps()I should be static as its target is</pre>
     *
     * Le corps n'est jamais exécuté — Mixin le remplace au tissage ; il rend
     * juste bruyant le cas où ce mixin ne serait pas appliqué, plutôt que de
     * renvoyer un 0 silencieux et faux. Même idiome que
     * {@code RenderPipelinesAccessor263#la$guiTextured()}.
     *
     * Appel : {@code MinecraftAccessor263.la$fps()}, sans instance.
     */
    @Accessor("fps")
    static int la$fps() { throw new AssertionError("MinecraftAccessor263 non tissé"); }

    /** Ajouté pour {@code ChatEnhancementsModule} (pseudo local, détection de mention). */
    @Accessor("user")
    User la$user();

    /**
     * Ajouté pour {@code ChatEnhancementsModule} (2026-08-26, §22) — {@code Gui} est public (vérifié javap), routé ici pour rester sur UNE seule surface d'accès à l'état interne de {@code Minecraft}, voir la javadoc de classe.
     *
     * <p>26.2 : ce {@code Gui} est le gestionnaire d'écrans, plus le HUD —
     * le HUD s'obtient par {@code GuiAccessor263.la$hud()}.
     */
    @Accessor("gui")
    Gui la$gui();

    /**
     * Joueur courant — {@code null} hors partie (écran titre, déconnexion).
     *
     * <p>{@code player} est un champ PUBLIC sur 26.1.2, donc un accès direct
     * compilerait aussi. Il passe quand même par un accessor, sur demande
     * explicite (2026-08-27) : la portabilité multiversion vient de ce que
     * TOUS les accès à l'état du jeu traversent une seule interface par
     * bracket. Le jour où un champ change de nom ou de visibilité sur une
     * autre version, seul l'accessor de CE bracket bouge — aucun appelant.
     * Mélanger accès directs et accessors ferait perdre exactement cette
     * propriété.
     */
    @Accessor("player")
    LocalPlayer la$player();

    /**
     * Gestionnaire de ressources — la SEULE voie qui applique les resource
     * packs. Lire une texture depuis le classloader rend celle du jar,
     * c'est-à-dire la version vanilla, en ignorant tout pack installé (voir
     * {@code SaturationModule}, qui charge par ici ses icônes de faim, de
     * cœur et son atlas AppleSkin).
     *
     * <p>Type de retour {@code ReloadableResourceManager} et non
     * {@code ResourceManager} : Mixin synthétise le getter au type EXACT du
     * champ.
     */
    @Accessor("resourceManager")
    ReloadableResourceManager la$resourceManager();

    /** Monde client courant — {@code null} hors partie. Même raison que {@link #la$player()} de passer par un accessor malgré un champ public. */
    @Accessor("level")
    ClientLevel la$level();

    /**
     * 26.2 : la cible de rendu principale n'est plus exposée par
     * {@code Minecraft.getMainRenderTarget()} (supprimé) mais par
     * {@code GameRenderer.mainRenderTarget} — voir
     * {@code render.GameRendererAccessor263#la$mainRenderTarget()}. Champ
     * {@code public final GameRenderer gameRenderer} (vérifié par javap).
     */
    @Accessor("gameRenderer")
    GameRenderer la$gameRenderer();
}
