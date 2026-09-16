package com.yuyuframe.launcheragent.apimixin.v1_8_9.core;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.data.PlayerEffect;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.chat.ChatHudAccessor189;
import com.yuyuframe.launcheragent.apimixin.v1_8_9.item.FoodItemAccessor189;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.util.Session;
import net.minecraft.client.util.Window;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.FoodItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.network.ClientConnection;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.biome.Biome;

/**
 * Liaisons {@link AccessPoint} → données du jeu pour la tranche 1.8.9 —
 * pendant d'{@code AccessorBindings1211}.
 *
 * <h2>Presque tout est public</h2>
 *
 * Champs et getters vérifiés {@code javap} sur le jar client 1.8.9 réel. Trois
 * champs PRIVÉS seulement, servis par un accessor classique :
 * l'épuisement ({@link HungerManagerAccessor189}), l'historique du chat
 * ({@link ChatHudAccessor189}) et {@code alwaysEdible}
 * ({@link FoodItemAccessor189}). Le FPS, privé en 1.21.11, a ici un getter
 * statique public.
 *
 * <h2>Ce que la version n'a pas — et ce qu'on sert à la place</h2>
 *
 * <ul>
 *   <li>Pas de {@code SimpleOption} : les options sont des {@code float} nus.
 *       Les poignées d'option sont donc des {@link OptionHandle} maison ; la
 *       valeur circule en {@code Float}, que {@code GameOptions.setValue}
 *       ré-emballe au bon type.</li>
 *   <li>Pas de registre d'effets : l'id NUMÉRIQUE (1…23) est traduit par une
 *       table fixe vers l'identifiant moderne ; le nom passe par
 *       {@code I18n}.</li>
 *   <li>Pas de recharge d'attaque, de main secondaire, d'élytres, de nage :
 *       {@code PLAYER_ATTACK_STRENGTH} = 1, main droite, drapeaux à
 *       {@code false}, main secondaire {@link ItemInfo#EMPTY}.</li>
 *   <li>Codes de touche LWJGL 2 : convertis en codes GLFW (table vérifiée sur
 *       les constantes de {@code org.lwjgl.input.Keyboard} 2.9.4), ce
 *       qu'attend {@code KeystrokesModule}. Boutons de souris → non servis.</li>
 *   <li>Biome : pas d'identifiant, seulement un nom anglais — converti en
 *       {@code minecraft:snake_case}. Exact pour les biomes dont le nom n'a
 *       pas changé depuis ({@code plains}, {@code desert}…), approché sinon
 *       ({@code extreme_hills} au lieu de {@code windswept_hills}).</li>
 *   <li>Commandes : envoyées par {@code sendChatMessage("/" + commande)}.</li>
 * </ul>
 *
 * <h2>Accès volontairement NON LIÉS sur cette version</h2>
 *
 * {@code CLIENT_WINDOW} (le {@code Window} de 1.8.9 est l'ancien
 * {@code ScaledResolution}, pas une fenêtre — le servir tromperait
 * l'appelant), {@code FOG_SET_ENABLED} (brouillard pas encore porté),
 * {@code COMPONENT_TYPE_FOOD}/{@code COMPONENT_TYPE_ENCHANTMENTS} (pas de
 * composants), {@code NETWORK_HAS_COMMAND}/{@code NETWORK_ADD_CLIENT_COMMANDS}
 * (pas de Brigadier). Le registre sert la valeur de repli.
 *
 * <p>Même unité et même règle de traduction que la 1.21.11 : compilée contre
 * {@code src/stubs/v1_8_9}, noms Yarn legacy traduits au chargement parce
 * que la classe porte {@link YarnNamed} ; variables typées avec la classe
 * DÉCLARANTE du membre (champs compris).
 */
@YarnNamed
public final class AccessorBindings189 {

    private AccessorBindings189() {
    }

    /** Codes des options à poignée — voir {@link OptionHandle}. */
    private static final int OPTION_FOV = 0;
    private static final int OPTION_SENSITIVITY = 1;
    private static final int OPTION_GAMMA = 2;

    /**
     * Poignée d'option opaque : les options de 1.8.9 sont des champs
     * {@code float} publics, sans objet à tendre à l'appelant. Écriture
     * directe dans le champ, donc sans clamp — même garantie que
     * {@code SimpleOptionAccessor1211}.
     */
    @YarnNamed
    private static final class OptionHandle {
        final GameOptions options;
        final int which;

        OptionHandle(GameOptions options, int which) {
            this.options = options;
            this.which = which;
        }

        Float read() {
            if (which == OPTION_FOV) return Float.valueOf(options.fov);
            if (which == OPTION_SENSITIVITY) return Float.valueOf(options.sensitivity);
            return Float.valueOf(options.gamma);
        }

