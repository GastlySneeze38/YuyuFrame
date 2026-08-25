package com.yuyuframe.launcheragent.apimixin;

/**
 * Catalogue fixe des points d'accroche vanilla exposés à nos modules — voir
 * ROADMAP-agent.md §3.2. Chaque entrée correspond à UN mixin dans
 * {@code apimixin/} (jamais plusieurs {@code @Inject}/{@code @WrapOperation}
 * regroupés dans un seul mixin — voir le raisonnement détaillé dans la
 * roadmap : {@link VanillaHookRegistry#isUsed} doit pouvoir décider hook par
 * hook si Mixin tisse ou non, ce que Mixin ne permet qu'au niveau d'une
 * classe {@code @Mixin} entière, jamais d'une méthode injectée isolée).
 *
 * Ces entrées sont VOLONTAIREMENT indépendantes de toute version MC précise
 * (pas de suffixe {@code 261}/{@code 1214} dans leur nom) — un seul
 * {@code HookPoint} logique (ex: {@link #HUD_EXTRACT_CROSSHAIR}) peut être
 * dispatché par un mixin différent selon le bracket (voir
 * {@code VersionBracketRegistry}), une fois que d'autres brackets auront
 * aussi leur propre pull Fabric API adapté (voir ROADMAP-agent.md §3.1/§5 —
 * pour l'instant, seul le bracket 26.1.2 a des mixins réellement écrits
 * derrière ces entrées).
 *
 * Chaque commentaire pointe vers le mixin Fabric API d'origine ayant servi de
 * référence (voir {@code mixinapi/26.1.2/}) — utile pour retrouver le point
 * d'injection exact (cible, {@code @At}, éventuel {@code @Slice}) au moment
 * de porter le mixin réel. ⚠️ Ces commentaires disent « WrapOperation » parce
 * qu'ils décrivent le mixin FABRIC API d'origine : ne PAS les recopier tels
 * quels, voir la règle ci-dessous.
 *
 * <h2>RÈGLE : viser la méthode appelée, jamais le site d'appel</h2>
 * Pour une porte « cet élément vanilla doit-il s'afficher ? », écrire
 * <b>{@code @Inject}(method = "&lt;méthode appelée&gt;", at = @At("HEAD"),
 * cancellable = true)</b> puis {@code ci.cancel()} — PAS un injecteur de site
 * d'appel ({@code @WrapOperation}/{@code @Redirect} avec
 * {@code @At(value = "INVOKE")}) sur l'appel depuis la méthode englobante.
 *
 * Fabric API peut se permettre le site d'appel : c'est un mod, il tisse tôt et
 * en même temps que les autres. Nous sommes un {@code -javaagent} à côté de
 * ~89 mods, et un site d'appel DISPARAÎT dès qu'un mod enveloppe la méthode
 * englobante. Cas réel et coûteux (2026-08-25, §14) : Iris applique
 * {@code @WrapMethod} sur {@code Gui.extractRenderState}, ce qui déplace tout
 * le corps d'origine dans une méthode synthétique — {@code extractRenderState}
 * ne contient plus qu'un appel à
 * {@code wrapMethod$bne000$iris$handleHudHidingScreens}, et TOUS nos
 * {@code @At(INVOKE)} portant dessus voyaient « Scanned 0 target(s) ».
 * 17 mixins {@code HudExtract*} ont été convertis d'un coup pour cette raison ;
 * l'échec était SILENCIEUX (voir {@code LauncherMixinTransformerWrapper} :
 * la JVM ignore toute exception d'un {@code ClassFileTransformer}).
 *
 * Injecter dans la méthode appelée y est insensible : peu importe d'où part
 * l'appel, il aboutit toujours là.
 *
 * Exceptions légitimes, qui DOIVENT rester sur le site d'appel :
 * <ul>
 *   <li>la méthode appelée appartient à une autre classe qu'on ne veut pas
 *       tisser (hiérarchie trop large, interface) — ex.
 *       {@code ContextualBarRenderer}, {@code SpectatorGui}, {@code Screen} ;</li>
 *   <li>le site d'appel lui-même porte l'information — ex.
 *       {@code MouseHandlerFreelookMixin261}, où intercepter
 *       {@code LocalPlayer.turn} à CET endroit précis est ce qui donne des
 *       deltas déjà passés par la courbe de sensibilité vanilla ;</li>
 *   <li>il faut modifier une valeur/un argument plutôt que tout annuler — ex.
 *       {@code CameraFreelookMixin261}.</li>
 * </ul>
 * Dans ces cas, préférer {@code @WrapOperation}/{@code @WrapWithCondition}
 * (MixinExtras) à {@code @Redirect} : plusieurs wraps COMPOSENT sur un même
 * site, deux {@code @Redirect} s'excluent. MixinExtras est opérationnel chez
 * nous depuis {@code IsolatedBootstrap.initMixinExtras()} — avant ça, toute
 * annotation MixinExtras d'{@code apimixin/} était silencieusement inerte.
 */
