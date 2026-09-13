package com.yuyuframe.launcheragent.apimixin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Table déclarative « quels mixins {@code apimixin/} existent, pour quelle
 * version MC, derrière quel {@link HookPoint} » — SOURCE DE VÉRITÉ UNIQUE du
 * tissage apimixin depuis 2026-09-09.
 *
 * <h2>Ce qui a changé (2026-09-09) et pourquoi</h2>
 *
 * Avant, la liste des mixins vivait dans un JSON Mixin PAR bracket
 * ({@code mixins.launcheragent-apimixin-26.1.json}) et cette classe ne
 * portait qu'une map plate {@code nom simple → HookPoint} ; {@code
 * IsolatedBootstrap.filterConfigByHookPoints()} RETIRAIT ensuite du JSON, par
 * regex, les entrées dont le HookPoint n'était réclamé par aucun module.
 * Deux défauts structurels :
 *
 * <ul>
 *   <li>DEUX listes à tenir synchronisées (le JSON et cette table) — toute
 *       divergence était silencieuse : un mixin oublié dans le JSON ne tissait
 *       jamais, sans le moindre message ;</li>
 *   <li>la version était implicite, encodée dans le SUFFIXE du nom de classe
 *       ({@code ...Mixin261}). Deux versions MC ne pouvaient pas cohabiter
 *       dans la table, ce qui bloquait l'ajout d'une nouvelle version.</li>
 * </ul>
 *
 * Désormais la version est une COLONNE explicite, la table liste TOUS les
 * mixins apimixin (gatés ou non), et le JSON de config est GÉNÉRÉ à partir
 * d'elle au démarrage — voir {@code IsolatedBootstrap.buildApiMixinConfig()}.
 * Il n'y a plus de suppression par regex, plus de liste en double, et une
 * nouvelle version MC s'ajoute ici et nulle part ailleurs.
 *
 * <h2>⚠️ Cette classe est lue par BYTECODE, jamais par appel Java</h2>
 *
 * {@code IsolatedBootstrap.scanHookPointTable()} lit le {@code <clinit>} de ce
 * fichier {@code .class} tel quel dans le JAR et en extrait les appels {@link
 * #gate}/{@link #always} — la charger via {@code Class.forName} depuis {@code
 * premain} reviendrait à toucher {@code apimixin/} depuis le classloader
 * système, garantissant un {@code LinkageError} (voir l'historique dans
 * {@code LauncherMixinService.findClass}).
 *
 * <p><b>Conséquence de design, à respecter strictement : ce fichier est une
 * TABLE, pas du code.</b> Les seules choses lisibles par le scanner sont des
 * littéraux : {@code gate("<version>", "<entrée>", HookPoint.X)} et {@code
 * always("<version>", "<entrée>")}. Aucune boucle, aucun {@code if}, aucune
 * lambda, aucune constante calculée, aucune concaténation — tout cela
 * compilerait mais deviendrait invisible au scan, donc silencieusement
 * inerte. Toute logique (résolution d'une version vers une famille, repli,
 * gating) vit dans le LECTEUR, jamais ici.
 *
 * <h2>Les deux formes d'entrée</h2>
 *
 * <ul>
 *   <li>{@link #gate} — mixin backé par un {@link HookPoint} : tissé
 *       UNIQUEMENT si ce HookPoint est réclamé par un module ({@code
 *       LauncherModule.hookPoints}) ou par l'infrastructure ({@code
 *       ModuleRegistry.INFRA_HOOK_POINTS}). Un HookPoint que personne ne
 *       réclame = mixin absent du JSON généré, donc jamais chargé par Mixin.</li>
 *   <li>{@link #always} — mixin d'infrastructure : hub de rendu, accessors
 *       {@code @Accessor}/{@code @Invoker}, freelook. Toujours tissé, jamais
 *       gaté. (Le cas freelook n'est pas un oubli : voir le commentaire à la
 *       fin de la table.)</li>
 * </ul>
 *
 * L'entrée est le nom de classe RELATIF au package Mixin {@code
 * com.yuyuframe.launcheragent.apimixin} (ex. {@code
 * v26_1.hud.HudExtractArmorMixin261}) — exactement ce qui atterrit dans le
 * tableau {@code client} du JSON généré.
 *
 * <h2>Ajouter une version MC</h2>
 *
 * Écrire ses mixins dans {@code apimixin/vXX_Y/}, ajouter ici un bloc {@code
 * gate}/{@code always} avec la nouvelle chaîne de version, et déclarer son
 * {@code VersionProfile} (mappings + indice de jar Yarn). Aucun JSON à créer :
 * le template partagé {@code mixins.launcheragent-apimixin.template.json}
 * fournit l'en-tête pour toutes les versions.
 */