        void write(float value) {
            if (which == OPTION_FOV) options.fov = value;
            else if (which == OPTION_SENSITIVITY) options.sensitivity = value;
            else options.gamma = value;
        }
    }

    /** Taille de la table des codes LWJGL 2 (le plus grand, KEY_SLEEP, vaut 223). */
    private static final int LWJGL2_KEY_COUNT = 256;

    /**
     * Code LWJGL 2 → code GLFW, {@code -1} si sans équivalent. Valeurs LWJGL
     * lues sur {@code Keyboard.class} du jar 2.9.4-nightly-20150209 (celui du
     * profil 1.8.9) ; valeurs GLFW = constantes {@code GLFW_KEY_*}.
     */
    private static final int[] LWJGL2_TO_GLFW = new int[LWJGL2_KEY_COUNT];

    static {
        java.util.Arrays.fill(LWJGL2_TO_GLFW, -1);
        // Chiffres : KEY_1=2 … KEY_9=10, KEY_0=11.
        for (int i = 0; i < 9; i++) LWJGL2_TO_GLFW[2 + i] = 49 + i;
        LWJGL2_TO_GLFW[11] = 48;
        // Lettres, rangées par ligne de clavier (scancodes).
        mapRow(16, "QWERTYUIOP");
        mapRow(30, "ASDFGHJKL");
        mapRow(44, "ZXCVBNM");
        // F1-F10 = 59…68 ; F11=87, F12=88 ; F13-F18 = 100…105 ; F19=113.
        for (int i = 0; i < 10; i++) LWJGL2_TO_GLFW[59 + i] = 290 + i;
        LWJGL2_TO_GLFW[87] = 300;
        LWJGL2_TO_GLFW[88] = 301;
        for (int i = 0; i < 6; i++) LWJGL2_TO_GLFW[100 + i] = 302 + i;
        LWJGL2_TO_GLFW[113] = 308;
        // Pavé numérique.
        LWJGL2_TO_GLFW[82] = 320; LWJGL2_TO_GLFW[79] = 321; LWJGL2_TO_GLFW[80] = 322;
        LWJGL2_TO_GLFW[81] = 323; LWJGL2_TO_GLFW[75] = 324; LWJGL2_TO_GLFW[76] = 325;
        LWJGL2_TO_GLFW[77] = 326; LWJGL2_TO_GLFW[71] = 327; LWJGL2_TO_GLFW[72] = 328;
        LWJGL2_TO_GLFW[73] = 329; LWJGL2_TO_GLFW[83] = 330; LWJGL2_TO_GLFW[181] = 331;
        LWJGL2_TO_GLFW[55] = 332; LWJGL2_TO_GLFW[74] = 333; LWJGL2_TO_GLFW[78] = 334;
        LWJGL2_TO_GLFW[156] = 335; LWJGL2_TO_GLFW[141] = 336;
        // Ponctuation.
        LWJGL2_TO_GLFW[12] = 45;  // MINUS
        LWJGL2_TO_GLFW[13] = 61;  // EQUALS
        LWJGL2_TO_GLFW[26] = 91;  // LBRACKET
        LWJGL2_TO_GLFW[27] = 93;  // RBRACKET
        LWJGL2_TO_GLFW[39] = 59;  // SEMICOLON
        LWJGL2_TO_GLFW[40] = 39;  // APOSTROPHE
        LWJGL2_TO_GLFW[41] = 96;  // GRAVE
        LWJGL2_TO_GLFW[43] = 92;  // BACKSLASH
        LWJGL2_TO_GLFW[51] = 44;  // COMMA
        LWJGL2_TO_GLFW[52] = 46;  // PERIOD
        LWJGL2_TO_GLFW[53] = 47;  // SLASH
        LWJGL2_TO_GLFW[57] = 32;  // SPACE
        // Contrôle et navigation.
        LWJGL2_TO_GLFW[1] = 256;   // ESCAPE
        LWJGL2_TO_GLFW[28] = 257;  // RETURN
        LWJGL2_TO_GLFW[15] = 258;  // TAB
        LWJGL2_TO_GLFW[14] = 259;  // BACK
        LWJGL2_TO_GLFW[210] = 260; // INSERT
        LWJGL2_TO_GLFW[211] = 261; // DELETE
        LWJGL2_TO_GLFW[205] = 262; // RIGHT
        LWJGL2_TO_GLFW[203] = 263; // LEFT
        LWJGL2_TO_GLFW[208] = 264; // DOWN
        LWJGL2_TO_GLFW[200] = 265; // UP
        LWJGL2_TO_GLFW[201] = 266; // PRIOR
        LWJGL2_TO_GLFW[209] = 267; // NEXT
        LWJGL2_TO_GLFW[199] = 268; // HOME
        LWJGL2_TO_GLFW[207] = 269; // END
        LWJGL2_TO_GLFW[58] = 280;  // CAPITAL
        LWJGL2_TO_GLFW[70] = 281;  // SCROLL
        LWJGL2_TO_GLFW[69] = 282;  // NUMLOCK
        LWJGL2_TO_GLFW[183] = 283; // SYSRQ
        LWJGL2_TO_GLFW[197] = 284; // PAUSE
        LWJGL2_TO_GLFW[42] = 340;  // LSHIFT
        LWJGL2_TO_GLFW[29] = 341;  // LCONTROL
        LWJGL2_TO_GLFW[56] = 342;  // LMENU
        LWJGL2_TO_GLFW[219] = 343; // LMETA
        LWJGL2_TO_GLFW[54] = 344;  // RSHIFT
        LWJGL2_TO_GLFW[157] = 345; // RCONTROL
        LWJGL2_TO_GLFW[184] = 346; // RMENU
        LWJGL2_TO_GLFW[220] = 347; // RMETA
        LWJGL2_TO_GLFW[221] = 348; // APPS
    }

