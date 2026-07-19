package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.runtime.module.ArmorDurabilityModule;
import com.yuyuframe.launcheragent.runtime.module.ChatEnhancementsModule;
import com.yuyuframe.launcheragent.runtime.module.ClearVisionModule;
import com.yuyuframe.launcheragent.runtime.module.CoordsModule;
import com.yuyuframe.launcheragent.runtime.module.CrosshairModule;
import com.yuyuframe.launcheragent.runtime.module.DiagonalSwordModule;
import com.yuyuframe.launcheragent.runtime.module.FovModule;
import com.yuyuframe.launcheragent.runtime.module.FpsModule;
import com.yuyuframe.launcheragent.runtime.module.FreelookModule;
import com.yuyuframe.launcheragent.runtime.module.FullbrightModule;
import com.yuyuframe.launcheragent.runtime.module.HurtCamModule;
import com.yuyuframe.launcheragent.runtime.module.KeystrokesModule;
import com.yuyuframe.launcheragent.runtime.module.LowHealthTintModule;
import com.yuyuframe.launcheragent.runtime.module.MumbleLinkModule;
import com.yuyuframe.launcheragent.runtime.module.NoDarknessModule;
import com.yuyuframe.launcheragent.runtime.module.NoFogModule;
import com.yuyuframe.launcheragent.runtime.module.NoPumpkinOverlayModule;
import com.yuyuframe.launcheragent.runtime.module.OldBowModule;
import com.yuyuframe.launcheragent.runtime.module.OldConsumeModule;
import com.yuyuframe.launcheragent.runtime.module.OldItemRotationsModule;
import com.yuyuframe.launcheragent.runtime.module.PingModule;
import com.yuyuframe.launcheragent.runtime.module.PotionEffectsModule;
import com.yuyuframe.launcheragent.runtime.module.SaturationModule;
import com.yuyuframe.launcheragent.runtime.module.ShulkerPreviewModule;
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
    // AUDIT PERF (demandé explicitement par l'utilisateur) : get(id) faisait
    // un scan linéaire de MODULES (~30-40 entrées) à CHAQUE appel — utilisé
    // depuis plusieurs Mixins déclenchés CHAQUE FRAME (ex: CrosshairMixin/261,
    // extractCrosshair — coût individuel négligeable en absolu, mais gratuit
    // à éliminer). O(1) via cette table, tenue à jour par register().
    private static final java.util.Map<String, LauncherModule> BY_ID = new java.util.HashMap<>();

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
     * Élargi aux brackets "C" (1.20.4), "D" (1.21.4), et maintenant "1.21.11"
     * et "E" (26.1.2, voir VersionBracketRegistry) : mêmes réglages vanilla
     * natifs (FOV/Sprint/Sneak, hurt cam) présents depuis la "Flattening"
     * (~1.13) et toujours là sur ces brackets, et les mêmes 7 Mixins
     * "animations 1.7" restent 1.8.9-only — donc les mêmes exclusions
     * s'appliquent partout. Le nom {@code IS_1_16} n'a pas été renommé (trop
     * de commentaires y référeraient encore) mais couvre bien TOUS ces
     * brackets malgré son nom.
     *
     * Demande explicite de l'utilisateur (audit avant publication) : 1.21.11
     * et 26.1.2 ajoutés à cette liste — jusque-là ABSENTS, alors que le même
     * raisonnement (réglages vanilla déjà natifs) s'y applique tout autant ;
     * gap probablement jamais comblé faute d'y avoir pensé lors des ajouts
     * successifs de brackets. Seul effet notable pour le groupe "Confort
     * visuel" : {@code ZoomModule} n'est PAS dans cette liste d'exclusion
     * (aucun équivalent vanilla, jamais concerné) — reste donc le SEUL membre
     * actif du groupe sur ces deux brackets.
     */
    private static final boolean IS_1_16 = System.getProperty("launcheragent.mcVersion", "").startsWith("1.16")
        || "1.20.4".equals(System.getProperty("launcheragent.mcVersion", ""))
        || "1.21.4".equals(System.getProperty("launcheragent.mcVersion", ""))
        || "1.21.11".equals(System.getProperty("launcheragent.mcVersion", ""))
        || "26.1.2".equals(System.getProperty("launcheragent.mcVersion", ""));

    /**
     * Sans citrouille / Vision claire / Sans flou de mouvement : implémentés
     * via des Mixins qui n'existent QUE pour le bracket 26.1 pour l'instant
     * (voir {@code mixin/client/v26_1} et leur javadoc respective) — cartes
     * gatées pour ne pas afficher 3 toggles sans le moindre effet sur les
     * autres brackets (même principe que le masquage du groupe
     * "Optimisations", demande explicite de l'utilisateur lors de cet
     * audit-là).
     */
    private static final boolean IS_26_1 = "26.1.2".equals(System.getProperty("launcheragent.mcVersion", ""));

    /**
     * Portage multiversion en cours (demande explicite de l'utilisateur,
     * audit du 2026-07-17 : "je n'ai jamais demandé à ce qu'il soit exclu,
     * mets-les en multiversion") — {@code NoPumpkinOverlayModule} confirmé
     * fonctionnel sur 1.21.11 en plus de 26.1.2 (même point d'accroche
     * {@code InGameHud.renderOverlay}/{@code Gui.renderTextureOverlay},
     * juste des noms Yarn différents — voir {@code ClearOverlaysMixin}).
     * {@code ShulkerPreviewModule} ET {@code FreelookModule} portés vers
     * 1.21.11 également (voir leurs javadoc respectives — McReflect pour le
     * premier, deux Mixins dédiés {@code MouseHandlerFreelookMixin}/{@code
     * CameraFreelookMixin} pour le second, architecture Camera.update()
     * vérifiée par javap, distincte de 26.1.2).
     * {@code ClearVisionModule} reste 26.1.2-only pour l'instant : son
     * portage vers 1.21.11 s'est heurté à une architecture de brouillard
     * {@code FogModifier} (1.21.11) totalement différente de {@code
     * FogEnvironment} (26.1.2, {@code setupFog(FogData,...)}) — {@code
     * getFogColor}/{@code shouldApply} au lieu de distances mutables, ET en
     * grande partie NON MAPPÉE par Yarn à ce jour (méthodes présentes
     * seulement sous leur ID intermédiaire brut, ex. {@code method_76304}) —
     * nécessite une recherche bytecode séparée, pas un simple portage de
     * noms. 1.16.5/1.20.4/1.21.4/1.8.9 pas encore commencés du tout pour ces
     * 4 modules.
     */
    private static final boolean IS_1_21_11 = "1.21.11".equals(System.getProperty("launcheragent.mcVersion", ""));

    /**
     * Portage 1.21.4 (bracket "D", ~1.21-1.21.5, voir VersionBracketRegistry)
     * — architecture Camera/Mouse/InGameHud.renderOverlay VÉRIFIÉE IDENTIQUE
     * à 1.21.11 par désassemblage complet (javap sur le vrai jar 1.21.4 :
     * mêmes IDs intermediary EXACTS pour Camera.update/moveBy/setRotation,
     * même structure bytecode — deux call sites de moveBy(FFF)V aux offsets
     * 309/368) — {@code NoPumpkinOverlayModule}/{@code ShulkerPreviewModule}/
     * {@code FreelookModule} portés en conséquence (voir {@code
     * ClearOverlaysMixin1214}/{@code MouseHandlerFreelookMixin1214}/{@code
     * CameraFreelookMixin1214}).
     *
     * Différence notable pour {@code ShulkerPreviewModule} : ce bracket n'a
     * PAS l'architecture "Deferred" (pas de {@code GuiRenderState}/{@code
     * GuiRenderer}, introduits entre la 1.21.4 et la 1.21.11) — le fond de
     * fenêtre du panneau (texture vanilla brute) utilise donc un chemin
     * "Immediate" séparé ({@code UiRenderer.drawVanillaContainerTextureModernImmediate},
     * nouveau cette session), qui reconstruit le {@code
     * java.util.function.Function<Identifier,RenderLayer>} attendu par
     * {@code DrawContext.drawTexture} via un {@link java.lang.reflect.Proxy}
     * enveloppant {@code RenderLayer.getGuiTextured} (référence de méthode
     * statique utilisée par vanilla lui-même — retrouvée dans la table
     * BootstrapMethods de {@code HandledScreen}, désassemblage du vrai jar
     * 1.21.4) plutôt qu'un {@code RenderPipeline} direct (qui n'existe pas
     * encore sur ce bracket). Le hook lui-même (contrairement à 1.21.11) n'a
     * PAS besoin d'un second point d'accroche différé façon {@code
     * GuiFlushMixin} : le pipeline Immediate dessine de façon SYNCHRONE dès
     * l'appel, directement depuis {@code GlobalUiRenderMixin1214} (TAIL de
     * {@code GameRenderer.render()}, donc déjà après {@code Screen.render()}
     * — bon z-order garanti sans complexité supplémentaire).
     *
     * {@code ClearVisionModule}/{@code NoFogModule} (refonte FogEnvironment) :
     * même statut que 1.21.11 pour Vision claire (jamais tenté, architecture
     * de brouillard classique pré-refonte toujours active sur ce bracket —
     * voir {@code NoFogModule}, fonctionne déjà via l'ancien flag sans
     * portage nécessaire).
     */
    private static final boolean IS_1_21_4 = "1.21.4".equals(System.getProperty("launcheragent.mcVersion", ""));

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
        // Saturation (équivalent AppleSkin) / Sans Ténèbres / Sans brouillard —
        // aucun équivalent vanilla, aucune restriction 1.8.9 (contrairement aux
        // modules ci-dessous) : enregistrés inconditionnellement, comme Zoom.
        // Chacun dégrade proprement (no-op) sur les brackets où sa mécanique
        // sous-jacente n'existe pas encore (Ténèbres = 1.19+, voir leurs javadoc).
        register(new SaturationModule());
        register(new NoDarknessModule());
        register(new NoFogModule());
        // 26.1.2/1.21.11/1.21.4 pour l'instant (voir IS_26_1/IS_1_21_11/
        // IS_1_21_4 plus haut + javadoc de ces 3 modules) — implémentés via
        // des Mixins qui n'existent pas encore pour 1.8.9/1.16.5/1.20.4,
        // contrairement aux 3 modules ci-dessus qui fonctionnent partout via
        // McReflect seul.
        if (IS_26_1 || IS_1_21_11 || IS_1_21_4) {
            register(new NoPumpkinOverlayModule());
        }
        if (IS_26_1) {
            register(new ClearVisionModule());
        }
        // Contenu stocké via DataComponents.CONTAINER (refonte "Data
        // Components", ~1.20.5) — lu par réflexion à noms RÉELS directs sur
        // 26.1.2, à noms Yarn + repli réel via McReflect sur 1.21.11/1.21.4
        // (voir sa javadoc) — aucun équivalent 1.8.9/1.16.5/1.20.4 pour
        // l'instant (stockage NBT pré-refonte, lecture entièrement différente).
        if (IS_26_1 || IS_1_21_11 || IS_1_21_4) {
            register(new ShulkerPreviewModule());
        }
        // Annule MouseHandler.turnPlayer(26.1.2)/Mouse.updateMouse(1.21.11/
        // 1.21.4) + rappelle Camera.setRotation (voir sa javadoc) — aucun
        // équivalent 1.8.9/1.16.5/1.20.4 pour l'instant.
        if (IS_26_1 || IS_1_21_11 || IS_1_21_4) {
            register(new FreelookModule());
        }
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
        // Modules "Optimisations" — leur implémentation réelle vit
        // ENTIÈREMENT dans des Mixins *189 (1.8.9 uniquement, voir
        // mixin/client/v1_8/optimodule et le mémo project-optimodule-fps-status) :
        // jamais portés vers aucun autre bracket. Étaient enregistrés
        // INCONDITIONNELLEMENT jusqu'ici (contrairement à Fov/ToggleSprint/etc.
        // ci-dessus, déjà protégés par IS_1_16) — cartes cliquables qui ne
        // faisaient RIEN sur 1.16.5+/1.20.4/1.21.4/1.21.11/26.1.2 (aucune
        // classe Mixin *189 ne se charge en dehors du bracket 1.8.9, voir
        // VersionBracketRegistry). Gaté sur demande explicite de l'utilisateur
        // (audit avant publication) avec le MÊME flag IS_1_16 — pas un nouveau
        // flag séparé, il n'y a aucune raison que le critère diffère de celui
        // déjà établi pour les autres modules 1.8.9-only.
        if (!IS_1_16) {
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
        }
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
        List<LauncherModule> comfortMembers = nonNull(get("fov"), get("zoom"), get("hurt-cam"), get("toggle-sprint"), get("toggle-sneak"),
            get("saturation"), get("no-darkness"), get("no-fog"),
            get("no-pumpkin-overlay"), get("clear-vision"), get("freelook"));
        if (!comfortMembers.isEmpty()) {
            GROUPS.add(new ModuleGroup("comfort", "Confort visuel",
                "FOV, Zoom, Hurt Cam, Sprint/Sneak, Saturation, Ténèbres, Brouillard, Citrouille, Vision claire",
                "Réglages de confort et d'immersion", comfortMembers));
        }
        // Groupe entièrement exclu sur 1.16.5 (les 7 membres y sont tous
        // exclus, voir IS_1_16) — pas de carte vide affichée dans ce cas.
        List<LauncherModule> legacyMembers = nonNull(get("swing-speed-1-7"), get("diagonal-sword"), get("old-item-rotations"),
            get("swing-while-blocking"), get("old-bow"), get("old-consume"), get("sneak-ramp-1-7"));
        if (!legacyMembers.isEmpty()) {
            GROUPS.add(new ModuleGroup("legacy-1-7", "Animations 1.7", "Swing, item, arc, manger/boire, sneak",
                "Animations façon 1.7", legacyMembers));
        }
        // Optimisations FPS (voir mixin/.../optimodule et runtime/module/optimodule) —
        // portage de features de PolyPatcher (mod d'optimisation 1.8.9 open source),
        // pas de dépendance sur PolyPatcher lui-même, juste la même idée en Mixin natif.
        // Groupe entier gaté par IS_1_16 (comme les modules eux-mêmes juste
        // au-dessus, voir leur commentaire) — plutôt que de filtrer les
        // membres un par un ici (les Tab ci-dessous en contiennent aussi,
        // il aurait fallu les filtrer séparément), tout le bloc est sauté
        // d'un coup : sur les brackets exclus, aucun des 10 modules n'est
        // enregistré de toute façon (get(id) renverrait null partout).
        if (!IS_1_16) {
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
    }

    public static void register(LauncherModule module) {
        MODULES.add(module);
        BY_ID.put(module.id, module);
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
        return BY_ID.get(id);
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