public final class MixinHookPointRegistry {

    private MixinHookPointRegistry() {}

    /** Une ligne de la table — miroir Java de ce que le scanner bytecode extrait. */
    public static final class Entry {
        public final String mcVersion;
        /** Nom de classe relatif au package Mixin (ex. {@code v26_1.hud.HudExtractArmorMixin261}). */
        public final String mixinEntry;
        /** {@code null} pour une entrée {@link #always} — jamais gatée. */
        public final HookPoint hookPoint;

        Entry(String mcVersion, String mixinEntry, HookPoint hookPoint) {
            this.mcVersion = mcVersion;
            this.mixinEntry = mixinEntry;
            this.hookPoint = hookPoint;
        }

        public String simpleName() {
            return mixinEntry.substring(mixinEntry.lastIndexOf('.') + 1);
        }
    }

    private static final Map<String, List<Entry>> BY_VERSION = new LinkedHashMap<>();

    /** Mixin gaté par un HookPoint — voir la javadoc de classe. */
    private static void gate(String mcVersion, String mixinEntry, HookPoint point) {
        BY_VERSION.computeIfAbsent(mcVersion, k -> new ArrayList<>())
                  .add(new Entry(mcVersion, mixinEntry, point));
    }

    /** Mixin d'infrastructure, toujours tissé — voir la javadoc de classe. */
    private static void always(String mcVersion, String mixinEntry) {
        BY_VERSION.computeIfAbsent(mcVersion, k -> new ArrayList<>())
                  .add(new Entry(mcVersion, mixinEntry, null));
    }

