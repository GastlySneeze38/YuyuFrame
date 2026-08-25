package com.yuyuframe.launcheragent.apimixin;

import java.util.HashMap;
import java.util.Map;

/**
 * Associe le nom simple d'un mixin {@code apimixin/} à L'UNIQUE {@link
 * HookPoint} qu'il dispatche — voir ROADMAP-agent.md §3.2 ("un mixin par
 * HookPoint", déjà imposé par la javadoc de {@link HookPoint}).
 *
 * Consulté par {@code LauncherMixinConfigPlugin.shouldApplyMixin()} : un
 * mixin apimixin PRÉSENT ici ne weave que si {@link
 * VanillaHookRegistry#isUsed} renvoie {@code true} pour son HookPoint (au
 * moins un module a appelé {@code VanillaHookRegistry.register(...)} pour ce
 * point avant le premier chargement de la classe cible — l'ordre est
 * garanti : l'enregistrement des modules se fait au démarrage de l'agent,
 * bien avant que Minecraft charge la moindre classe cible de rendu/tick).
 *
 * Un mixin apimixin ABSENT d'ici (mixins "hub"/infrastructure comme {@code
 * TitleScreenMixin261}/{@code GlobalUiRenderMixin261}/{@code
 * GlobalUiPresentMixin261}/{@code GuiFlushMixin261}, ou interfaces {@code
 * @Accessor}/{@code @Invoker}) weave TOUJOURS — {@code shouldApplyMixin()}
 * n'applique la gate déclarative qu'aux mixins listés ici.
 *
 * Mise à jour manuelle à chaque nouveau mixin {@code apimixin/} backé par un
 * {@link HookPoint} — mécanique mais volontairement explicite (pas de
 * convention de nommage auto-dérivée : {@code HUD_EXTRACT_HOTBAR} →
 * {@code HudExtractSpectatorHotbarMixin261} n'est déjà pas un simple
 * CamelCase de l'entrée d'enum, voir son commentaire dans {@link HookPoint}).
 */
public final class MixinHookPointRegistry {

    private MixinHookPointRegistry() {}

    private static final Map<String, HookPoint> BY_SIMPLE_NAME = new HashMap<>();

    private static void put(String simpleName, HookPoint point) {
        BY_SIMPLE_NAME.put(simpleName, point);
    }