public enum HookPoint {

    // ── HUD (fabric-rendering-v1 — GuiMixin, 26.1.2/1.21.11 "GuiRenderState" ─
    // pipeline différé, voir UiRenderer/UiVanillaItemRenderer pour le contexte
    // dual-pipeline déjà géré côté rendu custom de ce projet) ─────────────────
    /** Voir {@code GuiMixin#extractCameraOverlays} (WrapOperation). */
    HUD_EXTRACT_CAMERA_OVERLAY,
    /** Voir {@code GuiMixin#extractCrosshair} (WrapOperation). */
    HUD_EXTRACT_CROSSHAIR,
    /** Voir {@code GuiMixin#extractHotbar} (WrapOperation, via SpectatorGui). */
    HUD_EXTRACT_HOTBAR,
    /** Voir {@code GuiMixin#extractItemHotbar} (WrapOperation). */
    HUD_EXTRACT_ITEM_HOTBAR,
    /** Voir {@code GuiMixin#extractArmor} (WrapOperation). */
    HUD_EXTRACT_ARMOR,
    /** Voir {@code GuiMixin#extractHearts} (WrapOperation). */
    HUD_EXTRACT_HEARTS,
    /** Voir {@code GuiMixin#extractFood} (WrapOperation). */
    HUD_EXTRACT_FOOD,
    /** Voir {@code GuiMixin#extractAirBubbles} (WrapOperation). */
    HUD_EXTRACT_AIR_BUBBLES,
    /** Voir {@code GuiMixin#extractVehicleHealth} (WrapOperation). */
    HUD_EXTRACT_VEHICLE_HEALTH,
    /** Voir {@code GuiMixin} → {@code ContextualBarRenderer.extractBackground} (WrapOperation). */
    HUD_EXTRACT_CONTEXTUAL_BAR_BACKGROUND,
    /** Voir {@code GuiMixin} → {@code ContextualBarRenderer.extractExperienceLevel} (WrapOperation). */
    HUD_EXTRACT_EXPERIENCE_LEVEL,
    /** Voir {@code GuiMixin#extractSelectedItemName} (WrapOperation). */
    HUD_EXTRACT_SELECTED_ITEM_NAME,
    /** Voir {@code GuiMixin} → {@code SpectatorGui.extractAction} (WrapOperation). */
    HUD_EXTRACT_SPECTATOR_ACTION,
    /** Voir {@code GuiMixin#extractEffects} (WrapOperation). */
    HUD_EXTRACT_EFFECTS,
    /** Voir {@code GuiMixin#extractBossOverlay} (WrapOperation). */
    HUD_EXTRACT_BOSS_OVERLAY,
    /** Voir {@code GuiMixin#extractSleepOverlay} (WrapOperation). */
    HUD_EXTRACT_SLEEP_OVERLAY,
    /** Voir {@code GuiMixin#extractDemoOverlay} (WrapOperation). */
    HUD_EXTRACT_DEMO_OVERLAY,
    /** Voir {@code GuiMixin#extractScoreboardSidebar} (WrapOperation). */
    HUD_EXTRACT_SCOREBOARD_SIDEBAR,
    /** Voir {@code GuiMixin#extractOverlayMessage} (WrapOperation). */
    HUD_EXTRACT_OVERLAY_MESSAGE,
    /** Voir {@code GuiMixin#extractTitle} (WrapOperation). */
    HUD_EXTRACT_TITLE,
    /** Voir {@code GuiMixin#extractChat} (WrapOperation). */
    HUD_EXTRACT_CHAT,
    /** Voir {@code GuiMixin#extractTabList} (WrapOperation). */
    HUD_EXTRACT_TAB_LIST,
    /**
     * Voir {@code Gui.extractTextureOverlay(GuiGraphicsExtractor, Identifier, float)}
     * — PAS de mixin Fabric API d'origine (bespoke, voir audit ROADMAP-agent.md §3.3) :
     * point de passage COMMUN à deux overlays plein-écran distincts, discriminés par
     * {@code ctx} (l'{@code Identifier} de la texture demandée) — "pumpkin" pour
     * NoPumpkinOverlayModule, "powder_snow" pour ClearVisionModule#clearPowderSnow.
     */
    HUD_EXTRACT_TEXTURE_OVERLAY,