    private static void mapRow(int firstScancode, String letters) {
        for (int i = 0; i < letters.length(); i++) {
            LWJGL2_TO_GLFW[firstScancode + i] = letters.charAt(i);
        }
    }

    /**
     * Id numérique d'effet (1.8.9) → identifiant moderne. Les ids bruts sont
     * restés les mêmes jusqu'au passage aux registres, d'où une table fixe
     * plutôt qu'une traduction de nom.
     */
    private static final String[] EFFECT_IDS = {
        null, "speed", "slowness", "haste", "mining_fatigue", "strength",
        "instant_health", "instant_damage", "jump_boost", "nausea",
        "regeneration", "resistance", "fire_resistance", "water_breathing",
        "invisibility", "blindness", "night_vision", "hunger", "weakness",
        "poison", "wither", "health_boost", "absorption", "saturation",
    };

    static {
        // ── Client ─────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CLIENT_INSTANCE, (r, a) -> client());
        AccessorRegistry.bind(AccessPoint.CLIENT_PLAYER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.player; });
        AccessorRegistry.bind(AccessPoint.CLIENT_LEVEL, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.world; });
        AccessorRegistry.bind(AccessPoint.CLIENT_OPTIONS, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.options; });
        AccessorRegistry.bind(AccessPoint.CLIENT_USER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getSession(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SCREEN, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.currentScreen; });
        // INVENTORY avant CONTAINER : InventoryScreen hérite de HandledScreen.
        // Sur 1.8.9 elle est ABSTRAITE et couvre survie ET créatif.
        AccessorRegistry.bind(AccessPoint.SCREEN_KIND, (r, a) -> {
            if (r instanceof net.minecraft.client.gui.screen.ChatScreen) return "CHAT";
            if (r instanceof net.minecraft.client.gui.screen.ingame.InventoryScreen) return "INVENTORY";
            if (r instanceof net.minecraft.client.gui.screen.ingame.HandledScreen) return "CONTAINER";
            return "OTHER";
        });
        AccessorRegistry.bind(AccessPoint.CLIENT_SET_SCREEN, (r, a) -> {
            MinecraftClient c = mc(r);
            if (c == null || a.length < 1) return null;
            c.setScreen((net.minecraft.client.gui.screen.Screen) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.inGameHud; });
        AccessorRegistry.bind(AccessPoint.CLIENT_MOUSE_HANDLER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.mouse; });
        AccessorRegistry.bind(AccessPoint.CLIENT_RESOURCE_MANAGER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getResourceManager(); });
        // Getter STATIQUE public sur cette version : aucun accessor.
        AccessorRegistry.bind(AccessPoint.CLIENT_FPS, (r, a) -> Integer.valueOf(MinecraftClient.getCurrentFps()));
        AccessorRegistry.bind(AccessPoint.CLIENT_USERNAME, (r, a) -> {
            MinecraftClient c = mc(r);
            Session s = c == null ? null : c.getSession();
            return s == null ? null : s.getUsername();
        });

        // ── Options ────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTIONS_FOV, (r, a) -> optionHandle(r, OPTION_FOV));
        AccessorRegistry.bind(AccessPoint.OPTIONS_SENSITIVITY, (r, a) -> optionHandle(r, OPTION_SENSITIVITY));
        AccessorRegistry.bind(AccessPoint.OPTIONS_GAMMA, (r, a) -> optionHandle(r, OPTION_GAMMA));
        AccessorRegistry.bind(AccessPoint.OPTIONS_HIDE_GUI, (r, a) -> { GameOptions o = options(r); return o == null ? null : Boolean.valueOf(o.hudHidden); });
        // Un int nu sur cette version : 0 = 1re personne, 1 = dos, 2 = face.
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE, (r, a) -> {
            GameOptions o = options(r);
            if (o == null) return null;
            if (o.perspective == 1) return "third_person_back";
            if (o.perspective == 2) return "third_person_front";
            return "first_person";
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE_SET, (r, a) -> {
            GameOptions o = options(r);
            if (o == null || a.length < 1) return null;
            int view;
            if ("first_person".equals(a[0])) view = 0;
            else if ("third_person_back".equals(a[0])) view = 1;
            else if ("third_person_front".equals(a[0])) view = 2;
            else return null;
            o.perspective = view;
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_MOVEMENT_KEYS, (r, a) -> {
            GameOptions o = options(r);
            if (o == null) return null;
            return new Object[]{ o.forwardKey, o.leftKey, o.backKey, o.rightKey, o.jumpKey };
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_ACTION_KEYS, (r, a) -> {
            GameOptions o = options(r);
            if (o == null) return null;
            return new Object[]{ o.attackKey, o.useKey, o.sneakKey, o.sprintKey };
        });

        // ── Poignée d'option ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE, (r, a) ->
            r instanceof OptionHandle ? ((OptionHandle) r).read() : null);
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE_SET, (r, a) -> {
            if (!(r instanceof OptionHandle) || a.length < 1 || !(a[0] instanceof Number)) return null;
            ((OptionHandle) r).write(((Number) a[0]).floatValue());
            return null;
        });

        // ── Raccourci clavier ──────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.KEYBIND_IS_DOWN, (r, a) ->
            r instanceof KeyBinding ? Boolean.valueOf(((KeyBinding) r).isPressed()) : null);
        AccessorRegistry.bind(AccessPoint.KEYBIND_KEY_CODE, (r, a) -> {
            if (!(r instanceof KeyBinding)) return null;
            int code = ((KeyBinding) r).getCode();
            // Négatif = bouton de souris (bouton - 100) : aucun code GLFW de
            // TOUCHE ne le représente, le point d'accès répond « indisponible ».
            if (code <= 0 || code >= LWJGL2_KEY_COUNT) return null;
            int glfw = LWJGL2_TO_GLFW[code];
            return glfw < 0 ? null : Integer.valueOf(glfw);
        });

        // ── Faim/saturation ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.FOOD_LEVEL, (r, a) ->
            r instanceof HungerManager ? Integer.valueOf(((HungerManager) r).getFoodLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_SATURATION, (r, a) ->
            r instanceof HungerManager ? Float.valueOf(((HungerManager) r).getSaturationLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_EXHAUSTION, (r, a) ->
            r instanceof HungerManagerAccessor189 ? Float.valueOf(((HungerManagerAccessor189) r).la$exhaustion()) : null);

        // ── Joueur local ───────────────────────────────────────────────────
        // Position et angles sont des CHAMPS d'Entity : lus sur une variable
        // typée Entity, pour que le getfield désigne la classe déclarante.
        AccessorRegistry.bind(AccessPoint.PLAYER_POSITION, (r, a) -> {
            Entity p = player(r);
            return p == null ? null : new double[]{ p.x, p.y, p.z };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_YAW, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.yaw); });
        AccessorRegistry.bind(AccessPoint.PLAYER_PITCH, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.pitch); });
        AccessorRegistry.bind(AccessPoint.PLAYER_EYE_HEIGHT, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.getEyeHeight()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_PING, (r, a) -> {
            MinecraftClient c = mc(null);
            ClientPlayNetworkHandler net = c == null ? null : c.getNetworkHandler();
            Session s = c == null ? null : c.getSession();
            if (net == null || s == null) return null;
            PlayerListEntry entry = net.getPlayerListEntry(s.getUsername());
            return entry == null ? null : Integer.valueOf(entry.getLatency());
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HAS_EFFECT, (r, a) -> {
            LivingEntity p = player(r);
            int id = effectNumericId(a);
            return p == null || id < 0 ? null : Boolean.valueOf(p.hasStatusEffect(id));
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_REMOVE_EFFECT, (r, a) -> {
            LivingEntity p = player(r);
            int id = effectNumericId(a);
            if (p != null && id >= 0) p.removeEffect(id);
            return null;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_ACTIVE_EFFECTS, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null) return null;
            java.util.List<PlayerEffect> out = new java.util.ArrayList<>();
            StatusEffect[] table = StatusEffect.STATUS_EFFECTS;
            for (Object o : p.getStatusEffectInstances()) {
                if (!(o instanceof StatusEffectInstance)) continue;
                StatusEffectInstance i = (StatusEffectInstance) o;
                int id = i.getEffectId();
                StatusEffect effect = table != null && id >= 0 && id < table.length ? table[id] : null;
                String name = "?";
                int color = 0xFFFFFF;
                boolean beneficial = false;
                if (effect != null) {
                    String translated = I18n.translate(effect.getTranslationKey());
                    if (translated != null && !translated.isEmpty()) name = translated;
                    color = effect.getColor();
                    beneficial = !effect.isNegative();
                }
                // Pas d'effet infini en 1.8.9 : toujours false.
                out.add(new PlayerEffect(name, i.getAmplifier(), i.getDuration(),
                    false, color, beneficial, effectRegistryId(id)));
            }
            return out;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HEALTH, (r, a) -> { LivingEntity p = player(r); return p == null ? null : Float.valueOf(p.getHealth()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_MAX_HEALTH, (r, a) -> { LivingEntity p = player(r); return p == null ? null : Float.valueOf(p.getMaxHealth()); });
        // Pas de recharge d'attaque avant 1.9 : toujours pleine.
        AccessorRegistry.bind(AccessPoint.PLAYER_ATTACK_STRENGTH, (r, a) ->
            player(r) == null ? null : Float.valueOf(1f));
        AccessorRegistry.bind(AccessPoint.PLAYER_SWING_PROGRESS, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null || a.length < 1 || !(a[0] instanceof Number)) return null;
            return Float.valueOf(p.getHandSwingProgress(((Number) a[0]).floatValue()));
        });
        // Les deux champs publics écrits comme le fait LivingEntity.swingHand(),
        // SANS son paquet d'animation : la relance reste purement visuelle.
        // 6 = durée vanilla hors Célérité/Fatigue (getHandSwingDuration est privée).
        AccessorRegistry.bind(AccessPoint.PLAYER_RESTART_SWING_ANIMATION, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null) return null;
            if (p.handSwinging && p.handSwingTicks >= 0 && p.handSwingTicks < 3) return Boolean.FALSE;
            p.handSwingTicks = -1;
            p.handSwinging = true;
            return Boolean.TRUE;
        });
        // Genres fins pour les positions d'objet 1.7 (2026-09-16) : « block » =
        // modèle en volume (ItemRenderer.hasDepth, bjh.a(Lzx;)Z), « rod » =
        // objet dessiné retourné (Item.shouldRotate, zw.e()Z — la canne à
        // pêche), puis arc et épée.
        AccessorRegistry.bind(AccessPoint.PLAYER_MAIN_HAND_KIND, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null) return null;
            ItemStack stack = p.getMainHandStack();
            if (stack == null) return "empty";
            Item item = stack.getItem();
            MinecraftClient c = mc(null);
            if (c != null && c.getItemRenderer().hasDepth(stack)) return "block";
            if (item.shouldRotate()) return "rod";
            if (item instanceof BowItem) return "bow";
            return item instanceof SwordItem ? "sword" : "other";
        });
        // 1.8.9 : seule l'épée bloque, et l'objet utilisé est celui en main —
        // même résultat que PlayerEntity.isBlocking (wn.bW, sans nom Yarn).
        // Vaut aussi pour les AUTRES joueurs : leur durée d'utilisation est
        // tenue à jour par le drapeau d'usage que le serveur leur envoie.
        AccessorRegistry.bind(AccessPoint.ENTITY_IS_BLOCKING, (r, a) -> {
            if (!(r instanceof PlayerEntity)) return Boolean.FALSE;
            PlayerEntity p = (PlayerEntity) r;
            ItemStack stack = p.getMainHandStack();
            return Boolean.valueOf(p.getItemUseTicks() > 0 && stack != null && stack.getItem() instanceof SwordItem);
        });
        // Même appel que MinecraftClient.handleBlockBreaking quand on frappe un
        // bloc (javap ave.b(Z)V, offset 111) : particleManager.addBlockBreakingParticles.
        AccessorRegistry.bind(AccessPoint.CLIENT_BLOCK_HIT_PARTICLES, (r, a) -> {
            MinecraftClient c = mc(r);
            BlockHitResult hit = c == null ? null : c.result;
            if (hit == null || hit.type != BlockHitResult.Type.BLOCK || c.particleManager == null) return null;
            BlockPos pos = hit.getBlockPos();
            if (pos != null) c.particleManager.addBlockBreakingParticles(pos, hit.direction);
            return null;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_ITEM_USE, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null) return null;
            int remaining = p.getItemUseTicks();
            ItemStack stack = p.getMainHandStack();
            if (remaining <= 0 || stack == null) return null;
            return new int[]{ remaining, stack.getMaxUseTime() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_INPUT_SNEAKING, (r, a) -> {
            ClientPlayerEntity p = player(r);
            Input input = p == null ? null : p.input;
            return input == null ? null : Boolean.valueOf(input.sneaking);
        });
        AccessorRegistry.bind(AccessPoint.CLIENT_CROSSHAIR_TARGET, (r, a) -> {
            MinecraftClient c = mc(r);
            BlockHitResult hit = c == null ? null : c.result;
            if (hit == null || hit.type == null) return null;
            if (hit.type == BlockHitResult.Type.BLOCK) return "block";
            if (hit.type == BlockHitResult.Type.ENTITY) return "entity";
            return "miss";
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_EQUIPMENT, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null) return null;
            // getArmorSlot : 0 = bottes … 3 = casque (bytecode : inventory.armor[i]).
            LivingEntity living = p;
            return new ItemInfo[]{
                item(living.getArmorSlot(3)),
                item(living.getArmorSlot(2)),
                item(living.getArmorSlot(1)),
                item(living.getArmorSlot(0)),
                item(p.getMainHandStack()),
                ItemInfo.EMPTY,
            };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MOVEMENT_FLAGS, (r, a) -> {
            Entity e = player(r);
            if (e == null) return null;
            // {véhicule, vol plané, dans l'eau, sprint, au sol, nage} — ni
            // élytres ni nage sur cette version.
            return new boolean[]{ e.hasVehicle(), false, e.isTouchingWater(),
                e.isSprinting(), e.onGround, false };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MODE_FLAGS, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null) return null;
            return new boolean[]{ p.abilities != null && p.abilities.creativeMode, p.isSpectator() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_FOOD, (r, a) -> {
            PlayerEntity p = player(r);
            HungerManager food = p == null ? null : p.getHungerManager();
            if (food == null) return null;
            float exhaustion = food instanceof HungerManagerAccessor189
                ? ((HungerManagerAccessor189) food).la$exhaustion() : 0f;
            return new float[]{ food.getFoodLevel(), food.getSaturationLevel(), exhaustion };
        });
        // Pas de main principale réglable avant 1.9.
        AccessorRegistry.bind(AccessPoint.PLAYER_MAIN_ARM_RIGHT, (r, a) ->
            player(r) == null ? null : Boolean.TRUE);
        // Le seul enchantement demandé ("lunge") n'existe pas en 1.8.9.
        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_ENCHANT_LEVEL, (r, a) ->
            player(r) == null ? null : Integer.valueOf(0));
        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_FOOD, (r, a) -> {
            PlayerEntity p = player(r);
            return p == null ? null : edibleFood(p, p.getMainHandStack());
        });

        // ── Sons d'interface ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.SOUND_PLAY_UI, (r, a) -> {
            if (a.length < 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            Identifier sound = soundById(a[0]);
            MinecraftClient c = mc(null);
            SoundManager manager = c == null ? null : c.getSoundManager();
            if (sound == null || manager == null) return null;
            float volume = ((Number) a[1]).floatValue();
            float pitch = ((Number) a[2]).floatValue();
            // Pas de son « d'interface » à volume libre : master() fixe le
            // volume à 0,25. Avec un joueur, on joue donc le son À SA POSITION
            // — l'écouteur de 1.8.9 est posé sur l'entité, distance nulle,
            // volume demandé intact.
            Entity e = c.player;
            manager.play(e == null
                ? PositionedSoundInstance.master(sound, pitch)
                : new PositionedSoundInstance(sound, volume, pitch,
                    (float) e.x, (float) (e.y + e.getEyeHeight()), (float) e.z));
            return Boolean.TRUE;
        });

        // ── Monde ──────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.LEVEL_BIOME_ID, (r, a) -> {
            if (a.length < 3 || !(a[0] instanceof Number) || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            MinecraftClient c = mc(null);
            if (c == null || c.world == null) return null;
            BlockView view = c.world;
            Biome biome = view.getBiome(new BlockPos(
                ((Number) a[0]).intValue(), ((Number) a[1]).intValue(), ((Number) a[2]).intValue()));
            return biome == null ? null : biomeId(biome.name);
        });
        AccessorRegistry.bind(AccessPoint.LEVEL_IS_CLIENT, (r, a) ->
            Boolean.valueOf(r instanceof net.minecraft.client.world.ClientWorld));
        AccessorRegistry.bind(AccessPoint.IDENTIFIER_PATH, (r, a) ->
            r instanceof Identifier ? ((Identifier) r).getPath() : null);

        // ── Connexion au serveur ───────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.NETWORK_CONNECTION, (r, a) -> connection());
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_CHAT, (r, a) -> {
            ClientPlayerEntity p = player(null);
            if (p == null || a.length < 1 || !(a[0] instanceof String)) return null;
            p.sendChatMessage((String) a[0]);
            return Boolean.TRUE;
        });
        // Pas de canal de commande séparé : une commande EST un message « / ».
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_COMMAND, (r, a) -> {
            ClientPlayerEntity p = player(null);
            if (p == null || a.length < 1 || !(a[0] instanceof String)) return null;
            p.sendChatMessage("/" + a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_SERVER_ADDRESS, (r, a) -> {
            ClientPlayNetworkHandler net = connection();
            if (net == null) return null;
            // Le serveur courant est porté par le CLIENT sur cette version.
            MinecraftClient c = mc(null);
            ServerInfo server = c == null ? null : c.getCurrentServerEntry();
            String ip = server == null ? null : server.address;
            ClientConnection raw = net.getClientConnection();
            java.net.SocketAddress remote = raw == null ? null : raw.getAddress();
            return new Object[]{ ip, remote };
        });

        // ── Chat ───────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CHAT_HEAD_MESSAGE, (r, a) -> {
            java.util.List<Object> messages = messages(chat());
            if (messages == null || messages.isEmpty()) return null;
            Object head = messages.get(0);
            if (!(head instanceof ChatHudLine)) return null;
            Text content = ((ChatHudLine) head).getText();
            String plain = content == null ? null : content.asUnformattedString();
            if (plain == null) return null;
            return new Object[]{ head, content, plain };
        });
        AccessorRegistry.bind(AccessPoint.CHAT_MERGE_REPEATED, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof Text) || !(a[1] instanceof Number)) return null;
            ChatHud chat = chat();
            java.util.List<Object> messages = messages(chat);
            if (messages == null || messages.size() < 2) return null;
            if (!(messages.get(0) instanceof ChatHudLine)) return null;
            messages.remove(0);
            messages.remove(0);

            // Compteur en Formatting.GRAY = 0xAAAAAA, la couleur des autres
            // versions. Text est MUTABLE ici : copy() d'abord, sinon append() modifierait
            // le message d'origine que l'appelant détient encore.
            Text counter = new LiteralText(" (x" + ((Number) a[1]).intValue() + ")");
            counter.getStyle().setFormatting(Formatting.GRAY);
            Text combined = ((Text) a[0]).copy().append(counter);
            // id 0 = ligne ordinaire (un id non nul effacerait les lignes du
            // même id). reset() reconstruit les lignes visibles depuis
            // l'historique qu'on vient de retoucher.
            chat.addMessage(combined, 0);
            chat.reset();
            return messages.isEmpty() ? null : messages.get(0);
        });

        // ── Fenêtre et ressources ──────────────────────────────────────────
        // Window = l'ancien ScaledResolution : construit à la demande.
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI_SIZE, (r, a) -> {
            MinecraftClient c = mc(r);
            if (c == null) return null;
            Window w = new Window(c);
            return new int[]{ w.getWidth(), w.getHeight() };
        });
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI_SCALE, (r, a) -> {
            MinecraftClient c = mc(r);
            return c == null ? null : Integer.valueOf(new Window(c).getScaleFactor());
        });
        AccessorRegistry.bind(AccessPoint.RESOURCE_BYTES, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof String) || !(a[1] instanceof String)) return null;
            MinecraftClient c = mc(null);
            ResourceManager manager = c == null ? null : c.getResourceManager();
            if (manager == null) return null;
            try {
                // Lève IOException (pas d'Optional) si la ressource manque.
                Resource resource = manager.getResource(new Identifier((String) a[0], (String) a[1]));
                if (resource == null) return null;
                try (java.io.InputStream in = resource.getInputStream()) {
                    return readAll(in);
                }
            } catch (java.io.FileNotFoundException missing) {
                return null;
            } catch (Throwable t) {
                LauncherLog.err("[AccessorBindings189] RESOURCE_BYTES " + a[0] + ":" + a[1] + " : " + t);
                return null;
            }
        });

        // ── Serveur courant (champs publics) ───────────────────────────────
        AccessorRegistry.bind(AccessPoint.SERVER_IP, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).address : null);
        AccessorRegistry.bind(AccessPoint.SERVER_NAME, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).name : null);
    }

    /**
     * Aliment de cette pile s'il est RÉELLEMENT consommable maintenant, en
     * {@code {faim, saturation gagnée}} — sinon {@code null}.
     *
     * <p>{@code FoodItem.getSaturation} rend le MODIFICATEUR ; les autres
     * versions servent les points gagnés. Conversion par la formule de
     * {@code HungerManager.add(IF)} (vérifiée bytecode) : faim × modif × 2.
     * Sans l'accessor tissé, {@code alwaysEdible} vaut {@code false} — seul
     * effet : la pomme dorée n'est pas signalée quand la barre est pleine.
     */
    private static float[] edibleFood(PlayerEntity player, ItemStack stack) {
        if (stack == null) return null;
        Item item = stack.getItem();
        if (!(item instanceof FoodItem)) return null;
        FoodItem food = (FoodItem) item;
        boolean always = item instanceof FoodItemAccessor189 && ((FoodItemAccessor189) item).la$alwaysEdible();
        if (!player.canConsume(always)) return null;
        int hunger = food.getHungerPoints(stack);
        return new float[]{ hunger, hunger * food.getSaturation(stack) * 2f };
    }

    /** Lit un flux entier — les ressources visées sont de petits PNG d'interface. */
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** « minecraft:speed » ou « speed » → id numérique 1.8.9, {@code -1} si inconnu. */
    private static int effectNumericId(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof String)) return -1;
        String id = (String) args[0];
        int sep = id.indexOf(':');
        String path = sep < 0 ? id : id.substring(sep + 1);
        for (int i = 1; i < EFFECT_IDS.length; i++) {
            if (EFFECT_IDS[i].equals(path)) return i;
        }
        return -1;
    }

    private static String effectRegistryId(int id) {
        return id > 0 && id < EFFECT_IDS.length ? "minecraft:" + EFFECT_IDS[id] : null;
    }

    /**
     * Nom anglais de biome → {@code minecraft:snake_case} :
     * {@code "Extreme Hills+"} → {@code extreme_hills}, {@code "MushroomIsland"}
     * → {@code mushroom_island}. Approché par nature, voir la javadoc de classe.
     */
    private static String biomeId(String name) {
        if (name == null || name.isEmpty()) return null;
        StringBuilder out = new StringBuilder("minecraft:");
        char prev = ' ';
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isUpperCase(ch) && Character.isLowerCase(prev)) out.append('_');
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            } else if (ch == ' ' && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
            prev = ch;
        }
        int end = out.length();
        while (end > 10 && out.charAt(end - 1) == '_') end--;
        return out.substring(0, end);
    }

    /**
     * Identifiant moderne → son 1.8.9. Les noms de sons ont été refondus en
     * 1.9 : table explicite, un identifiant sans équivalent rend {@code null}
     * (le carillon d'améthyste n'existe pas ici — pas de substitut deviné).
     */
    private static Identifier soundById(Object id) {
        if ("minecraft:entity.experience_orb.pickup".equals(id)) return new Identifier("minecraft", "random.orb");
        return null;
    }

    /** La connexion courante, ou {@code null} en solo/hors partie. */
    private static ClientPlayNetworkHandler connection() {
        MinecraftClient c = mc(null);
        return c == null ? null : c.getNetworkHandler();
    }

    /** Le chat vanilla, ou {@code null} s'il n'est pas encore là. */
    private static ChatHud chat() {
        MinecraftClient c = mc(null);
        InGameHud hud = c == null ? null : c.inGameHud;
        return hud == null ? null : hud.getChatHud();
    }

    /** L'historique du chat, ou {@code null} si l'accessor n'a pas été tissé. */
    private static java.util.List<Object> messages(ChatHud chat) {
        return chat instanceof ChatHudAccessor189 ? ((ChatHudAccessor189) chat).la$messages() : null;
    }

    /**
     * Pile du jeu → porteur neutre. Case vide = {@code null} sur cette version
     * (pas de pile vide) ; {@code count} est un champ public.
     */
    private static ItemInfo item(ItemStack stack) {
        if (stack == null) return ItemInfo.EMPTY;
        boolean damageable = stack.isDamageable();
        return new ItemInfo(stack, false, stack.count, damageable,
            damageable ? stack.getDamage() : 0,
            damageable ? stack.getMaxDamage() : 0);
    }

    private static OptionHandle optionHandle(Object receiver, int which) {
        GameOptions o = options(receiver);
        return o == null ? null : new OptionHandle(o, which);
    }

    /** Receveur → joueur local ; {@code null} = celui du client courant. */
    private static ClientPlayerEntity player(Object receiver) {
        if (receiver instanceof ClientPlayerEntity) return (ClientPlayerEntity) receiver;
        MinecraftClient c = mc(null);
        return c == null ? null : c.player;
    }

    /** Receveur → client ; {@code null} = l'instance courante. */
    private static MinecraftClient mc(Object receiver) {
        Object target = receiver != null ? receiver : client();
        return target instanceof MinecraftClient ? (MinecraftClient) target : null;
    }

    /** Instance courante, ou {@code null} si la classe n'est pas encore initialisable. */
    private static MinecraftClient client() {
        try {
            return MinecraftClient.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Receveur → options ; {@code null} = celles du client courant. */
    private static GameOptions options(Object receiver) {
        Object target = receiver;
        if (target == null) {
            MinecraftClient c = mc(null);
            target = c == null ? null : c.options;
        }
        return target instanceof GameOptions ? (GameOptions) target : null;
    }
}
