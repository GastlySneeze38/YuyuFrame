package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.module.ArmorDurabilityModule;
import com.yuyuframe.launcheragent.runtime.module.ChatEnhancementsModule;
import com.yuyuframe.launcheragent.runtime.module.CoordsModule;
import com.yuyuframe.launcheragent.runtime.module.CrosshairModule;
import com.yuyuframe.launcheragent.runtime.module.DiagonalSwordModule;
import com.yuyuframe.launcheragent.runtime.module.FovModule;
import com.yuyuframe.launcheragent.runtime.module.FpsModule;
import com.yuyuframe.launcheragent.runtime.module.FullbrightModule;
import com.yuyuframe.launcheragent.runtime.module.HurtCamModule;
import com.yuyuframe.launcheragent.runtime.module.KeystrokesModule;
import com.yuyuframe.launcheragent.runtime.module.LowHealthTintModule;
import com.yuyuframe.launcheragent.runtime.module.MumbleLinkModule;
import com.yuyuframe.launcheragent.runtime.module.OldBowModule;
import com.yuyuframe.launcheragent.runtime.module.OldConsumeModule;
import com.yuyuframe.launcheragent.runtime.module.OldItemRotationsModule;
import com.yuyuframe.launcheragent.runtime.module.PingModule;
import com.yuyuframe.launcheragent.runtime.module.PotionEffectsModule;
import com.yuyuframe.launcheragent.runtime.module.SneakRampModule;
import com.yuyuframe.launcheragent.runtime.module.SwingSpeedModule;
import com.yuyuframe.launcheragent.runtime.module.SwingWhileBlockingModule;
import com.yuyuframe.launcheragent.runtime.module.ToggleSneakModule;
import com.yuyuframe.launcheragent.runtime.module.ToggleSprintModule;
import com.yuyuframe.launcheragent.runtime.module.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.module.ZoomModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.BorderlessWindowModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.CachedFancyCloudsModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.ChunkBuilderThreadsModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.EntityBackfaceCullingModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.LabelRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.LowAnimationTickModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.ParticleRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.PlayerBackfaceCullingModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.TileEntityRenderDistanceModule;
import com.yuyuframe.launcheragent.runtime.module.optimodule.UnstackedItemsModule;
import com.yuyuframe.launcheragent.runtime.ui.graphicapi.UiRenderer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Registre global des modules — {@link com.yuyuframe.launcheragent.runtime.ui.ingameui.UiMainMenuScreen}
 * construit sa grille de cartes UNIQUEMENT à partir de {@link #all()}, plus
 * aucune donnée factice codée en dur dans l'écran lui-même : un nouveau
 * module s'ajoute ICI (ou s'auto-enregistre via {@link #register}), jamais
 * dans le code de l'écran.
 *
 * Chargée paresseusement (comme toute classe Java) au premier accès — forcée
 * dès la première frame par GlobalUiRenderMixin/GlobalUiRenderMixin189, pour
 * que les éléments HUD des modules intégrés soient déjà présents même si le
 * joueur n'a jamais ouvert le menu "YuyuFrame".
 *
 * {@link #tickAll()}/{@link #renderOverlayAll} sont aussi appelés
 * génériquement depuis ces mêmes Mixin, UNE FOIS pour tous les modules — un
 * futur module qui a besoin d'une logique de jeu continue (voir FovModule)
 * ou d'un rendu plein écran (voir LowHealthTintModule) surcharge juste
 * {@link LauncherModule#onTick()}/{@link LauncherModule#onRenderOverlay},
 * jamais besoin de retoucher le Mixin.
 */
public final class ModuleRegistry {
    private ModuleRegistry() {}

    private static final List<LauncherModule> MODULES = new ArrayList<>();
    private static final List<ModuleGroup> GROUPS = new ArrayList<>();

    /**
     * 1.16.5 (et plus largement le bracket "B", voir VersionBracketRegistry —
     * même prédicat {@code startsWith("1.16")}) exclut certains modules dont
     * la logique ne vit QUE dans des Mixins {@code *189} (1.8.9), jamais
     * portée ici : les afficher comme des cartes cliquables qui ne font rien
     * serait trompeur. Exclus (voir historique de session, audit demandé par
     * l'utilisateur) :
     *   - ToggleSprint/ToggleSneak/Fov : REDONDANTS avec des réglages vanilla
     *     natifs déjà présents en 1.13-1.16.5 (Contrôles: Sprint/Sneak
     *     Maintenir/Basculer ; curseur FOV vidéo).
     *   - Les 6 "animations 1.7" (Swing/Diagonal/ItemRotations/
     *     SwingWhileBlocking/OldBow/OldConsume) : PAS redondants avec du
     *     vanilla (ils restaurent des mécaniques retirées par la mise à jour
     *     combat 1.9+, toujours pertinents en 1.16.5), mais leur Mixin
     *     d'implémentation reste 1.8.9 uniquement pour l'instant — à
     *     réintégrer ici le jour où un Mixin équivalent existe pour ce bracket.
     * {@code SneakRampModule} n'est PAS dans cette liste (recrée juste une
     * sensation, catégorisé à part lors de l'audit) — reste enregistré
     * partout, y compris en 1.16.5.
     *
     * Élargi aux brackets "C" (1.20.4) et "D" (1.21.4, voir
     * VersionBracketRegistry) : mêmes réglages vanilla natifs (FOV/Sprint/
     * Sneak, hurt cam) présents depuis la "Flattening" (~1.13) et toujours là
     * sur ces brackets, et les mêmes 7 Mixins "animations 1.7" restent
     * 1.8.9-only — donc les mêmes exclusions s'appliquent partout. Le nom
     * {@code IS_1_16} n'a pas été renommé (trop de commentaires y
     * référeraient encore) mais couvre bien TOUS ces brackets malgré son nom.
     */
    private static final boolean IS_1_16 = System.getProperty("launcheragent.mcVersion", "").startsWith("1.16")
        || "1.20.4".equals(System.getProperty("launcheragent.mcVersion", ""))
        || "1.21.4".equals(System.getProperty("launcheragent.mcVersion", ""));

    static {
        register(new FpsModule());
        register(new PingModule());
        register(new CoordsModule());
        register(new KeystrokesModule());
        register(new PotionEffectsModule());
        register(new ArmorDurabilityModule());
        register(new LowHealthTintModule());
        if (!IS_1_16) register(new FovModule());
        // Enregistré JUSTE APRÈS FovModule — tickAll() itère MODULES dans
        // l'ordre d'enregistrement, donc si les deux sont actifs, le zoom
        // s'applique EN DERNIER chaque frame et n'est jamais écrasé par le
        // FOV permanent de FovModule (voir ZoomModule pour le détail).
        register(new ZoomModule());
        // Exclu depuis 1.13+ (voir IS_1_16 plus haut) sur demande explicite de
        // l'utilisateur : l'effet de secousse caméra à la prise de dégâts est
        // désormais natif en vanilla à partir de ce bracket — carte redondante sinon.
        if (!IS_1_16) register(new HurtCamModule());
        if (!IS_1_16) register(new ToggleSprintModule());
        if (!IS_1_16) register(new ToggleSneakModule());
        if (!IS_1_16) register(new SwingSpeedModule());
        if (!IS_1_16) register(new DiagonalSwordModule());
        if (!IS_1_16) register(new OldItemRotationsModule());
        if (!IS_1_16) register(new SwingWhileBlockingModule());
        if (!IS_1_16) register(new OldBowModule());
        if (!IS_1_16) register(new OldConsumeModule());
        // Ajouté à la liste d'exclusion 1.16.5 sur demande explicite de
        // l'utilisateur ("enlève-les TOUS") — initialement laissé de côté
        // lors de l'audit (recrée juste une sensation, pas un vrai portage
        // 1.7), mais reste visuellement groupé sous "Animations 1.7" dans
        // l'UI, donc traité pareil que les 6 autres.
        if (!IS_1_16) register(new SneakRampModule());
        register(new CrosshairModule());
        register(new FullbrightModule());
        register(new WorldTimeModule());
        register(new ChatEnhancementsModule());
        register(new UnstackedItemsModule());
        register(new PlayerBackfaceCullingModule());
        register(new EntityBackfaceCullingModule());
        register(new LowAnimationTickModule());
        register(new TileEntityRenderDistanceModule());
        register(new ChunkBuilderThreadsModule());
        register(new CachedFancyCloudsModule());
        register(new LabelRenderDistanceModule());
        register(new ParticleRenderDistanceModule());
        register(new BorderlessWindowModule());
        register(new MumbleLinkModule());

        // Regroupement demandé — voir ModuleGroup : purement de la
        // présentation, les modules ci-dessus restent enregistrés
        // individuellement juste au-dessus (tickAll/renderOverlayAll/persistance
        // inchangés), seul UiMainMenuScreen les affiche fusionnés sous une
        // carte au lieu d'une par module.
        // nonNull() — certains membres ci-dessous ne sont PAS enregistrés sur
        // 1.16.5 (voir IS_1_16 plus haut) : get(id) renvoie alors null, qu'il
        // faut filtrer avant de construire le groupe (sinon carte "vide"
        // cassée dans l'UI).
        List<LauncherModule> comfortMembers = nonNull(get("fov"), get("zoom"), get("hurt-cam"), get("toggle-sprint"), get("toggle-sneak"));
        if (!comfortMembers.isEmpty()) {
            GROUPS.add(new ModuleGroup("comfort", "Confort visuel", "FOV, Zoom, Hurt Cam, Sprint/Sneak", comfortMembers));
        }
        // Groupe entièrement exclu sur 1.16.5 (les 7 membres y sont tous
        // exclus, voir IS_1_16) — pas de carte vide affichée dans ce cas.
        List<LauncherModule> legacyMembers = nonNull(get("swing-speed-1-7"), get("diagonal-sword"), get("old-item-rotations"),
            get("swing-while-blocking"), get("old-bow"), get("old-consume"), get("sneak-ramp-1-7"));
        if (!legacyMembers.isEmpty()) {
            GROUPS.add(new ModuleGroup("legacy-1-7", "Animations 1.7", "Swing, item, arc, manger/boire, sneak", legacyMembers));
        }
        // Optimisations FPS (voir mixin/.../optimodule et runtime/module/optimodule) —
        // portage de features de PolyPatcher (mod d'optimisation 1.8.9 open source),
        // pas de dépendance sur PolyPatcher lui-même, juste la même idée en Mixin natif.
        List<LauncherModule> optimisationMembers = Arrays.asList(get("unstacked-items"), get("player-backface-culling"),
            get("entity-backface-culling"), get("low-animation-tick"), get("tile-entity-render-distance"),
            get("chunk-builder-threads"), get("cached-fancy-clouds"), get("label-render-distance"),
            get("particle-render-distance"), get("borderless-window"));
        // Onglets regroupés — demandé explicitement pour réduire le nombre
        // d'onglets ET la longueur de leurs noms dans la sous-sidebar (les
        // noms complets des modules débordaient de la largeur des onglets,
        // voir UiModGroupConfigScreen) : les 2 modules de culling face
        // arrière (joueur/entités) et les 3 modules de distance de rendu
        // (tile entities/labels/particules) partagent maintenant chacun un
        // seul onglet au lieu d'un par module — chaque module GARDE son
        // propre toggle d'activation et ses réglages annotés, juste empilés
        // à la suite les uns des autres dans le même onglet (voir
        // UiModGroupConfigScreen.buildLayout()). Les modules restants gardent
        // un onglet dédié (Tab à un seul membre).
        GROUPS.add(new ModuleGroup("optimisations", "Optimisations", "Gains FPS ciblés", optimisationMembers,
            Arrays.asList(
                new ModuleGroup.Tab("Items non empilés", Collections.singletonList(get("unstacked-items"))),
                new ModuleGroup.Tab("Culling face arrière", Arrays.asList(get("player-backface-culling"), get("entity-backface-culling"))),
                new ModuleGroup.Tab("Animations réduites", Collections.singletonList(get("low-animation-tick"))),
                new ModuleGroup.Tab("Distance de rendu", Arrays.asList(get("tile-entity-render-distance"), get("label-render-distance"), get("particle-render-distance"))),
                new ModuleGroup.Tab("Threads de construction", Collections.singletonList(get("chunk-builder-threads"))),
                new ModuleGroup.Tab("Nuages Fancy en cache", Collections.singletonList(get("cached-fancy-clouds"))),
                new ModuleGroup.Tab("Fenêtre sans bordure", Collections.singletonList(get("borderless-window")))
            )));
    }

    public static void register(LauncherModule module) {
        MODULES.add(module);
        // Écrase les valeurs par défaut (fixées dans le constructeur du
        // module, juste avant ce point) avec la config persistée — voir
        // HudConfigStore. Placé ICI (pas dans le bloc static{}) pour que tout
        // futur module enregistré dynamiquement (pas seulement les 9 modules
        // intégrés) bénéficie aussi de la persistance sans y penser.
        HudConfigStore.applyTo(module);
        // applyTo() écrit les champs @Config* par réflexion mais n'appelle
        // jamais onConfigChanged() — un module qui, comme KeystrokesModule,
        // ne lit sa config qu'à travers onConfigChanged() (ex: pour recopier
        // vers un objet Renderer statique séparé) ignorait donc la valeur
        // persistée jusqu'au premier changement manuel dans l'UI pendant la
        // session (régression constatée : option désactivée en config mais
        // toujours affichée au lancement).
        module.onConfigChanged();
    }

    public static List<LauncherModule> all() { return Collections.unmodifiableList(MODULES); }

    public static List<ModuleGroup> groups() { return Collections.unmodifiableList(GROUPS); }

    /** Modules qui n'appartiennent à AUCUN {@link ModuleGroup} — ce sont ceux qui gardent leur propre carte sur l'écran d'accueil. */
    public static List<LauncherModule> ungrouped() {
        List<LauncherModule> result = new ArrayList<>();
        for (LauncherModule m : MODULES) {
            boolean grouped = false;
            for (ModuleGroup g : GROUPS) {
                if (g.members.contains(m)) { grouped = true; break; }
            }
            if (!grouped) result.add(m);
        }
        return result;
    }

    public static LauncherModule get(String id) {
        for (LauncherModule m : MODULES) if (m.id.equals(id)) return m;
        return null;
    }

    /** Filtre les {@code null} — voir IS_1_16, certains {@code get(id)} n'ont pas de résultat selon le bracket. */
    private static List<LauncherModule> nonNull(LauncherModule... modules) {
        List<LauncherModule> result = new ArrayList<>(modules.length);
        for (LauncherModule m : modules) if (m != null) result.add(m);
        return result;
    }

    public static void tickAll() {
        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try { m.onTick(); } catch (Throwable ignored) {}
            }
        }
    }

    public static void renderOverlayAll(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try { m.onRenderOverlay(renderer, vpWidth, vpHeight); } catch (Throwable ignored) {}
            }
        }
    }
}
