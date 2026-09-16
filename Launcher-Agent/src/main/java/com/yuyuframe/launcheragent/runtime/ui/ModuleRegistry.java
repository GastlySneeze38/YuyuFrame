package com.yuyuframe.launcheragent.runtime.ui;

import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.runtime.module.hud.ArmorDurabilityModule;
import com.yuyuframe.launcheragent.runtime.module.gameplay.ChatEnhancementsModule;
import com.yuyuframe.launcheragent.runtime.module.visual.ClearVisionModule;
import com.yuyuframe.launcheragent.runtime.module.hud.CoordsModule;
import com.yuyuframe.launcheragent.runtime.module.visual.CrosshairModule;
import com.yuyuframe.launcheragent.runtime.module.visual.FovModule;
import com.yuyuframe.launcheragent.runtime.module.hud.FpsModule;
import com.yuyuframe.launcheragent.runtime.module.legacy17.Animations17Module;
import com.yuyuframe.launcheragent.runtime.module.visual.FreelookModule;
import com.yuyuframe.launcheragent.runtime.module.visual.FullbrightModule;
import com.yuyuframe.launcheragent.runtime.module.visual.HurtCamModule;
import com.yuyuframe.launcheragent.runtime.module.hud.KeystrokesModule;
import com.yuyuframe.launcheragent.runtime.module.visual.LowHealthTintModule;
import com.yuyuframe.launcheragent.runtime.module.gameplay.MacroModule;
import com.yuyuframe.launcheragent.runtime.module.gameplay.MumbleLinkModule;
import com.yuyuframe.launcheragent.runtime.module.visual.NoDarknessModule;
import com.yuyuframe.launcheragent.runtime.module.visual.NoFogModule;
import com.yuyuframe.launcheragent.runtime.module.visual.NoPumpkinOverlayModule;
import com.yuyuframe.launcheragent.runtime.module.hud.PingModule;
import com.yuyuframe.launcheragent.runtime.module.hud.PotionEffectsModule;
import com.yuyuframe.launcheragent.runtime.module.hud.SaturationModule;
import com.yuyuframe.launcheragent.runtime.module.gameplay.ToggleSneakModule;
import com.yuyuframe.launcheragent.runtime.module.gameplay.ToggleSprintModule;
import com.yuyuframe.launcheragent.runtime.module.visual.WorldTimeModule;
import com.yuyuframe.launcheragent.runtime.module.visual.ZoomModule;
import com.yuyuframe.launcheragent.apigraphic.UiRenderer;