    // ── Brouillard par environnement (pas de mixin Fabric API — bespoke, voir audit
    // ROADMAP-agent.md §3.3 : Fabric API ne couvre aucune des 6 classes FogEnvironment,
    // voir NoFogModule/ClearVisionModule pour les modules qui s'enregistrent ici) ──────
    /** Voir {@code AtmosphericFogEnvironment.setupFog} (Inject TAIL) — brouillard de distance normal. */
    FOG_SETUP_ATMOSPHERIC,
    /** Voir {@code WaterFogEnvironment.setupFog} (Inject TAIL). */
    FOG_SETUP_WATER,
    /** Voir {@code LavaFogEnvironment.setupFog} (Inject TAIL). */
    FOG_SETUP_LAVA,
    /** Voir {@code PowderedSnowFogEnvironment.setupFog} (Inject TAIL). */
    FOG_SETUP_POWDERED_SNOW,
    /** Voir {@code BlindnessFogEnvironment.setupFog} (Inject TAIL). */
    FOG_SETUP_BLINDNESS,
    /** Voir {@code DarknessFogEnvironment.setupFog} (Inject TAIL). */
    FOG_SETUP_DARKNESS,

    // ── Rendu de texte/décorations d'item (fabric-rendering-v1) ──────────────
    /** Voir {@code GuiGraphicsExtractorMixin#itemDecorations} (Inject RETURN) — texte de durabilité/quantité, vanilla inclus. */
    ITEM_DECORATIONS_EXTRACT,
    /** Voir {@code AdvancementToastMixin#extractRenderState} (WrapOperation sur fakeItem) — TOUS les toasts, vanilla compris. */
    ADVANCEMENT_TOAST_EXTRACT,
    /** Voir {@code SubtitleOverlayMixin#extractRenderState} (WrapMethod). */
    SUBTITLE_OVERLAY_EXTRACT,
    /** Voir {@code HumanoidArmorLayerMixin#submit}/{@code renderArmorPiece} — armure vanilla incluse. */
    ARMOR_LAYER_RENDER,

    // ── Monde/niveau (fabric-rendering-v1 — LevelRendererMixin) ──────────────
    /** Voir {@code LevelRendererMixin#renderLevel} (Inject HEAD) — tout début du rendu du monde. */
    LEVEL_RENDER_HEAD,
    /** Voir {@code LevelRendererMixin#extractBlockOutline} (Inject RETURN) — recoupe le hook déjà utilisé côté modules existants. */
    LEVEL_BLOCK_OUTLINE_EXTRACT,
    /** Voir {@code LevelRendererMixin#extractLevel} (Inject RETURN). */
    LEVEL_EXTRACT,
    /** Voir {@code GuiRenderStateMixin#reset} (Inject TAIL) — même classe que UiVanillaItemRenderer exploite déjà par réflexion manuelle. */
    GUI_RENDER_STATE_RESET,
    /** Voir {@code GameRendererMixin#extract} (Inject HEAD) — passe d'extraction générale par frame. */
    GAME_RENDER_EXTRACT,

    // ── Écran/Input (fabric-screen-api-v1 + fabric-events-interaction-v0) ────
    /** Voir {@code ScreenMixin}/{@code ScreenAccessor} (fabric-screen-api-v1) — extension générique de tout écran. */
    SCREEN_INIT,
    /** Voir {@code MouseHandlerMixin} (screen-api-v1 ET events-interaction-v0) — comble la carence scroll (Phase 5.6). */
    MOUSE_SCROLL,
    /** Voir {@code KeyboardHandlerMixin} (screen-api-v1). */
    KEYBOARD_KEY,
    /** Voir {@code MinecraftMixin#setScreen} (screen-api-v1) — transition d'écran. */
    SCREEN_SET,
    /**
     * Voir {@code ScreenMixin#extractWithTooltip} (fabric-screen-api-v1) —
     * CORRECTIF : cible en réalité {@code Screen} en général (juste après
     * {@code extractBackground}), pas spécifiquement {@code
     * AbstractContainerScreen} malgré le nom de cette entrée — c'est là que
     * ShulkerPreviewModule devra s'accrocher, {@code AbstractContainerScreen}
     * n'en étant qu'un cas particulier.
     */
    CONTAINER_SCREEN_EXTRACT_TOOLTIP,
    /** Voir {@code client.MultiPlayerGameModeMixin} (events-interaction-v0) — clic gauche/attaque bloc. */
    ATTACK_BLOCK,
    /** Voir {@code client.MinecraftMixin} (events-interaction-v0) — pré-attaque. */
    PRE_ATTACK,

