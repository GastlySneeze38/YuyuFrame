package com.yuyuframe.launcheragent.apimixin;

/**
 * Catalogue fixe des ACCÈS à l'état du jeu exposés à nos modules — pendant de
 * {@link HookPoint}, pour la lecture/écriture plutôt que pour l'injection.
 *
 * <h2>Pourquoi ce catalogue existe</h2>
 *
 * Un accessor Sponge ({@code @Accessor}/{@code @Invoker}) est une interface
 * TYPÉE et propre à une version : {@code MinecraftAccessor261} cible le champ
 * {@code player} de {@code net.minecraft.client.Minecraft} tel qu'il s'appelle
 * en 26.1.2. Tout appelant qui la nomme se couple à cette version — c'est
 * exactement ce que faisaient {@code PlayerData}/{@code ClientData} et une
 * dizaine de modules, d'où le constat de l'audit : le code de {@code runtime/}
 * ne contenait AUCUN nom de classe Minecraft en dur, mais restait quand même
 * cloué à 26.1.2 par ses imports d'accessors.
 *
 * <p>Ces entrées sont VOLONTAIREMENT indépendantes de toute version MC
 * (pas de suffixe {@code 261} dans leur nom) — un seul {@link AccessPoint}
 * logique (ex. {@link #CLIENT_PLAYER}) est servi par un accessor différent
 * selon la tranche active, exactement comme un {@link HookPoint} est dispatché
 * par un mixin différent selon la tranche. Voir {@link AccessorRegistry} pour
 * le mécanisme de liaison et {@code MixinHookPointRegistry} pour le pendant
 * côté injection.
 *
 * <h2>Nommer un accès, pas un champ</h2>
 *
 * L'entrée décrit CE QU'ON VEUT, pas le nom du champ qui le porte
 * aujourd'hui : {@link #CLIENT_FPS} et pas {@code MINECRAFT_FIELD_FPS}. Un
 * renommage Mojang ne doit toucher que la liaison de la tranche concernée,
 * jamais le catalogue ni un appelant. Le commentaire de chaque entrée nomme le
 * champ/la méthode d'origine en 26.1.2, utile pour retrouver la cible au
 * moment d'écrire la liaison d'une autre version.
 *
 * <h2>Portée : l'état du jeu, pas le rendu</h2>
 *
 * Les accessors de {@code apigraphic/} ({@code VertexFormatElementAccessor261},
 * {@code RenderPipelinesAccessor261}, {@code GuiRendererAccessor261}…) ne sont
 * DÉLIBÉRÉMENT pas ici : ils sont sollicités plusieurs fois par frame et
 * appartiennent au problème de l'ÈRE DE RENDU (pipeline Blaze3D vs dessin
 * immédiat), qui se règle par une stratégie de backend, pas par une table
 * d'accès génériques renvoyant {@code Object}.
 */
public enum AccessPoint {

    // ── Client (net.minecraft.client.Minecraft) ────────────────────────────
    /** L'instance du client elle-même — {@code Minecraft.getInstance()} (méthode statique, pas un champ). */
    CLIENT_INSTANCE,
    /** Joueur local — champ {@code player}. */
    CLIENT_PLAYER,
    /** Monde client — champ {@code level}. */
    CLIENT_LEVEL,
    /** Options du jeu — champ {@code options}. */
    CLIENT_OPTIONS,
    /** Session utilisateur (pseudo, UUID) — champ {@code user}. */
    CLIENT_USER,
    /** Écran ouvert, {@code null} en jeu — champ {@code screen}. */
    CLIENT_SCREEN,
    /** HUD vanilla — champ {@code gui}. */
    CLIENT_GUI,
    /** Fenêtre du jeu — champ {@code window}. */
    CLIENT_WINDOW,
    /** Gestionnaire de souris — champ {@code mouseHandler}. */
    CLIENT_MOUSE_HANDLER,
    /** Gestionnaire de ressources (respecte les resource packs) — champ {@code resourceManager}. */
    CLIENT_RESOURCE_MANAGER,
    /** FPS courant — champ STATIQUE {@code fps} (pas de receveur). */
    CLIENT_FPS,

    // ── Options (net.minecraft.client.Options) ─────────────────────────────
    /** Poignée d'option du champ de vision — champ {@code fov}. */
    OPTIONS_FOV,
    /** Poignée d'option de la sensibilité souris — champ {@code sensitivity}. */
    OPTIONS_SENSITIVITY,
    /** Poignée d'option de la luminosité — champ {@code gamma}. */
    OPTIONS_GAMMA,
    /** HUD masqué (F1) — champ {@code hideGui}. */
    OPTIONS_HIDE_GUI,

