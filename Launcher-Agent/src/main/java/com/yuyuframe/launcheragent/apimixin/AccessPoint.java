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
    /**
     * GENRE de l'écran passé en receveur, rendu en {@code String} :
     * {@code "CHAT"}, {@code "INVENTORY"}, {@code "CONTAINER"} ou
     * {@code "OTHER"} (y compris pour {@code null}).
     *
     * <p>Une OPÉRATION plutôt que les trois classes : l'appelant n'a ni à
     * nommer {@code ChatScreen}/{@code InventoryScreen}/{@code
     * AbstractContainerScreen} (renommées {@code HandledScreen} en Yarn), ni à
     * les résoudre par réflexion. La liaison fait trois {@code instanceof}
     * typés.
     *
     * <p>L'inventaire du joueur EST un conteneur du point de vue du jeu : la
     * liaison doit tester {@code INVENTORY} avant {@code CONTAINER}.
     */
    SCREEN_KIND,
    /**
     * Ouvre un écran. Argument : la poignée de l'écran, ou {@code null} pour
     * revenir au jeu.
     *
     * <p>Nos propres écrans ({@code UiScreenBase}) sont compilés contre un stub
     * {@code Screen} SANS relation de type avec la vraie classe du jeu ;
     * {@code ScreenStubPatcher} réécrit cette relation au chargement, si bien
     * qu'à l'exécution la poignée EST un écran valide pour la version active.
     * C'est ce qui permet à ce point de n'accepter qu'un {@code Object}.
     */
    CLIENT_SET_SCREEN,
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
    /**
     * Pseudo du joueur connecté — {@code User.getName()} en 26.1.2,
     * {@code Session.getUsername()} en Yarn 1.21.11.
     *
     * <p>Rendu en {@code String} et non via l'objet session : ça évite aux
     * appelants de passer par {@code GameProfile} (com.mojang.authlib), qui
     * n'est sur AUCUN classpath de compilation et les obligeait à de la
     * réflexion — voir {@code MumbleLinkModule}, qui en était le dernier usage.
     */
    CLIENT_USERNAME,

    // ── Options (net.minecraft.client.Options) ─────────────────────────────
    /** Poignée d'option du champ de vision — champ {@code fov}. */
    OPTIONS_FOV,
    /** Poignée d'option de la sensibilité souris — champ {@code sensitivity}. */
    OPTIONS_SENSITIVITY,
    /** Poignée d'option de la luminosité — champ {@code gamma}. */
    OPTIONS_GAMMA,
    /** HUD masqué (F1) — champ {@code hideGui}. */
    OPTIONS_HIDE_GUI,
    /**
     * Les cinq raccourcis de déplacement, en poignées OPAQUES et dans CET
     * ordre : avancer, gauche, reculer, droite, sauter.
     *
     * <p>Poignées et non codes : l'appelant les repasse à
     * {@link #KEYBIND_IS_DOWN} et {@link #KEYBIND_KEY_CODE}, sans jamais
     * nommer leur type. Renommages : {@code keyUp}/{@code keyLeft}/
     * {@code keyDown}/{@code keyRight}/{@code keyJump} en 26.1.2,
     * {@code forwardKey}/{@code leftKey}/{@code backKey}/{@code rightKey}/
     * {@code jumpKey} en Yarn.
     */
    OPTIONS_MOVEMENT_KEYS,
    /**
     * Point de vue de la caméra, en CHAÎNE : {@code "first_person"},
     * {@code "third_person_back"} ou {@code "third_person_front"}.
     *
     * <p>Une chaîne et non une poignée, contrairement à
     * {@link #OPTIONS_MOVEMENT_KEYS} : il n'existe que trois valeurs, elles
     * sont les mêmes sur toutes les versions, et l'appelant a besoin de les
     * DISTINGUER (il en choisit une selon un réglage) — pas seulement de les
     * transporter. Une poignée l'obligerait à nommer l'enum, qui s'appelle
     * {@code CameraType} en 26.1.2 et {@code Perspective} en Yarn.
     */
    OPTIONS_PERSPECTIVE,
    /** Écriture de ce point de vue — même convention de chaîne qu'en lecture. */
    OPTIONS_PERSPECTIVE_SET,

    // ── Poignée d'option (net.minecraft.client.OptionInstance) ─────────────
    /** Valeur courante d'une poignée d'option — champ {@code value}. */
    OPTION_VALUE,
    /** Écriture de cette valeur — champ {@code value} en écriture ; contourne la validation vanilla, qui clampe. */
    OPTION_VALUE_SET,

    // ── Raccourci clavier (net.minecraft.client.KeyMapping) ────────────────
    /** Touche enfoncée — champ {@code isDown} (renommage à connaître : KeyBinding→KeyMapping, {@code pressed}→{@code isDown}). */
    KEYBIND_IS_DOWN,
    /**
     * CODE de la touche liée (code GLFW), receveur = la poignée de raccourci.
     *
     * <p>Rend un entier et non l'objet touche : {@code InputConstants$Key}
     * (26.1.2) et {@code InputUtil$Key} (Yarn) sont le même concept sous deux
     * noms, et l'appelant n'en veut de toute façon que le code, pour le
     * traduire en libellé lisible.
     *
     * <p>Chaque version prend le chemin qui existe chez elle : 26.1.2 lit le
     * champ privé {@code key} par accessor puis {@code getValue()} ; la
     * 1.21.11 passe par les méthodes PUBLIQUES
     * {@code getBoundKeyTranslationKey()} puis
     * {@code InputUtil.fromTranslationKey(...).getCode()}, ce qui lui évite
     * l'accessor. Renommages à connaître : le champ {@code code} (int direct)
     * de {@code KeyBinding} a disparu en 1.13+, {@code boundKey}→{@code key},
     * et {@code getCode()}→{@code getValue()} côté 26.1.2.
     */
    KEYBIND_KEY_CODE,

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
    /**
     * L'effet est-il actif ? Argument : son identifiant de registre
     * ({@code "minecraft:darkness"}).
     *
     * <p>L'effet est désigné par une CHAÎNE et non par l'objet du jeu : un
     * appelant neutre ne peut pas nommer {@code MobEffects}/{@code StatusEffects}.
     * Chaque liaison traduit l'identifiant vers sa constante — ajouter un effet
     * demande donc une ligne des DEUX côtés, ce qui est voulu : on ne sert que
     * des effets dont on a vérifié le nom sur chaque version.
     */
    PLAYER_HAS_EFFECT,
    /** Retire cet effet — même convention d'identifiant que {@link #PLAYER_HAS_EFFECT}. */
    PLAYER_REMOVE_EFFECT,
    /**
     * Latence du joueur local, en millisecondes.
     *
     * <p>Toute la CHAÎNE est faite par la liaison (connexion → entrée de liste
     * → latence), et chaque version prend le chemin qui existe chez elle :
     * 26.1.2 cherche par UUID, 1.21.11 par pseudo, faute de {@code getUuid()}
     * sur {@code Entity} dans ses mappings. Un point d'accès par OPÉRATION —
     * et non un qui rendrait la connexion — est ce qui rend cette divergence
     * invisible à l'appelant.
     */
    PLAYER_PING,
    /**
     * Effets actifs, rendus en {@code List<PlayerEffect>} — données neutres,
     * jamais les objets du jeu (voir {@link PlayerEffect}).
     *
     * <p>La méthode a été RENOMMÉE trois fois au fil des versions
     * ({@code getStatusEffectInstances} → {@code getStatusEffects} →
     * {@code getActiveEffects}) : raison de plus pour que le module ne la nomme
     * jamais lui-même.
     */
    PLAYER_ACTIVE_EFFECTS,
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
    /**
     * Équipement complet, rendu en {@code ItemInfo[6]} et dans CET ordre :
     * tête, torse, jambes, pieds, main principale, main secondaire (voir
     * {@link ItemInfo}). Jamais {@code null}, jamais de case nulle —
     * {@link ItemInfo#EMPTY} pour un emplacement vide.
     *
     * <p>Les six emplacements d'un coup plutôt qu'un point par pièce : le seul
     * appelant les veut tous ensemble, une fois par tick, et six allers-retours
     * coûteraient six recherches de joueur pour une donnée qui ne peut pas
     * changer entre-temps.
     *
     * <p>Piège historique valable sur toute la ligne : l'armure se lisait par
     * {@code getArmorSlot(int)} avant la refonte « Flattening » (~1.13), depuis
     * remplacé par {@code getEquippedStack}/{@code getItemBySlot(EquipmentSlot)} ;
     * et la main est un ARGUMENT ({@code Hand}/{@code InteractionHand}, absent
     * avant la 1.9), jamais un appel sans paramètre.
     */
    PLAYER_EQUIPMENT,
    /**
     * Le joueur est-il droitier ? — {@code getMainArm() == RIGHT}.
     *
     * <p>Rendu en booléen et non en constante du jeu : {@code HumanoidArm}
     * (26.1.2) et {@code Arm} (Yarn) sont le même enum sous deux noms, qu'un
     * appelant neutre ne peut nommer ni l'un ni l'autre.
     */
    PLAYER_MAIN_ARM_RIGHT,
    /**
     * Drapeaux de déplacement, rendus en {@code boolean[6]} et dans CET ordre :
     * monté sur une entité, en vol à l'élytre, dans l'eau, en sprint, au sol,
     * en nage.
     *
     * <p>Les quatre d'un coup, pour la même raison que
     * {@link #PLAYER_EQUIPMENT} : leur seul appelant les lit ensemble, une fois
     * par tick, pour décider d'une même chose.
     *
     * <p>Renommages, tous les quatre différents d'une version à l'autre :
     * {@code isPassenger}/{@code hasVehicle},
     * {@code isFallFlying}/{@code isGliding},
     * {@code isInWater}/{@code isTouchingWater},
     * {@code onGround}/{@code isOnGround} ; seuls {@code isSprinting} et
     * {@code isSwimming} sont communs.
     */
    PLAYER_MOVEMENT_FLAGS,
    /**
     * Mode de jeu, rendu en {@code boolean[2]} : créatif, spectateur.
     *
     * <p>Séparé de {@link #PLAYER_MOVEMENT_FLAGS} bien que de même forme : ce
     * n'est pas la même donnée ni la même fréquence de lecture, et les
     * regrouper ferait une table fourre-tout dont l'ordre des cases
     * n'apprendrait plus rien.
     */
    PLAYER_MODE_FLAGS,
    /**
     * Faim, saturation et épuisement du joueur, rendus en
     * {@code float[]{niveau, saturation, épuisement}} — {@code null} si
     * indisponible, ce que l'appelant DOIT distinguer de zéros (un joueur à
     * jeun et un joueur absent ne se dessinent pas pareil).
     *
     * <p>L'objet porteur n'est JAMAIS rendu : {@code FoodData} (26.1.2) et
     * {@code HungerManager} (Yarn) sont deux types du jeu, et le recevoir
     * obligerait l'appelant à en nommer un. Les points {@link #FOOD_LEVEL},
     * {@link #FOOD_SATURATION} et {@link #FOOD_EXHAUSTION}, qui prennent cet
     * objet en receveur, restent utiles aux liaisons elles-mêmes.
     */
    PLAYER_FOOD,

    // ── Monde ──────────────────────────────────────────────────────────────
    /**
     * Identifiant de registre COMPLET du biome à ces coordonnées
     * ({@code "minecraft:plains"}), ou {@code null}. Arguments : x, y, z en
     * entiers de bloc.
     *
     * <p>Toute la chaîne est dans la liaison, et elle n'a RIEN de commun d'une
     * version à l'autre : 26.1.2 fait {@code level.getBiomeManager()} →
     * {@code getBiome(BlockPos)} → {@code unwrapKey()} → {@code identifier()} ;
     * la 1.21.11 fait {@code WorldView.getBiome(BlockPos)} →
     * {@code getIdAsString()}. Le rendre en chaîne évite à l'appelant de
     * connaître l'une ou l'autre.
     *
     * <p>Deux pièges déjà payés : les noms « évidents » {@code getKey()}/
     * {@code getValue()} n'existent pas sur {@code Holder}, et
     * {@code BlockPos(int, int, int)} n'existe pas en 1.21.11 (fabrique
     * {@code ofFloored} à la place).
     */
    LEVEL_BIOME_ID,
    /**
     * Le monde passé en receveur est-il le monde CLIENT ? Rendu en
     * {@code Boolean}.
     *
     * <p>Pour les hooks posés sur une classe de monde PARTAGÉE client/serveur :
     * en solo, le serveur intégré tourne dans la même JVM, et agir sur son
     * monde fausserait la simulation réelle. Un {@code instanceof} typé dans la
     * liaison — {@code ClientWorld} en 1.21.11, {@code ClientLevel} en 26.1.2.
     */
    LEVEL_IS_CLIENT,
    /**
     * Chemin (sans espace de noms) de l'identifiant de ressource passé en
     * receveur, en {@code String} — ex. {@code "misc/pumpkinblur.png"}.
     *
     * <p>Pour les Mixins qui reçoivent un identifiant du jeu : leur corps n'est
     * pas traduit au chargement, ils ne peuvent donc pas l'appeler eux-mêmes en
     * 1.21.11 (nom obfusqué).
     */
    IDENTIFIER_PATH,

    // ── Faim/saturation (net.minecraft.world.food.FoodData) ────────────────
    /** Niveau de faim — champ {@code foodLevel}. */
    FOOD_LEVEL,
    /** Saturation — champ {@code saturationLevel}. */
    FOOD_SATURATION,
    /** Épuisement — champ {@code exhaustionLevel}. */
    FOOD_EXHAUSTION,

    /**
     * Niveau de cet enchantement sur l'objet en MAIN PRINCIPALE, 0 s'il ne
     * l'a pas. Argument : le chemin de registre de l'enchantement
     * ({@code "lunge"}), sans espace de noms.
     *
     * <p>Même convention de chaîne que {@link #PLAYER_HAS_EFFECT}, et pour la
     * même raison. La traversée du composant d'enchantements diffère d'un bout
     * à l'autre : {@code keySet()} + {@code unwrapKey()} + {@code identifier()}
     * en 26.1.2, {@code getEnchantments()} + {@code getIdAsString()} en Yarn.
     */
    PLAYER_HELD_ENCHANT_LEVEL,
    /**
     * Aliment tenu et RÉELLEMENT consommable, rendu en
     * {@code float[]{faim rendue, saturation rendue}} — {@code null} si rien
     * de mangeable n'est en main.
     *
     * <p>« Réellement consommable » est le cœur du point : la liaison teste
     * elle-même {@code canEat(canAlwaysEat)} / {@code canConsume(...)}, la
     * garde qu'utilise vanilla avant de consommer. Sans elle, l'appelant
     * promettrait un gain impossible barre pleine — bug déjà signalé et
     * corrigé. La main principale l'emporte sur la secondaire.
     *
     * <p>La saturation rendue est ABSOLUE depuis la 1.20.5, plus un
     * multiplicateur.
     */
    PLAYER_HELD_FOOD,

    // ── Types de composants d'item (net.minecraft.core.component.DataComponents) ──
    /** Type de composant « nourriture » — champ STATIQUE {@code FOOD} (pas de receveur). */
    COMPONENT_TYPE_FOOD,
    /** Type de composant « enchantements » — champ STATIQUE {@code ENCHANTMENTS} (pas de receveur). */
    COMPONENT_TYPE_ENCHANTMENTS,

    // ── Fenêtre et ressources ──────────────────────────────────────────────
    /**
     * Taille de l'écran en PIXELS GUI (échelle vanilla appliquée), rendue en
     * {@code int[]{largeur, hauteur}} — {@code null} si la fenêtre n'est pas
     * lisible.
     *
     * <p>Les vraies dimensions plutôt qu'une reconstruction par division :
     * l'arrondi de vanilla ne se redérive pas exactement, et un pixel d'écart
     * décale tout un alignement sur les barres du HUD.
     * Renommage : {@code getGuiScaledWidth()} / {@code getScaledWidth()}.
     */
    CLIENT_GUI_SIZE,
    /**
     * Contenu BRUT d'une ressource du jeu, en octets — arguments : espace de
     * noms et chemin ({@code "minecraft"}, {@code "textures/gui/sprites/..."}).
     * {@code null} si la ressource est absente.
     *
     * <p>Passer par le gestionnaire de ressources et non par le classloader
     * est ce qui fait SUIVRE LES RESOURCE PACKS : le classloader sert la
     * version du jar et les ignore. Rendu en octets pour que l'appelant décode
     * lui-même, sans jamais nommer {@code Resource} ni {@code Identifier}.
     */
    RESOURCE_BYTES,

    // ── Serveur courant (net.minecraft.client.multiplayer.ServerData) ──────
    /** Adresse du serveur — champ {@code ip}. */
    SERVER_IP,
    /** Nom affiché du serveur — champ {@code name}. */
    SERVER_NAME,

    // ── Chat ───────────────────────────────────────────────────────────────
    //
    // Les deux points d'accès qui rendaient la LISTE de messages et exposaient
    // l'ajout brut ont été retirés le 2026-09-12. Ils livraient des objets du
    // jeu (GuiMessage, Component, MessageSignature…) dont la FORME diffère
    // d'une version à l'autre — la 26.1.2 range {contenu, source, étiquette},
    // la 1.21.11 {tick, contenu, signature, indicateur} — et l'appelant devait
    // donc connaître celle de sa version pour en faire quoi que ce soit.
    //
    // Les deux points ci-dessous nomment une OPÉRATION à la place. La POLITIQUE
    // (quand fusionner, à partir de quel message, avec quel compteur) reste
    // chez l'appelant, qui la décrit en données neutres ; la manipulation des
    // objets du jeu appartient entièrement à la liaison de chaque tranche.
    /**
     * Message le plus RÉCENT du chat, ou {@code null} si le chat n'est pas
     * encore disponible / est vide.
     *
     * <p>Rendu en {@code Object[]{ ligne, contenu, texteBrut }} : les deux
     * premiers sont des poignées OPAQUES (à comparer par identité et à
     * repasser tel quel, jamais à transtyper), le troisième est le texte aplati
     * dont l'appelant a réellement besoin pour décider.
     */
    CHAT_HEAD_MESSAGE,
    /**
     * Fusionne les DEUX dernières lignes du chat en une seule, suffixée d'un
     * compteur de répétitions. Arguments : la poignée de contenu de la PREMIÈRE
     * occurrence (celle qui porte la mise en forme du serveur, rendue plus tôt
     * par {@link #CHAT_HEAD_MESSAGE}) et le nombre de répétitions.
     *
     * <p>Rend la nouvelle poignée de ligne de tête, ou {@code null} si la
     * fusion n'a pas eu lieu.
     *
     * <p>Repartir du COMPOSANT d'origine plutôt que de son texte aplati n'est
     * pas un détail : c'est ce qui garde couleurs, gras, survols et clics du
     * message d'origine — un bug déjà vécu et corrigé (« ça stack les messages
     * mais sans garder la typo d'origine »), invisible sur un serveur vanilla
     * au chat blanc.
     */
    CHAT_MERGE_REPEATED,

    // ── Connexion au serveur ───────────────────────────────────────────────
    //
    // Renommages, systématiques sur toute cette famille :
    // ClientPacketListener/ClientPlayNetworkHandler, sendChat/sendChatMessage,
    // sendCommand/sendChatCommand, getServerData/getServerInfo,
    // getCommands/getCommandDispatcher, getRemoteAddress/getAddress.
    /**
     * Poignée OPAQUE de la connexion courante, ou {@code null} en solo/hors
     * partie.
     *
     * <p>Ne sert QU'À l'identité : « est-ce toujours la même connexion
     * qu'avant ? ». {@code MacroModule} s'en sert pour ne tenter son
     * auto-login qu'une fois par connexion. Rien d'autre ne doit en être tiré
     * — toutes les opérations ont leur propre point ci-dessous.
     */
    NETWORK_CONNECTION,
    /** Envoie un message de chat. Argument : le texte. */
    NETWORK_SEND_CHAT,
    /** Envoie une COMMANDE, sans le {@code /} initial. Argument : la commande. */
    NETWORK_SEND_COMMAND,
    /**
     * Les deux sources BRUTES de l'adresse du serveur, rendues en
     * {@code Object[]{ String ip, java.net.SocketAddress distante }} — l'une
     * ou l'autre peut être nulle ; {@code null} tout court en solo.
     *
     * <p>Deux sources et non une : en « quick play » (lancement direct sur une
     * IP), le jeu ne construit pas d'entrée de liste de serveurs et la
     * première est nulle. La connexion, elle, existe toujours.
     *
     * <p>Laquelle l'emporte et comment la normaliser reste chez l'appelant :
     * c'est de la politique, et {@code SocketAddress} vient de {@code java.net},
     * donc d'un type que tout le monde peut nommer.
     */
    NETWORK_SERVER_ADDRESS,
    /**
     * Le serveur DÉCLARE-t-il cette commande ? Argument : son nom, sans le
     * {@code /}.
     *
     * <p>L'arbre de commandes vient de brigadier, une bibliothèque externe non
     * obfusquée — il aurait donc pu être rendu tel quel. Il ne l'est pas : le
     * TRAVERSER demande de partir de la connexion, qui, elle, est un type du
     * jeu. Un point par question posée plutôt qu'un point qui rend l'arbre.
     */
    NETWORK_HAS_COMMAND,

    // ── Sons d'interface (net.minecraft.client.sounds.SoundManager) ────────
    /**
     * Joue un son d'interface. Arguments : identifiant de registre
     * ({@code "minecraft:block.amethyst_block.chime"}), hauteur, volume.
     *
     * <p>Le son est désigné par une CHAÎNE, même convention et même raison que
     * {@link #PLAYER_HAS_EFFECT} : {@code SoundEvents} est une classe du jeu.
     * La liaison fait toute la chaîne — gestionnaire de sons, construction de
     * l'instance ({@code SimpleSoundInstance.forUI} en 26.1.2,
     * {@code PositionedSoundInstance.ui} en Yarn), lecture — pour qu'aucun
     * appelant n'ait à connaître ces trois noms, qui diffèrent tous les trois
     * d'une version à l'autre.
     */
    SOUND_PLAY_UI,

    // ── Brouillard (net.minecraft.client.renderer.fog.FogRenderer) ─────────
    /** Activation globale du brouillard — champ STATIQUE {@code fogEnabled} en écriture (pas de receveur). */
    FOG_SET_ENABLED,
}