    // ── Raccourcis clavier (fabric-key-mapping-api-v1) ────────────────────────
    /** Voir {@code OptionsMixin} — enregistrement de {@code KeyMapping} custom. */
    KEYBIND_REGISTER,
    /** Voir {@code KeyMappingCategoryMixin} — catégorie custom dans l'écran Contrôles vanilla. */
    KEYBIND_CATEGORY_REGISTER,

    // ── Chat (fabric-message-api-v1) ──────────────────────────────────────────
    /** Voir {@code client.message.ChatListenerMixin} — réception. Existe déjà une variante maison (ChatListenerMixin261) à réconcilier. */
    CHAT_RECEIVE,
    /** Voir {@code client.message.ClientPacketListenerMixin} — envoi. */
    CHAT_SEND,

    // ── Item (fabric-item-api-v1) ──────────────────────────────────────────
    /** Voir {@code client.ItemInHandRendererMixin} — recoupe SwingSpeedModule/OldItemRotationsModule. */
    ITEM_IN_HAND_RENDER,
    /** Voir {@code client.ItemStackMixin} — {@code getTooltipLines} RETURN ordinal=1. */
    ITEM_TOOLTIP,
    /** Voir {@code client.MultiPlayerGameModeMixin} (item-api-v1) — timing de bris de bloc. */
    BLOCK_BREAK_TIMING,

    // ── Cycle de vie tick/chunk/entité (fabric-lifecycle-events-v1) ──────────
    /** Voir {@code client.MinecraftMixin} — base tick client. */
    CLIENT_TICK,
    /** Voir {@code client.LevelChunkMixin}/{@code client.ClientChunkCacheMixin}. */
    CHUNK_LOAD,
    /** Voir {@code client.ClientLevelEntityCallbacksMixin} ({@code ClientLevel$EntityCallbacks}). */
    ENTITY_LOAD,
    /** Voir {@code client.ClientLevelEntityCallbacksMixin}. */
    ENTITY_UNLOAD,
    /** Voir {@code client.ClientLevelMixin} (lifecycle-events-v1) — chargement du niveau client. */
    CLIENT_LEVEL_LOAD,
    /** Voir {@code client.ClientPacketListenerMixin}/{@code client.ClientConfigurationPacketListenerImplMixin} — (dé)connexion serveur. */
    SERVER_CONNECT,

    // ── Horloge (pas de mixin Fabric API — bespoke, voir audit ROADMAP-agent.md
    // §3.3) ─────────────────────────────────────────────────────────────────
    /**
     * Voir {@code ClientClockManager.getTotalTicks(Holder)} (Inject HEAD,
     * cancellable) — LA source unique dont dérive tout le rendu temporel
     * (soleil/lune/couleur du ciel/éclairage ambiant), voir WorldTimeModule.
     * Dispatché via {@link VanillaHookRegistry#dispatchValue} (remplacement
     * de valeur de retour), PAS {@link VanillaHookRegistry#dispatch} — ctx =
     * {@code Holder}, valeur de remplacement = {@code Long} ou {@code null}.
     */
    CLOCK_TOTAL_TICKS,

    // ── Freelook (pas de mixin Fabric API — bespoke, voir FreelookModule) ────
    /**
     * Voir {@code MouseHandlerFreelookMixin261} — dernier appel {@code
     * LocalPlayer.turn(D,D)} dans {@code MouseHandler.turnPlayer(D)V}.
     * Dispatché via {@link VanillaHookRegistry#dispatch} — {@code true} =
     * delta consommé par le freelook (rotation réelle du joueur annulée),
     * {@code false} = laisser vanilla tourner le joueur normalement.
     * {@code ctx} = {@code double[]{yRot, xRot}} (deltas déjà post-courbe de
     * sensibilité vanilla).
     */
    FREELOOK_TURN_INTERCEPT,
    /**
     * Voir {@code CameraFreelookMixin261} — args de {@code
     * Camera.setRotation(F,F)} DANS {@code alignWithEntity(F)V}. Dispatché
     * via {@link VanillaHookRegistry#dispatchValue} — {@code ctx} =
     * {@code float[]{yRot, xRot}} (valeurs vanilla déjà calculées), valeur de
     * remplacement = {@code float[]{newYRot, newXRot}} si le freelook est
     * actif, {@code null} sinon (aucun changement).
     */
    FREELOOK_CAMERA_ROTATION_OFFSET,
}