    static {
        // ══════════════════════════════════════════════════════════════════
        // 26.1.2 — MC non obfusqué (voir VersionProfileRegistry)
        // ══════════════════════════════════════════════════════════════════

        // ── Infrastructure : hub de rendu, cycle d'écran, accessors ────────
        // Jamais gatés — ce sont eux qui font exister notre UI et nos accès
        // typés au jeu ; les gater reviendrait à gater l'agent lui-même.
        always("26.1.2", "v26_1.core.TitleScreenMixin261");
        always("26.1.2", "v26_1.core.MinecraftAccessor261");
        always("26.1.2", "v26_1.core.GlobalUiRenderMixin261");
        always("26.1.2", "v26_1.core.GlobalUiPresentMixin261");
        always("26.1.2", "v26_1.core.GuiFlushMixin261");
        always("26.1.2", "v26_1.core.OptionsAccessor261");
        always("26.1.2", "v26_1.core.OptionInstanceAccessor261");
        always("26.1.2", "v26_1.core.KeyMappingAccessor261");
        always("26.1.2", "v26_1.core.FoodDataAccessor261");
        always("26.1.2", "v26_1.core.DataComponentsAccessor261");
        always("26.1.2", "v26_1.core.ServerDataAccessor261");
        always("26.1.2", "v26_1.core.ChatComponentAccessor261");
        always("26.1.2", "v26_1.core.FogRendererAccessor261");
        always("26.1.2", "v26_1.core.GuiGraphicsExtractorAccessor261");
        always("26.1.2", "v26_1.render.VertexFormatElementAccessor261");
        always("26.1.2", "v26_1.render.GameRendererAccessor261");
        always("26.1.2", "v26_1.render.GuiRendererAccessor261");
        always("26.1.2", "v26_1.render.RenderPipelinesAccessor261");
        always("26.1.2", "v26_1.render.GuiGraphicsExtractorInvoker261");

        // ── Freelook : VOLONTAIREMENT non gaté (2026-08-24, [[project_mc_261_port]] §11) ──
        // Ces deux mixins dispatchent bien via HookPoint/VanillaHookRegistry
        // (dispatch()/dispatchValue() sont sûrs sans handler enregistré), MAIS
        // ne doivent PAS être gatés : Camera est chargée dans Minecraft.<init>,
        // avant que FreelookModule (enregistré au premier GameRenderer.render())
        // n'ait eu la chance de s'enregistrer — violant la garantie d'ordre du
        // gating. Résultat observé : CameraFreelookMixin261 partait sur le
        // chemin de retransform tardif, où la classe synthétique
        // Args$1ArgsClassGenerator (@ModifyArgs) ne se liait pas au runtime →
        // NoSuchMethodError sur Args$1.of(float,float) dans
        // Camera.alignWithEntity. Les laisser tisser inconditionnellement
        // évite le retransform et le bug.
        always("26.1.2", "v26_1.freelook.CameraFreelookMixin261");
        always("26.1.2", "v26_1.freelook.MouseHandlerFreelookMixin261");

        // ── HUD (fabric-rendering-v1) ─────────────────────────────────────
        gate("26.1.2", "v26_1.hud.HudExtractCameraOverlayMixin261", HookPoint.HUD_EXTRACT_CAMERA_OVERLAY);
        gate("26.1.2", "v26_1.hud.HudExtractCrosshairMixin261", HookPoint.HUD_EXTRACT_CROSSHAIR);
        gate("26.1.2", "v26_1.hud.HudExtractSpectatorHotbarMixin261", HookPoint.HUD_EXTRACT_HOTBAR);
        gate("26.1.2", "v26_1.hud.HudExtractItemHotbarMixin261", HookPoint.HUD_EXTRACT_ITEM_HOTBAR);
        gate("26.1.2", "v26_1.hud.HudExtractArmorMixin261", HookPoint.HUD_EXTRACT_ARMOR);
        gate("26.1.2", "v26_1.hud.HudExtractHeartsMixin261", HookPoint.HUD_EXTRACT_HEARTS);
        gate("26.1.2", "v26_1.hud.HudExtractFoodMixin261", HookPoint.HUD_EXTRACT_FOOD);
        gate("26.1.2", "v26_1.hud.HudExtractAirBubblesMixin261", HookPoint.HUD_EXTRACT_AIR_BUBBLES);
        gate("26.1.2", "v26_1.hud.HudExtractVehicleHealthMixin261", HookPoint.HUD_EXTRACT_VEHICLE_HEALTH);
        gate("26.1.2", "v26_1.hud.HudExtractContextualBarBackgroundMixin261", HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND);
        gate("26.1.2", "v26_1.hud.HudExtractExperienceLevelMixin261", HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL);
        gate("26.1.2", "v26_1.hud.HudExtractSelectedItemNameMixin261", HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME);
        gate("26.1.2", "v26_1.hud.HudExtractSpectatorActionMixin261", HookPoint.HUD_EXTRACT_SPECTATOR_ACTION);
        gate("26.1.2", "v26_1.hud.HudExtractEffectsMixin261", HookPoint.HUD_EXTRACT_EFFECTS);
        gate("26.1.2", "v26_1.hud.HudExtractBossOverlayMixin261", HookPoint.HUD_EXTRACT_BOSS_OVERLAY);
        gate("26.1.2", "v26_1.hud.HudExtractSleepOverlayMixin261", HookPoint.HUD_EXTRACT_SLEEP_OVERLAY);
        gate("26.1.2", "v26_1.hud.HudExtractDemoOverlayMixin261", HookPoint.HUD_EXTRACT_DEMO_OVERLAY);
        gate("26.1.2", "v26_1.hud.HudExtractScoreboardSidebarMixin261", HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR);
        gate("26.1.2", "v26_1.hud.HudExtractOverlayMessageMixin261", HookPoint.HUD_EXTRACT_OVERLAY_MESSAGE);
        gate("26.1.2", "v26_1.hud.HudExtractTitleMixin261", HookPoint.HUD_EXTRACT_TITLE);
        gate("26.1.2", "v26_1.hud.HudExtractChatMixin261", HookPoint.HUD_EXTRACT_CHAT);
        gate("26.1.2", "v26_1.hud.HudExtractTabListMixin261", HookPoint.HUD_EXTRACT_TAB_LIST);
        gate("26.1.2", "v26_1.hud.HudExtractTextureOverlayMixin261", HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);

        // ── Brouillard par environnement ──────────────────────────────────
        gate("26.1.2", "v26_1.fog.FogSetupAtmosphericMixin261", HookPoint.FOG_SETUP_ATMOSPHERIC);
        gate("26.1.2", "v26_1.fog.FogSetupWaterMixin261", HookPoint.FOG_SETUP_WATER);
        gate("26.1.2", "v26_1.fog.FogSetupLavaMixin261", HookPoint.FOG_SETUP_LAVA);
        gate("26.1.2", "v26_1.fog.FogSetupPowderedSnowMixin261", HookPoint.FOG_SETUP_POWDERED_SNOW);
        gate("26.1.2", "v26_1.fog.FogSetupBlindnessMixin261", HookPoint.FOG_SETUP_BLINDNESS);
        gate("26.1.2", "v26_1.fog.FogSetupDarknessMixin261", HookPoint.FOG_SETUP_DARKNESS);

        // ── Rendu de texte/décorations d'item ─────────────────────────────
        gate("26.1.2", "v26_1.render.ItemDecorationsExtractMixin261", HookPoint.ITEM_DECORATIONS_EXTRACT);
        gate("26.1.2", "v26_1.render.AdvancementToastExtractMixin261", HookPoint.ADVANCEMENT_TOAST_EXTRACT);
        gate("26.1.2", "v26_1.render.SubtitleOverlayExtractMixin261", HookPoint.SUBTITLE_OVERLAY_EXTRACT);

        // ── Monde/niveau ───────────────────────────────────────────────────
        gate("26.1.2", "v26_1.level.LevelBlockOutlineExtractMixin261", HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT);
        gate("26.1.2", "v26_1.level.LevelExtractMixin261", HookPoint.LEVEL_EXTRACT);
        gate("26.1.2", "v26_1.level.GuiRenderStateResetMixin261", HookPoint.GUI_RENDER_STATE_RESET);
        gate("26.1.2", "v26_1.level.GameRenderExtractMixin261", HookPoint.GAME_RENDER_EXTRACT);

        // ── Écran/Input ────────────────────────────────────────────────────
        gate("26.1.2", "v26_1.screen.ScreenInitMixin261", HookPoint.SCREEN_INIT);
        gate("26.1.2", "v26_1.screen.MouseScrollMixin261", HookPoint.MOUSE_SCROLL);
        gate("26.1.2", "v26_1.screen.KeyboardKeyMixin261", HookPoint.KEYBOARD_KEY);
        gate("26.1.2", "v26_1.screen.ScreenSetMixin261", HookPoint.SCREEN_SET);
        gate("26.1.2", "v26_1.screen.ScreenAfterBackgroundExtractMixin261", HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP);

        // ── Raccourcis clavier ─────────────────────────────────────────────
        gate("26.1.2", "v26_1.keybind.KeybindRegisterMixin261", HookPoint.KEYBIND_REGISTER);
        gate("26.1.2", "v26_1.keybind.KeybindCategoryRegisterMixin261", HookPoint.KEYBIND_CATEGORY_REGISTER);

        // ── Chat / combat ──────────────────────────────────────────────────
        gate("26.1.2", "v26_1.chat.ChatReceiveMixin261", HookPoint.CHAT_RECEIVE);
        gate("26.1.2", "v26_1.chat.ChatSendMixin261", HookPoint.CHAT_SEND);
        gate("26.1.2", "v26_1.chat.CommandSendMixin261", HookPoint.COMMAND_SEND);
        gate("26.1.2", "v26_1.chat.CommandTreeMixin261", HookPoint.COMMAND_TREE_RECEIVE);
        gate("26.1.2", "v26_1.combat.PiercingAttackMixin261", HookPoint.PIERCING_ATTACK);

        // ── Item ───────────────────────────────────────────────────────────
        gate("26.1.2", "v26_1.item.ItemTooltipMixin261", HookPoint.ITEM_TOOLTIP);

        // ── Cycle de vie tick/chunk/entité ────────────────────────────────
        gate("26.1.2", "v26_1.lifecycle.ClientTickMixin261", HookPoint.CLIENT_TICK);
        gate("26.1.2", "v26_1.lifecycle.EntityLoadMixin261", HookPoint.ENTITY_LOAD);
        gate("26.1.2", "v26_1.lifecycle.EntityUnloadMixin261", HookPoint.ENTITY_UNLOAD);
        gate("26.1.2", "v26_1.lifecycle.ClientLevelLoadMixin261", HookPoint.CLIENT_LEVEL_LOAD);

        // ── Horloge ────────────────────────────────────────────────────────
        gate("26.1.2", "v26_1.clock.ClockTotalTicksMixin261", HookPoint.CLOCK_TOTAL_TICKS);

        // ══════════════════════════════════════════════════════════════════
        // 1.21.11 — MC OBFUSQUÉ : cibles en noms Yarn, méthodes traduites par
        // le refmap (voir LauncherMixinService.REFMAP_ENTRIES). Dégel de la
        // tranche (2026-09-11). Tout le catalogue 26.1.2 y est, SAUF :
        // HUD_EXTRACT_CHAT (HUD dans la passe GUI, étape dédiée) et les
        // accessors d'AccessPoint.
        // ══════════════════════════════════════════════════════════════════

        // ── Infrastructure : hub de rendu, écran-titre ─────────────────────
        always("1.21.11", "v1_21_11.core.TitleScreenMixin1211");
        always("1.21.11", "v1_21_11.core.GlobalUiRenderMixin1211");
        always("1.21.11", "v1_21_11.core.GlobalUiPresentMixin1211");
        always("1.21.11", "v1_21_11.core.GuiFlushMixin1211");

        // ── Freelook : non gaté, même raison qu'en 26.1.2 (voir plus haut) —
        // Camera/Mouse se chargent avant l'enregistrement de FreelookModule.
        always("1.21.11", "v1_21_11.freelook.CameraAccessor1211");
        always("1.21.11", "v1_21_11.freelook.CameraFreelookMixin1211");
        always("1.21.11", "v1_21_11.freelook.EntityInvoker1211");
        always("1.21.11", "v1_21_11.freelook.MouseHandlerFreelookMixin1211");

        // ── HUD ────────────────────────────────────────────────────────────
        gate("1.21.11", "v1_21_11.hud.HudExtractCrosshairMixin1211", HookPoint.HUD_EXTRACT_CROSSHAIR);
        gate("1.21.11", "v1_21_11.hud.HudExtractEffectsMixin1211", HookPoint.HUD_EXTRACT_EFFECTS);
        gate("1.21.11", "v1_21_11.hud.HudExtractTextureOverlayMixin1211", HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);
        gate("1.21.11", "v1_21_11.hud.HudExtractCameraOverlayMixin1211", HookPoint.HUD_EXTRACT_CAMERA_OVERLAY);
        gate("1.21.11", "v1_21_11.hud.HudExtractSpectatorHotbarMixin1211", HookPoint.HUD_EXTRACT_HOTBAR);
        gate("1.21.11", "v1_21_11.hud.HudExtractItemHotbarMixin1211", HookPoint.HUD_EXTRACT_ITEM_HOTBAR);
        gate("1.21.11", "v1_21_11.hud.HudExtractArmorMixin1211", HookPoint.HUD_EXTRACT_ARMOR);
        gate("1.21.11", "v1_21_11.hud.HudExtractHeartsMixin1211", HookPoint.HUD_EXTRACT_HEARTS);
        gate("1.21.11", "v1_21_11.hud.HudExtractFoodMixin1211", HookPoint.HUD_EXTRACT_FOOD);
        gate("1.21.11", "v1_21_11.hud.HudExtractAirBubblesMixin1211", HookPoint.HUD_EXTRACT_AIR_BUBBLES);
        gate("1.21.11", "v1_21_11.hud.HudExtractVehicleHealthMixin1211", HookPoint.HUD_EXTRACT_VEHICLE_HEALTH);
        gate("1.21.11", "v1_21_11.hud.HudExtractContextualBarBackgroundMixin1211", HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND);
        gate("1.21.11", "v1_21_11.hud.HudExtractExperienceLevelMixin1211", HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL);
        gate("1.21.11", "v1_21_11.hud.HudExtractSelectedItemNameMixin1211", HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME);
        gate("1.21.11", "v1_21_11.hud.HudExtractSpectatorActionMixin1211", HookPoint.HUD_EXTRACT_SPECTATOR_ACTION);
        gate("1.21.11", "v1_21_11.hud.HudExtractBossOverlayMixin1211", HookPoint.HUD_EXTRACT_BOSS_OVERLAY);
        gate("1.21.11", "v1_21_11.hud.HudExtractSleepOverlayMixin1211", HookPoint.HUD_EXTRACT_SLEEP_OVERLAY);
        gate("1.21.11", "v1_21_11.hud.HudExtractDemoOverlayMixin1211", HookPoint.HUD_EXTRACT_DEMO_OVERLAY);
        gate("1.21.11", "v1_21_11.hud.HudExtractScoreboardSidebarMixin1211", HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR);
        gate("1.21.11", "v1_21_11.hud.HudExtractOverlayMessageMixin1211", HookPoint.HUD_EXTRACT_OVERLAY_MESSAGE);
        gate("1.21.11", "v1_21_11.hud.HudExtractTitleMixin1211", HookPoint.HUD_EXTRACT_TITLE);
        gate("1.21.11", "v1_21_11.hud.HudExtractTabListMixin1211", HookPoint.HUD_EXTRACT_TAB_LIST);
        // HUD_EXTRACT_CHAT : le HUD de l'agent passe désormais par la passe GUI
        // de vanilla sur cette version aussi. Son handler est indépendant de la
        // version — c'est VanillaGuiSink1211 qui parle à l'état de GUI 1.21.11
        // (voir VanillaGuiSinks).
        gate("1.21.11", "v1_21_11.hud.HudExtractChatMixin1211", HookPoint.HUD_EXTRACT_CHAT);
        // Accès aux données du jeu : quatre champs privés (le reste est public
        // sur cette version, voir AccessorBindings1211). Jamais gatés — un
        // accessor non tissé laisse l'objet sans notre interface.
        always("1.21.11", "v1_21_11.core.MinecraftClientAccessor1211");
        always("1.21.11", "v1_21_11.core.HungerManagerAccessor1211");
        always("1.21.11", "v1_21_11.chat.ChatHudAccessor1211");
        always("1.21.11", "v1_21_11.option.SimpleOptionAccessor1211");

        // Capte le GuiRenderState porté par chaque DrawContext — infrastructure
        // du chemin ci-dessus, donc jamais gatée (voir sa javadoc : champ privé,
        // ni réflexion ni @Accessor possibles).
        always("1.21.11", "v1_21_11.render.DrawContextStateMixin1211");

        // ── Brouillard — contrat neutre, voir FogOverride ──────────────────
        always("1.21.11", "v1_21_11.fog.FogDataAccessor1211");
        gate("1.21.11", "v1_21_11.fog.FogSetupAtmosphericMixin1211", HookPoint.FOG_SETUP_ATMOSPHERIC);
        gate("1.21.11", "v1_21_11.fog.FogSetupWaterMixin1211", HookPoint.FOG_SETUP_WATER);
        gate("1.21.11", "v1_21_11.fog.FogSetupLavaMixin1211", HookPoint.FOG_SETUP_LAVA);
        gate("1.21.11", "v1_21_11.fog.FogSetupPowderedSnowMixin1211", HookPoint.FOG_SETUP_POWDERED_SNOW);
        gate("1.21.11", "v1_21_11.fog.FogSetupBlindnessMixin1211", HookPoint.FOG_SETUP_BLINDNESS);
        gate("1.21.11", "v1_21_11.fog.FogSetupDarknessMixin1211", HookPoint.FOG_SETUP_DARKNESS);

        // ── Rendu de texte/décorations, toasts, sous-titres ────────────────
        gate("1.21.11", "v1_21_11.render.ItemDecorationsExtractMixin1211", HookPoint.ITEM_DECORATIONS_EXTRACT);
        gate("1.21.11", "v1_21_11.render.AdvancementToastExtractMixin1211", HookPoint.ADVANCEMENT_TOAST_EXTRACT);
        gate("1.21.11", "v1_21_11.render.SubtitleOverlayExtractMixin1211", HookPoint.SUBTITLE_OVERLAY_EXTRACT);

        // ── Monde / frame ──────────────────────────────────────────────────
        gate("1.21.11", "v1_21_11.level.LevelBlockOutlineExtractMixin1211", HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT);
        gate("1.21.11", "v1_21_11.level.LevelExtractMixin1211", HookPoint.LEVEL_EXTRACT);
        gate("1.21.11", "v1_21_11.level.GuiRenderStateResetMixin1211", HookPoint.GUI_RENDER_STATE_RESET);
        gate("1.21.11", "v1_21_11.level.GameRenderExtractMixin1211", HookPoint.GAME_RENDER_EXTRACT);

        // ── Écrans / entrées ───────────────────────────────────────────────
        gate("1.21.11", "v1_21_11.screen.ScreenInitMixin1211", HookPoint.SCREEN_INIT);
        gate("1.21.11", "v1_21_11.screen.MouseScrollMixin1211", HookPoint.MOUSE_SCROLL);
        gate("1.21.11", "v1_21_11.screen.KeyboardKeyMixin1211", HookPoint.KEYBOARD_KEY);
        gate("1.21.11", "v1_21_11.screen.ScreenSetMixin1211", HookPoint.SCREEN_SET);
        gate("1.21.11", "v1_21_11.screen.ScreenAfterBackgroundExtractMixin1211", HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP);

        // ── Touches ────────────────────────────────────────────────────────
        gate("1.21.11", "v1_21_11.keybind.KeybindRegisterMixin1211", HookPoint.KEYBIND_REGISTER);
        gate("1.21.11", "v1_21_11.keybind.KeybindCategoryRegisterMixin1211", HookPoint.KEYBIND_CATEGORY_REGISTER);

        // ── Chat / combat / objets ─────────────────────────────────────────
        gate("1.21.11", "v1_21_11.chat.ChatReceiveMixin1211", HookPoint.CHAT_RECEIVE);
        gate("1.21.11", "v1_21_11.chat.ChatSendMixin1211", HookPoint.CHAT_SEND);
        gate("1.21.11", "v1_21_11.chat.CommandSendMixin1211", HookPoint.COMMAND_SEND);
        gate("1.21.11", "v1_21_11.chat.CommandTreeMixin1211", HookPoint.COMMAND_TREE_RECEIVE);
        gate("1.21.11", "v1_21_11.combat.PiercingAttackMixin1211", HookPoint.PIERCING_ATTACK);
        gate("1.21.11", "v1_21_11.item.ItemTooltipMixin1211", HookPoint.ITEM_TOOLTIP);

        // ── Cycle de vie ───────────────────────────────────────────────────
        gate("1.21.11", "v1_21_11.lifecycle.ClientTickMixin1211", HookPoint.CLIENT_TICK);
        gate("1.21.11", "v1_21_11.lifecycle.EntityLoadMixin1211", HookPoint.ENTITY_LOAD);
        gate("1.21.11", "v1_21_11.lifecycle.EntityUnloadMixin1211", HookPoint.ENTITY_UNLOAD);
        gate("1.21.11", "v1_21_11.lifecycle.ClientLevelLoadMixin1211", HookPoint.CLIENT_LEVEL_LOAD);

        // ── Horloge — même HookPoint qu'en 26.1.2, sur World.getTimeOfDay ──
        gate("1.21.11", "v1_21_11.clock.ClockTotalTicksMixin1211", HookPoint.CLOCK_TOTAL_TICKS);

        // ══════════════════════════════════════════════════════════════════
        // 1.8.9 — MC OBFUSQUÉ, noms Yarn LEGACY (Legacy Fabric), vanilla seul
        // (docs/LauncherAgent/v1.8.9/README.md). Méthodes traduites par le
        // refmap, cibles @At par REFMAP_REMAP. Tranche encore GELÉE : voir
        // VersionProfileRegistry pour ce qui manque avant de l'activer.
        //
        // Absents faute d'équivalent 1.8.9 : HUD_EXTRACT_EFFECTS (aucun effet
        // affiché dans le HUD), HUD_EXTRACT_CHAT (passe GUI Blaze3D),
        // SUBTITLE_OVERLAY_EXTRACT, GUI_RENDER_STATE_RESET, MOUSE_SCROLL (pas
        // de défilement centralisé dans Screen), KEYBIND_CATEGORY_REGISTER
        // (catégories = simples chaînes), COMMAND_TREE_RECEIVE (pas de
        // brigadier), PIERCING_ATTACK. Reportés : FOG_SETUP_* et
        // ADVANCEMENT_TOAST_EXTRACT (données du joueur via points d'accès),
        // freelook.
        // ══════════════════════════════════════════════════════════════════

        // ── Infrastructure ────────────────────────────────────────────────
        always("1.8.9", "v1_8_9.core.TitleScreenMixin189");
        always("1.8.9", "v1_8_9.core.GlobalUiRenderMixin189");

        // ── HUD ────────────────────────────────────────────────────────────
        gate("1.8.9", "v1_8_9.hud.HudExtractCrosshairMixin189", HookPoint.HUD_EXTRACT_CROSSHAIR);
        gate("1.8.9", "v1_8_9.hud.HudExtractTextureOverlayMixin189", HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);
        gate("1.8.9", "v1_8_9.hud.HudExtractCameraOverlayMixin189", HookPoint.HUD_EXTRACT_CAMERA_OVERLAY);
        gate("1.8.9", "v1_8_9.hud.HudExtractSpectatorHotbarMixin189", HookPoint.HUD_EXTRACT_HOTBAR);
        gate("1.8.9", "v1_8_9.hud.HudExtractItemHotbarMixin189", HookPoint.HUD_EXTRACT_ITEM_HOTBAR);
        gate("1.8.9", "v1_8_9.hud.HudExtractArmorMixin189", HookPoint.HUD_EXTRACT_ARMOR);
        gate("1.8.9", "v1_8_9.hud.HudExtractHeartsMixin189", HookPoint.HUD_EXTRACT_HEARTS);
        gate("1.8.9", "v1_8_9.hud.HudExtractFoodMixin189", HookPoint.HUD_EXTRACT_FOOD);
        gate("1.8.9", "v1_8_9.hud.HudExtractAirBubblesMixin189", HookPoint.HUD_EXTRACT_AIR_BUBBLES);
        gate("1.8.9", "v1_8_9.hud.HudExtractVehicleHealthMixin189", HookPoint.HUD_EXTRACT_VEHICLE_HEALTH);
        gate("1.8.9", "v1_8_9.hud.HudExtractContextualBarBackgroundMixin189", HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND);
        gate("1.8.9", "v1_8_9.hud.HudExtractExperienceLevelMixin189", HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL);
        gate("1.8.9", "v1_8_9.hud.HudExtractSelectedItemNameMixin189", HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME);
        gate("1.8.9", "v1_8_9.hud.HudExtractSpectatorActionMixin189", HookPoint.HUD_EXTRACT_SPECTATOR_ACTION);
        gate("1.8.9", "v1_8_9.hud.HudExtractBossOverlayMixin189", HookPoint.HUD_EXTRACT_BOSS_OVERLAY);
        gate("1.8.9", "v1_8_9.hud.HudExtractSleepOverlayMixin189", HookPoint.HUD_EXTRACT_SLEEP_OVERLAY);
        gate("1.8.9", "v1_8_9.hud.HudExtractDemoOverlayMixin189", HookPoint.HUD_EXTRACT_DEMO_OVERLAY);
        gate("1.8.9", "v1_8_9.hud.HudExtractScoreboardSidebarMixin189", HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR);
        gate("1.8.9", "v1_8_9.hud.HudExtractOverlayMessageMixin189", HookPoint.HUD_EXTRACT_OVERLAY_MESSAGE);
        gate("1.8.9", "v1_8_9.hud.HudExtractTitleMixin189", HookPoint.HUD_EXTRACT_TITLE);
        gate("1.8.9", "v1_8_9.hud.HudExtractTabListMixin189", HookPoint.HUD_EXTRACT_TAB_LIST);

        // ── Rendu / monde / frame ──────────────────────────────────────────
        gate("1.8.9", "v1_8_9.render.ItemDecorationsExtractMixin189", HookPoint.ITEM_DECORATIONS_EXTRACT);
        gate("1.8.9", "v1_8_9.level.LevelBlockOutlineExtractMixin189", HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT);
        gate("1.8.9", "v1_8_9.level.LevelExtractMixin189", HookPoint.LEVEL_EXTRACT);
        gate("1.8.9", "v1_8_9.level.GameRenderExtractMixin189", HookPoint.GAME_RENDER_EXTRACT);

        // ── Écrans / entrées / touches ─────────────────────────────────────
        gate("1.8.9", "v1_8_9.screen.ScreenInitMixin189", HookPoint.SCREEN_INIT);
        gate("1.8.9", "v1_8_9.screen.KeyboardKeyMixin189", HookPoint.KEYBOARD_KEY);
        gate("1.8.9", "v1_8_9.screen.ScreenSetMixin189", HookPoint.SCREEN_SET);
        gate("1.8.9", "v1_8_9.screen.ScreenAfterBackgroundExtractMixin189", HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP);
        gate("1.8.9", "v1_8_9.keybind.KeybindRegisterMixin189", HookPoint.KEYBIND_REGISTER);

        // ── Chat / objets ──────────────────────────────────────────────────
        gate("1.8.9", "v1_8_9.chat.ChatReceiveMixin189", HookPoint.CHAT_RECEIVE);
        gate("1.8.9", "v1_8_9.chat.ChatSendMixin189", HookPoint.CHAT_SEND);
        gate("1.8.9", "v1_8_9.chat.CommandSendMixin189", HookPoint.COMMAND_SEND);
        gate("1.8.9", "v1_8_9.item.ItemTooltipMixin189", HookPoint.ITEM_TOOLTIP);

        // ── Cycle de vie ───────────────────────────────────────────────────
        gate("1.8.9", "v1_8_9.lifecycle.ClientTickMixin189", HookPoint.CLIENT_TICK);
        gate("1.8.9", "v1_8_9.lifecycle.EntityLoadMixin189", HookPoint.ENTITY_LOAD);
        gate("1.8.9", "v1_8_9.lifecycle.EntityUnloadMixin189", HookPoint.ENTITY_UNLOAD);
        gate("1.8.9", "v1_8_9.lifecycle.ClientLevelLoadMixin189", HookPoint.CLIENT_LEVEL_LOAD);

        // ── Horloge — LevelProperties.getTimeOfDay lue par World.getSkyAngle ──
        gate("1.8.9", "v1_8_9.clock.ClockTotalTicksMixin189", HookPoint.CLOCK_TOTAL_TICKS);
    }

    /**
     * Entrées déclarées pour cette version MC, dans l'ordre de la table
     * (l'ordre du tableau {@code client} généré en dépend). Liste vide si la
     * version n'a aucun mixin apimixin.
     *
     * <p>API Java de CONFORT — le chemin réellement emprunté au démarrage est
     * le scan bytecode (voir la javadoc de classe), qui ne charge jamais cette
     * classe. Utile en test/diagnostic, jamais dans {@code premain}.
     */
    public static List<Entry> entries(String mcVersion) {
        List<Entry> list = BY_VERSION.get(mcVersion);
        return list != null ? list : new ArrayList<Entry>();
    }

    /** Versions MC ayant au moins une entrée déclarée ici. */
    public static java.util.Set<String> declaredVersions() {
        return BY_VERSION.keySet();
    }
}