    static {
        // ── HUD (fabric-rendering-v1) ─────────────────────────────────────
        put("HudExtractCameraOverlayMixin261", HookPoint.HUD_EXTRACT_CAMERA_OVERLAY);
        put("HudExtractCrosshairMixin261", HookPoint.HUD_EXTRACT_CROSSHAIR);
        put("HudExtractSpectatorHotbarMixin261", HookPoint.HUD_EXTRACT_HOTBAR);
        put("HudExtractItemHotbarMixin261", HookPoint.HUD_EXTRACT_ITEM_HOTBAR);
        put("HudExtractArmorMixin261", HookPoint.HUD_EXTRACT_ARMOR);
        put("HudExtractHeartsMixin261", HookPoint.HUD_EXTRACT_HEARTS);
        put("HudExtractFoodMixin261", HookPoint.HUD_EXTRACT_FOOD);
        put("HudExtractAirBubblesMixin261", HookPoint.HUD_EXTRACT_AIR_BUBBLES);
        put("HudExtractVehicleHealthMixin261", HookPoint.HUD_EXTRACT_VEHICLE_HEALTH);
        put("HudExtractContextualBarBackgroundMixin261", HookPoint.HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND);
        put("HudExtractExperienceLevelMixin261", HookPoint.HUD_EXTRACT_EXPERIENCE_LEVEL);
        put("HudExtractSelectedItemNameMixin261", HookPoint.HUD_EXTRACT_SELECTED_ITEM_NAME);
        put("HudExtractSpectatorActionMixin261", HookPoint.HUD_EXTRACT_SPECTATOR_ACTION);
        put("HudExtractEffectsMixin261", HookPoint.HUD_EXTRACT_EFFECTS);
        put("HudExtractBossOverlayMixin261", HookPoint.HUD_EXTRACT_BOSS_OVERLAY);
        put("HudExtractSleepOverlayMixin261", HookPoint.HUD_EXTRACT_SLEEP_OVERLAY);
        put("HudExtractDemoOverlayMixin261", HookPoint.HUD_EXTRACT_DEMO_OVERLAY);
        put("HudExtractScoreboardSidebarMixin261", HookPoint.HUD_EXTRACT_SCOREBOARD_SIDEBAR);
        put("HudExtractOverlayMessageMixin261", HookPoint.HUD_EXTRACT_OVERLAY_MESSAGE);
        put("HudExtractTitleMixin261", HookPoint.HUD_EXTRACT_TITLE);
        put("HudExtractChatMixin261", HookPoint.HUD_EXTRACT_CHAT);
        put("HudExtractTabListMixin261", HookPoint.HUD_EXTRACT_TAB_LIST);
        put("HudExtractTextureOverlayMixin261", HookPoint.HUD_EXTRACT_TEXTURE_OVERLAY);

        // ── Brouillard par environnement ──────────────────────────────────
        put("FogSetupAtmosphericMixin261", HookPoint.FOG_SETUP_ATMOSPHERIC);
        put("FogSetupWaterMixin261", HookPoint.FOG_SETUP_WATER);
        put("FogSetupLavaMixin261", HookPoint.FOG_SETUP_LAVA);
        put("FogSetupPowderedSnowMixin261", HookPoint.FOG_SETUP_POWDERED_SNOW);
        put("FogSetupBlindnessMixin261", HookPoint.FOG_SETUP_BLINDNESS);
        put("FogSetupDarknessMixin261", HookPoint.FOG_SETUP_DARKNESS);

        // ── Rendu de texte/décorations d'item ─────────────────────────────
        put("ItemDecorationsExtractMixin261", HookPoint.ITEM_DECORATIONS_EXTRACT);
        put("AdvancementToastExtractMixin261", HookPoint.ADVANCEMENT_TOAST_EXTRACT);
        put("SubtitleOverlayExtractMixin261", HookPoint.SUBTITLE_OVERLAY_EXTRACT);

        // ── Monde/niveau ───────────────────────────────────────────────────
        put("LevelBlockOutlineExtractMixin261", HookPoint.LEVEL_BLOCK_OUTLINE_EXTRACT);
        put("LevelExtractMixin261", HookPoint.LEVEL_EXTRACT);
        put("GuiRenderStateResetMixin261", HookPoint.GUI_RENDER_STATE_RESET);
        put("GameRenderExtractMixin261", HookPoint.GAME_RENDER_EXTRACT);

        // ── Écran/Input ────────────────────────────────────────────────────
        put("ScreenInitMixin261", HookPoint.SCREEN_INIT);
        put("MouseScrollMixin261", HookPoint.MOUSE_SCROLL);
        put("KeyboardKeyMixin261", HookPoint.KEYBOARD_KEY);
        put("ScreenSetMixin261", HookPoint.SCREEN_SET);
        put("ScreenAfterBackgroundExtractMixin261", HookPoint.CONTAINER_SCREEN_EXTRACT_TOOLTIP);

        // ── Raccourcis clavier ─────────────────────────────────────────────
        put("KeybindRegisterMixin261", HookPoint.KEYBIND_REGISTER);
        put("KeybindCategoryRegisterMixin261", HookPoint.KEYBIND_CATEGORY_REGISTER);

        // ── Chat ───────────────────────────────────────────────────────────
        put("ChatReceiveMixin261", HookPoint.CHAT_RECEIVE);
        put("ChatSendMixin261", HookPoint.CHAT_SEND);

        // ── Item ───────────────────────────────────────────────────────────
        put("ItemTooltipMixin261", HookPoint.ITEM_TOOLTIP);

        // ── Cycle de vie tick/chunk/entité ────────────────────────────────
        put("ClientTickMixin261", HookPoint.CLIENT_TICK);
        put("EntityLoadMixin261", HookPoint.ENTITY_LOAD);
        put("EntityUnloadMixin261", HookPoint.ENTITY_UNLOAD);
        put("ClientLevelLoadMixin261", HookPoint.CLIENT_LEVEL_LOAD);

        // ── Horloge ────────────────────────────────────────────────────────
        put("ClockTotalTicksMixin261", HookPoint.CLOCK_TOTAL_TICKS);

        // ── Freelook : PAS ici (2026-08-24, voir [[project_mc_261_port]] §11) ──
        // MouseHandlerFreelookMixin261/CameraFreelookMixin261 dispatchent bien
        // via HookPoint/VanillaHookRegistry (dispatch()/dispatchValue() sont
        // sûrs sans handler enregistré — retournent false/null), MAIS ne
        // doivent PAS être gatés ici : Camera est chargée dans Minecraft.<init>,
        // avant que FreelookModule (enregistré au premier GameRenderer.render(),
        // voir GlobalUiRenderMixin261/ModuleRegistry.all()) n'ait eu la chance
        // de s'enregistrer — violant la garantie d'ordre documentée ci-dessus.
        // Résultat : CameraFreelookMixin261 partait sur le chemin de retransform
        // tardif (IsolatedBootstrap.scheduleDelayedRetransform), où la classe
        // synthétique Args$1ArgsClassGenerator (@ModifyArgs, Sponge Mixin) ne
        // se liait pas correctement au runtime → NoSuchMethodError sur
        // Args$1.of(float,float) dans Camera.alignWithEntity. Laisser ces 2
        // mixins tisser INCONDITIONNELLEMENT (comme les mixins hub) évite le
        // retransform et le bug.
    }

    /** @return le {@link HookPoint} associé à ce nom simple de classe mixin, ou {@code null} si absent (mixin hors système déclaratif — voir la javadoc de classe). */
    public static HookPoint resolve(String simpleMixinClassName) {
        return BY_SIMPLE_NAME.get(simpleMixinClassName);
    }
}