    // ── Poignée d'option (net.minecraft.client.OptionInstance) ─────────────
    /** Valeur courante d'une poignée d'option — champ {@code value}. */
    OPTION_VALUE,
    /** Écriture de cette valeur — champ {@code value} en écriture ; contourne la validation vanilla, qui clampe. */
    OPTION_VALUE_SET,

    // ── Raccourci clavier (net.minecraft.client.KeyMapping) ────────────────
    /** Touche enfoncée — champ {@code isDown} (renommage à connaître : KeyBinding→KeyMapping, {@code pressed}→{@code isDown}). */
    KEYBIND_IS_DOWN,
    /** Touche liée — champ {@code key}. */
    KEYBIND_KEY,

    // ── Joueur local : OPÉRATIONS, pas poignées ────────────────────────────
    //
    // Ces points-ci ne rendent PAS le joueur : ils font l'appel à sa place.
    // C'est la différence qui rend un module portable — recevoir l'objet
    // obligerait l'appelant à nommer son type pour en tirer quoi que ce soit
    // (et donc à tomber en NoClassDefFoundError sur l'autre version), alors
    // que l'appel, lui, est nommé UNE fois par version dans les liaisons.
    /** Position, rendue en {@code double[]{x, y, z}} — {@code getX()/getY()/getZ()}. */
    PLAYER_POSITION,
    /** Rotation horizontale en degrés — {@code getYRot()} en 26.1.2, {@code getYaw()} en Yarn. */
    PLAYER_YAW,
    /** Rotation verticale en degrés — {@code getXRot()} en 26.1.2, {@code getPitch()} en Yarn. */
    PLAYER_PITCH,
    /** Hauteur des yeux — {@code getEyeHeight()} en 26.1.2, {@code getStandingEyeHeight()} en Yarn. */
    PLAYER_EYE_HEIGHT,
    /** Points de vie courants — {@code getHealth()}. */
    PLAYER_HEALTH,
    /** Points de vie maximum — {@code getMaxHealth()}. */
    PLAYER_MAX_HEALTH,
    /**
     * Charge de l'attaque, de 0 à 1 — {@code getAttackStrengthScale(partial)}
     * en 26.1.2, {@code getAttackCooldownProgress(partial)} en Yarn 1.21.11 :
     * même opération, deux noms, d'où l'intérêt de la nommer ici une seule fois.
     */
    PLAYER_ATTACK_STRENGTH,

    // ── Faim/saturation (net.minecraft.world.food.FoodData) ────────────────
    /** Niveau de faim — champ {@code foodLevel}. */
    FOOD_LEVEL,
    /** Saturation — champ {@code saturationLevel}. */
    FOOD_SATURATION,
    /** Épuisement — champ {@code exhaustionLevel}. */
    FOOD_EXHAUSTION,

    // ── Types de composants d'item (net.minecraft.core.component.DataComponents) ──
    /** Type de composant « nourriture » — champ STATIQUE {@code FOOD} (pas de receveur). */
    COMPONENT_TYPE_FOOD,
    /** Type de composant « enchantements » — champ STATIQUE {@code ENCHANTMENTS} (pas de receveur). */
    COMPONENT_TYPE_ENCHANTMENTS,

    // ── Serveur courant (net.minecraft.client.multiplayer.ServerData) ──────
    /** Adresse du serveur — champ {@code ip}. */
    SERVER_IP,
    /** Nom affiché du serveur — champ {@code name}. */
    SERVER_NAME,

    // ── Chat (net.minecraft.client.gui.components.ChatComponent) ───────────
    /** Historique complet des messages — champ {@code allMessages}. */
    CHAT_ALL_MESSAGES,
    /**
     * Ajout d'un message dans le chat — {@code @Invoker} sur la méthode PRIVÉE
     * {@code addMessage(Component, MessageSignature, GuiMessageSource, GuiMessageTag)}.
     * Prend des arguments, contrairement à tous les accès ci-dessus — voir
     * {@link AccessorRegistry#invoke}.
     */
    CHAT_ADD_MESSAGE,

    // ── Brouillard (net.minecraft.client.renderer.fog.FogRenderer) ─────────
    /** Activation globale du brouillard — champ STATIQUE {@code fogEnabled} en écriture (pas de receveur). */
    FOG_SET_ENABLED,
}