import java.util.ArrayList;
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
     * Sans citrouille / Vision claire : implémentés via des mixins qui
     * n'existent que pour 26.1.2 et 1.21.11 ({@code apimixin/v26_1},
     * {@code apimixin/v1_21_11}) — cartes gatées pour ne pas afficher des
     * toggles sans le moindre effet sur la 1.8.9 (même principe que le
     * masquage du groupe "Optimisations", demande explicite de l'utilisateur
     * lors de cet audit-là).
     */
    private static final boolean IS_26_1 = "26.1.2".equals(System.getProperty("launcheragent.mcVersion", ""));

    /**
     * Portage multiversion en cours (demande explicite de l'utilisateur,
     * audit du 2026-07-17 : "je n'ai jamais demandé à ce qu'il soit exclu,
     * mets-les en multiversion") — {@code NoPumpkinOverlayModule} confirmé
     * fonctionnel sur 1.21.11 en plus de 26.1.2 (même point d'accroche
     * {@code InGameHud.renderOverlay}/{@code Gui.renderTextureOverlay},
     * juste des noms Yarn différents — voir {@code ClearOverlaysMixin}).
     * {@code FreelookModule} porté vers 1.21.11 également (voir sa javadoc —
     * deux Mixins dédiés {@code MouseHandlerFreelookMixin}/{@code
     * CameraFreelookMixin}, architecture Camera.update() vérifiée par javap,
     * distincte de 26.1.2). {@code ShulkerPreviewModule} l'avait été aussi,
     * mais il a été SUPPRIMÉ le 2026-08-31 (voir plus bas).
     * {@code ClearVisionModule} activé sur 1.21.11 le 2026-09-11. L'ancienne
     * raison de l'exclure (« architecture {@code FogModifier} totalement
     * différente, sans distances mutables ») était FAUSSE : les mappings
     * Mojang officiels 1.21.11 montrent la même classe {@code FogEnvironment}
     * et le même {@code setupFog(FogData, …)} qu'en 26.1.2 — seul Yarn les
     * nomme autrement ({@code FogModifier.applyStartEndModifier}). Passe par
     * {@code apimixin/v1_21_11/fog/} (contrat neutre, voir {@code FogOverride})
     * et {@code HudExtractTextureOverlayMixin1211} pour le givre.
     * 1.8.9 pas encore commencée pour ces modules.
     */
    private static final boolean IS_1_21_11 = "1.21.11".equals(System.getProperty("launcheragent.mcVersion", ""));

    /**
     * 1.8.9 — seule version où les animations 1.7, les bascules sneak/sprint,
     * la hurt cam et le FOV fixe ont des mixins (apimixin/v1_8_9, portés le
     * 2026-09-15). Utilisé aussi pour la macro, ajoutée à cette version à la
     * même date.
     *
     * <p>Remplace (2026-09-16) le drapeau {@code IS_1_16}, qui malgré son nom
     * valait vrai sur TOUTES les versions sauf 1.8.9 et excluait ces modules
     * ailleurs : ToggleSprint/ToggleSneak/Fov/HurtCam y sont redondants avec
     * des réglages vanilla natifs depuis ~1.13, et les animations 1.7 n'y ont
     * pas de mixin. {@code ZoomModule} n'est pas concerné (aucun équivalent
     * vanilla). Avec les seules versions supportées (1.8.9, 1.21.11, 26.1.2),
     * « pas 1.16 » et « 1.8.9 » désignent exactement les mêmes lancements.
     */
    private static final boolean IS_1_8_9 = "1.8.9".equals(System.getProperty("launcheragent.mcVersion", ""));

    static {
        // Chaque module passe par safeRegister (2026-09-11) — voir sa javadoc :
        // un seul module lié à un type absent de la version (ex. SimpleSoundInstance
        // 26.1.2 sur 1.21.11) tuait tout le bloc static, donc TOUS les modules.
        safeRegister(() -> new FpsModule());
        safeRegister(() -> new PingModule());
        safeRegister(() -> new CoordsModule());
        safeRegister(() -> new KeystrokesModule());
        safeRegister(() -> new PotionEffectsModule());
        safeRegister(() -> new ArmorDurabilityModule());
        safeRegister(() -> new LowHealthTintModule());
        if (IS_1_8_9) safeRegister(() -> new FovModule());
        // Enregistré JUSTE APRÈS FovModule — tickAll() itère MODULES dans
        // l'ordre d'enregistrement, donc si les deux sont actifs, le zoom
        // s'applique EN DERNIER chaque frame et n'est jamais écrasé par le
        // FOV permanent de FovModule (voir ZoomModule pour le détail).
        safeRegister(() -> new ZoomModule());
        // Saturation (équivalent AppleSkin) / Sans Ténèbres / Sans brouillard —
        // aucun équivalent vanilla, aucune restriction 1.8.9 (contrairement aux
        // modules ci-dessous) : enregistrés inconditionnellement, comme Zoom.
        // Chacun dégrade proprement (no-op) sur les brackets où sa mécanique
        // sous-jacente n'existe pas encore (Ténèbres = 1.19+, voir leurs javadoc).
        safeRegister(() -> new SaturationModule());
        safeRegister(() -> new NoDarknessModule());
        safeRegister(() -> new NoFogModule());
        // 26.1.2/1.21.11 pour l'instant (voir IS_26_1/IS_1_21_11 plus haut +
        // javadoc de ces modules) — implémentés via des mixins qui n'existent
        // pas encore pour 1.8.9.
        if (IS_26_1 || IS_1_21_11) {
            safeRegister(() -> new NoPumpkinOverlayModule());
        }
        if (IS_26_1 || IS_1_21_11) {
            safeRegister(() -> new ClearVisionModule());
        }
        // ShulkerPreviewModule SUPPRIMÉ le 2026-08-31 (décision utilisateur :
        // « il y a trop de boulot à faire pour ce qu'un mod peut faire de
        // façon optimisée »). Il restait le dernier gros consommateur de
        // réflexion de l'agent (22 appels) et le seul rendu à devoir
        // s'intercaler dans un écran de conteneur vanilla.
        // Annule MouseHandler.turnPlayer(26.1.2)/Mouse.updateMouse(1.21.11)/
        // l'appel à increaseTransforms de GameRenderer.render (1.8.9, depuis
        // le 2026-09-16) et décale la rotation de la caméra (voir sa javadoc).
        if (IS_26_1 || IS_1_21_11 || IS_1_8_9) {
            safeRegister(() -> new FreelookModule());
        }
        // 1.8.9 seulement (voir IS_1_8_9 plus haut), sur demande explicite de
        // l'utilisateur : l'effet de secousse caméra à la prise de dégâts est
        // natif en vanilla depuis ~1.13 — carte redondante sinon.
        if (IS_1_8_9) safeRegister(() -> new HurtCamModule());
        if (IS_1_8_9) safeRegister(() -> new ToggleSprintModule());
        if (IS_1_8_9) safeRegister(() -> new ToggleSneakModule());
        // Animations 1.7 : UN module depuis le 2026-09-16 (voir sa javadoc),
        // à la place du groupe de sept modules séparés.
        if (IS_1_8_9) safeRegister(() -> new Animations17Module());
        safeRegister(() -> new CrosshairModule());
        safeRegister(() -> new FullbrightModule());
        safeRegister(() -> new WorldTimeModule());
        safeRegister(() -> new ChatEnhancementsModule());
        // Les modules « Optimisations » 1.8.9 (optimodule) et la fenêtre sans
        // bordure ont été retirés le 2026-09-13 : le renderer 1.8.9 et LWJGL 3
        // les remplacent (docs/LauncherAgent/v1.8.9/README.md).
        safeRegister(() -> new MumbleLinkModule());
        // Macros + connexion automatique — 26.1.2 ET 1.21.11 depuis le
        // 2026-09-12. La restriction d'origine disait « la détection du login
        // lit l'arbre de commandes via des accessors qui n'existent que sur ce
        // bracket » : ce n'est plus vrai, tout passe désormais par
        // NetworkData/les points d'accès, liés des deux côtés. La 1.8.9 s'y
        // ajoute le 2026-09-15 : elle tourne sur GLFW, donc a le poller
        // moderne. Sans Brigadier, NETWORK_HAS_COMMAND n'y est pas lié : les
        // macros marchent, la connexion automatique ne se déclenche jamais.
        if (IS_26_1 || IS_1_21_11 || IS_1_8_9) {
            safeRegister(() -> new MacroModule());
        }

        // Infra de rendu (pas un module) — voir INFRA_HOOK_POINTS. Émet le HUD
        // PUIS vide la file d'icônes d'item, le tout dans l'état de GUI de
        // vanilla, entre le HUD vanilla et le chat. Remplace l'ancien dessin
        // après-coup qui faisait passer tout notre contenu par-dessus le chat.
        //
        // Passe par VanillaGuiPass, indépendant de la version : c'est la sink
        // de la version en cours (VanillaGuiSinks) qui parle à l'état de GUI,
        // 26.1.2 comme 1.21.11.
        //
        // try/catch CONSERVÉ : un échec ICI, dans le bloc static, rendrait
        // ModuleRegistry définitivement inutilisable (NoClassDefFoundError à
        // chaque accès), donc AUCUN module. Perdre le HUD vaut mieux que
        // perdre tous les modules.
        try {
            com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiPass.setHudPass(
                com.yuyuframe.launcheragent.runtime.ui.hud.HudOverlayRenderer::renderInVanillaGui);
            com.yuyuframe.launcheragent.apigraphic.era.blaze3d.vanillagui.VanillaGuiPass.install();
        } catch (Throwable t) {
            com.yuyuframe.launcheragent.base.log.LauncherLog.err(
                "[ModuleRegistry] installation du HUD dans la passe GUI vanilla impossible sur cette version : " + t);
        }

        // Regroupement demandé — voir ModuleGroup : purement de la
        // présentation, les modules ci-dessus restent enregistrés
        // individuellement juste au-dessus (tickAll/renderOverlayAll/persistance
        // inchangés), seul UiMainMenuScreen les affiche fusionnés sous une
        // carte au lieu d'une par module.
        // nonNull() — certains membres ci-dessous ne sont enregistrés que sur
        // une version (voir IS_1_8_9/IS_1_21_11 plus haut) : get(id) renvoie alors null, qu'il
        // faut filtrer avant de construire le groupe (sinon carte "vide"
        // cassée dans l'UI).
        // 2026-08-30, demande explicite : le Freelook PUIS le Zoom SORTENT du
        // groupe (modules à part entière, leur propre carte via ungrouped()) et
        // le Fullbright y ENTRE — favori par défaut, donc il garde malgré tout
        // une carte à lui sur l'accueil, en raccourci vers ses réglages dans le
        // groupe (voir FullbrightModule, où ce défaut est posé, et
        // groupedFavorites()).
        List<LauncherModule> comfortMembers = nonNull(get("fov"), get("hurt-cam"), get("toggle-sprint"), get("toggle-sneak"),
            get("saturation"), get("no-darkness"), get("no-fog"),
            get("no-pumpkin-overlay"), get("clear-vision"), get("fullbright"));
        if (!comfortMembers.isEmpty()) {
            ModuleGroup comfortGroup = new ModuleGroup("comfort", "Confort visuel",
                "FOV, Hurt Cam, Sprint/Sneak, Saturation, Ténèbres, Brouillard, Citrouille, Vision claire, Fullbright",
                "Réglages de confort et d'immersion", comfortMembers);
            comfortGroup.iconUrl = LauncherModule.icons8("visible");
            GROUPS.add(comfortGroup);
        }
        // Charge l'état "favori" persisté de CHAQUE groupe (demandé
        // explicitement : "rends les groupes favorisables") — même rôle que
        // HudConfigStore.applyTo(module) pour un LauncherModule, appliqué
        // ici APRÈS que tous les groupes ci-dessus aient été ajoutés
        // (jamais avant, sinon un groupe pas encore dans GROUPS ne serait
        // simplement pas couvert par cette boucle).
        for (ModuleGroup group : GROUPS) {
            HudConfigStore.applyFavoriteTo(group);
        }
    }

    /**
     * Construit ET enregistre un module, en isolant tout échec (2026-09-11).
     *
     * <p>Pourquoi : les modules référencent encore des types Mojang 26.1.2
     * (ex. {@code SimpleSoundInstance} dans {@code ArmorDurabilityModule} /
     * {@code ChatEnhancementsModule}). Sur une autre version, la JVM ne peut
     * pas lier la classe du module : {@code new X()} lève
     * {@code NoClassDefFoundError}. Dans le bloc {@code static}, cette erreur
     * rendait {@code ModuleRegistry} DÉFINITIVEMENT inutilisable
     * (« Could not initialize class » à chaque accès) — aucun module, menu
     * vide, constaté sur Fabric 1.21.11. Probable cause du vieux symptôme
     * « sur certaines instances l'interface ne se lance pas ».
     *
     * <p>⚠️ Lambda EXPLICITE ({@code () -> new X()}), JAMAIS {@code X::new}
     * (testé en jeu, v1058) : une référence de constructeur est un
     * {@code invokedynamic} dont la JVM LIE la classe cible dès l'exécution de
     * la ligne — avant {@code get()}, donc hors du try, et l'erreur tuait
     * encore tout le bloc static. Dans une lambda, le {@code new} est dans le
     * corps : la classe n'est initialisée qu'à l'appel, dans le try.
     */
    private static void safeRegister(java.util.function.Supplier<LauncherModule> factory) {
        try {
            register(factory.get());
        } catch (Throwable t) {
            com.yuyuframe.launcheragent.base.log.LauncherLog.err(
                "[ModuleRegistry] module non chargé sur cette version (les autres continuent) : " + t);
        }
    }

    public static void register(LauncherModule module) {
        MODULES.add(module);
        BY_ID.put(module.id, module);
        // Fige les valeurs d'origine de l'élément HUD AVANT que HudConfigStore
        // n'applique la config persistée juste en dessous — c'est le seul
        // instant où elles sont encore intactes ET complètes : le constructeur
        // du module vient de finir (donc textColor/paddingX posés par lui sont
        // là), et rien n'a encore été écrasé par le disque. Les capturer dans
        // le constructeur de HudElement serait trop TÔT (le module n'a pas
        // encore personnalisé), ici c'est trop TARD d'une ligne si on le fait
        // après applyTo(). Voir HudElement.captureDefaults/resetAll.
        if (module instanceof HudElementOwner) {
            ((HudElementOwner) module).hudElement().captureDefaults();
        }
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
        // Roadmap Phase 6 — voir ungroupedCache/ungrouped() : invalide le
        // cache à chaque enregistrement (même un module enregistré dynamiquement
        // après le bloc static{}, voir commentaire ci-dessus) plutôt que de le
        // supposer figé une fois la classe chargée.
        ungroupedCache = null;
    }

    public static List<LauncherModule> all() { return Collections.unmodifiableList(MODULES); }

    public static List<ModuleGroup> groups() { return Collections.unmodifiableList(GROUPS); }

    /**
     * HookPoint consommés par des registrants qui NE SONT PAS des modules
     * (2026-08-25, §12).
     *
     * {@code LauncherModule.hookPoints} couvre les modules, mais pas
     * l'infrastructure : {@code ClientCommandRegistry.bootstrap()} enregistre
     * {@link HookPoint#COMMAND_SEND} pour intercepter les commandes client, sans
     * être un module (c'était {@code CHAT_SEND} jusqu'au 2026-09-13, où aucune
     * commande n'arrive — voir ClientCommandRegistry ; {@code CHAT_SEND} reste
     * déclaré par {@code ChatEnhancementsModule}). Toute future décision prise avant le tissage (gate
     * déclarative) doit donc réunir CE jeu et celui des modules, sinon le
     * mixin correspondant serait écarté à tort.
     *
     * À compléter si un autre composant hors-module se met à appeler
     * {@code VanillaHookRegistry.register(...)} — l'audit de
     * {@link VanillaHookRegistry#auditDeclarations} le signalera aussitôt en
     * « UTILISÉS MAIS NON DÉCLARÉS ».
     */
    private static final HookPoint[] INFRA_HOOK_POINTS = {
        HookPoint.COMMAND_SEND,
        // Ajouté le 2026-09-13 : ClientCommandRegistry y greffe ses commandes
        // pour que le chat les suggère.
        HookPoint.COMMAND_TREE_RECEIVE,
        // Ajouté le 2026-08-30 : aucun module ne le réclame, mais l'infra de
        // rendu s'en sert pour vider la file d'icônes d'item vanilla JUSTE
        // AVANT le chat (voir VanillaGuiLayer.installItemIconFlush). Sans
        // cette déclaration, le filtre HookPoint écarterait le mixin
        // HudExtractChatMixin261 et le correctif de z-order serait silencieux.
        HookPoint.HUD_EXTRACT_CHAT,
    };

    /**
     * Union de TOUS les HookPoint déclarés statiquement : modules + infra.
     *
     * C'est la seule vue exploitable AVANT le tissage — {@code
     * VanillaHookRegistry.usedPoints()} est vide à cet instant, les
     * enregistrements n'ayant lieu qu'à la première frame.
     *
     * ⚠️ Appelle {@link #all()}, donc CONSTRUIT tous les modules. Ne pas
     * invoquer depuis un contexte de tissage sans avoir vérifié qu'aucun
     * constructeur de module ne touche une classe du jeu.
     */
    public static java.util.Set<HookPoint> declaredHookPoints() {
        java.util.EnumSet<HookPoint> declared = java.util.EnumSet.noneOf(HookPoint.class);
        Collections.addAll(declared, INFRA_HOOK_POINTS);
        for (LauncherModule m : all()) {
            if (m.hookPoints != null) Collections.addAll(declared, m.hookPoints);
        }
        return declared;
    }

    /**
     * Roadmap Phase 6 ("ungrouped() refait un double-parcours O(n×m) à chaque
     * appel") — l'appartenance module→groupe ne change qu'à l'enregistrement
     * d'un module (voir {@link #register}, seul endroit qui invalide ce
     * cache) ; {@link #GROUPS} lui-même n'est mutable QUE depuis le bloc
     * {@code static{}} (aucune méthode publique pour y ajouter un groupe
     * après coup), donc invalider sur {@code register()} seul suffit à
     * couvrir les deux sources de changement possibles. {@code null} =
     * jamais calculé ou invalidé depuis le dernier appel.
     */
    private static List<LauncherModule> ungroupedCache;

    /** Modules qui n'appartiennent à AUCUN {@link ModuleGroup} — ce sont ceux qui gardent leur propre carte sur l'écran d'accueil. */
    public static List<LauncherModule> ungrouped() {
        if (ungroupedCache != null) return ungroupedCache;
        List<LauncherModule> result = new ArrayList<>();
        for (LauncherModule m : MODULES) {
            boolean grouped = false;
            for (ModuleGroup g : GROUPS) {
                if (g.members.contains(m)) { grouped = true; break; }
            }
            if (!grouped) result.add(m);
        }
        ungroupedCache = Collections.unmodifiableList(result);
        return ungroupedCache;
    }

    /**
     * Groupe auquel appartient {@code module}, ou {@code null} s'il n'est
     * membre d'aucun — exact complément de {@link #ungrouped()}.
     *
     * <p>Ajouté pour les FAVORIS DE MODULE GROUPÉ (2026-08-30) : un membre de
     * groupe mis en favori réapparaît comme une carte à part sur l'écran
     * d'accueil, dont le clic doit rouvrir l'écran de SON groupe. Il faut donc
     * pouvoir remonter du module vers son groupe, ce que le modèle ne
     * permettait que dans le sens inverse ({@link ModuleGroup#members}).
     *
     * <p>Parcours direct, sans cache : appelé une poignée de fois par
     * reconstruction de l'écran d'accueil (une par module favori groupé), pas
     * à chaque frame — contrairement à {@link #ungrouped()}, qui lui avait
     * bien un coût mesurable.
     */
    public static ModuleGroup groupOf(LauncherModule module) {
        if (module == null) return null;
        for (ModuleGroup g : GROUPS) {
            if (g.members.contains(module)) return g;
        }
        return null;
    }

    /**
     * Membres de groupe marqués favoris — ceux qui obtiennent une carte à part
     * sur l'écran d'accueil EN PLUS de la carte de leur groupe (voir
     * {@link #groupOf}). Les modules non groupés n'y sont PAS : ils ont déjà
     * leur propre carte, favoris ou non.
     */
    public static List<LauncherModule> groupedFavorites() {
        List<LauncherModule> result = new ArrayList<>();
        for (ModuleGroup g : GROUPS) {
            for (LauncherModule m : g.members) {
                if (m != null && m.favorite) result.add(m);
            }
        }
        return result;
    }

    /**
     * Compteur incrémenté à chaque changement de favori fait AILLEURS que sur
     * l'écran d'accueil (aujourd'hui : les cœurs de
     * {@code UiModGroupConfigScreen}).
     *
     * <p>BUG ÉVITÉ (2026-08-30) : {@code UiMainMenuScreen.rebuildAll()} n'est
     * déclenché QUE par un changement de taille de fenêtre ou d'échelle
     * d'interface. Mettre un module groupé en favori depuis l'écran de son
     * groupe, puis revenir à l'accueil, affichait donc la grille TELLE
     * QU'ELLE ÉTAIT — sans la nouvelle carte — jusqu'au prochain
     * redimensionnement. L'écran d'accueil compare ce compteur à chaque frame
     * et se reconstruit s'il a bougé.
     *
     * <p>Les cœurs de l'écran d'accueil lui-même n'en ont pas besoin : ils
     * appellent déjà {@code rebuildAll()} directement.
     */
    private static int favoritesRevision;

    public static int favoritesRevision() { return favoritesRevision; }

    /** À appeler après avoir modifié un {@code favorite} hors de l'écran d'accueil — voir {@link #favoritesRevision()}. */
    public static void markFavoritesChanged() { favoritesRevision++; }

    public static LauncherModule get(String id) {
        return BY_ID.get(id);
    }

    /** Filtre les {@code null} — voir IS_1_8_9, certains {@code get(id)} n'ont pas de résultat selon la version. */
    private static List<LauncherModule> nonNull(LauncherModule... modules) {
        List<LauncherModule> result = new ArrayList<>(modules.length);
        for (LauncherModule m : modules) if (m != null) result.add(m);
        return result;
    }

    /**
     * Journalise l'échec d'un module UNE fois par couple (module, phase).
     *
     * <p>Ces deux boucles tournent 20 à 120 fois par seconde : journaliser à
     * chaque passage noierait la console, mais tout avaler — ce qu'elles
     * faisaient — rend une panne de module totalement muette. Le déduplication
     * garde le premier signalement, celui qui porte la vraie cause.
     */
    private static final java.util.Set<String> REPORTED_FAILURES = new java.util.HashSet<>();

    private static void reportModuleFailure(String phase, LauncherModule module, Throwable t) {
        String key = phase + ":" + module.id;
        if (!REPORTED_FAILURES.add(key)) return;
        com.yuyuframe.launcheragent.base.log.LauncherLog.err(
            "[ModuleRegistry] " + phase + "(" + module.id + ") a levé : " + t);
    }

    public static void tickAll() {
        // Écriture différée de la configuration — voir HudConfigStore.save().
        // Ici plutôt que dans un mixin : c'est le seul point de tick déjà
        // partagé par tous les brackets.
        HudConfigStore.tick();
        // Commandes client reconnues depuis la dernière image — exécutées ici
        // et non dans le hook d'envoi, voir ClientCommandRegistry.
        com.yuyuframe.launcheragent.runtime.command.ClientCommandRegistry.tick();

        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try {
                    m.onTick();
                } catch (Throwable t) {
                    // Journalisé UNE fois par module : un module qui lève à
                    // chaque tick le ferait 20 fois par seconde, mais l'avaler
                    // entièrement — ce qui était le cas — rendait sa panne
                    // parfaitement invisible pendant toute la session.
                    reportModuleFailure("onTick", m, t);
                }
            }
        }
    }

    public static void renderOverlayAll(UiRenderer renderer, int vpWidth, int vpHeight) {
        for (LauncherModule m : MODULES) {
            if (m.isEnabled()) {
                try {
                    m.onRenderOverlay(renderer, vpWidth, vpHeight);
                } catch (Throwable t) {
                    reportModuleFailure("onRenderOverlay", m, t);
                }
            }
        }
    }

    /**
     * Pendant de {@link #renderOverlayAll} pour la passe GUI de vanilla — voir
     * {@link LauncherModule#onRenderInVanillaGui}.
     *
     * @param screenOpen un écran vanilla/mod est ouvert : seuls les modules
     *     qui le demandent explicitement sont alors appelés, voir
     *     {@link LauncherModule#renderInVanillaGuiWhenScreenOpen()}.
     */
    public static void renderInVanillaGuiAll(UiRenderer renderer, int vpWidth, int vpHeight, boolean screenOpen) {
        for (LauncherModule m : MODULES) {
            if (!m.isEnabled()) continue;
            if (screenOpen && !m.renderInVanillaGuiWhenScreenOpen()) continue;
            try {
                m.onRenderInVanillaGui(renderer, vpWidth, vpHeight);
            } catch (Throwable t) {
                com.yuyuframe.launcheragent.base.log.LauncherLog.err(
                    "[ModuleRegistry] onRenderInVanillaGui(" + m.id + "): " + t);
            }
        }
    }
}
