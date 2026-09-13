package com.yuyuframe.launcheragent.apimixin.v1_21_11.core;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.data.PlayerEffect;
import com.yuyuframe.launcheragent.apimixin.mapping.YarnNamed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.chat.ChatHudAccessor1211;
import com.yuyuframe.launcheragent.apimixin.v1_21_11.option.SimpleOptionAccessor1211;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.session.Session;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.component.ComponentsAccess;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Liaisons {@link AccessPoint} → données du jeu pour la tranche 1.21.11 —
 * pendant d'{@code AccessorBindings261}.
 *
 * <h2>Pourquoi presque aucun accessor Mixin ici</h2>
 *
 * La 26.1.2 passe par un accessor pour CHAQUE champ, parce qu'ils y sont
 * privés. Sur 1.21.11, {@code player}, {@code world}, {@code options},
 * {@code currentScreen}, {@code inGameHud} et {@code mouse} sont PUBLICS
 * (vérifié {@code javap} sur le jar client réel), et {@code window} /
 * {@code session} / {@code resourceManager} ont des getters publics. Du code
 * TYPÉ suffit donc — ni mixin, ni réflexion.
 *
 * <p>Trois exceptions, toutes des champs PRIVÉS : {@code currentFps},
 * l'épuisement du joueur, et l'historique du chat — servies par un accessor
 * classique.
 *
 * <h2>Unité de compilation et traduction</h2>
 *
 * Ce fichier est compilé contre {@code src/stubs/v1_21_11} et nomme les
 * classes du jeu sous leur nom YARN ; {@code YarnNamedRemapper} les traduit au
 * chargement vers les noms du loader actif, parce que la classe porte
 * {@link YarnNamed}. Sans ce marqueur, elle chercherait des classes Yarn
 * inexistantes en jeu.
 *
 * <p>Rangée à côté de ses accessors, comme {@code AccessorBindings261}, depuis
 * le 2026-09-13 (auparavant dans un paquet {@code typed/v1_21_11} à part, seul
 * moyen de la désigner au remappeur tant qu'il sélectionnait par paquet).
 *
 * <p>Chargée par NOM depuis {@code AccessorRegistry.ensureInitialized()} —
 * jamais référencée par du code neutre, qui tomberait sinon en
 * {@code NoClassDefFoundError} sur toute autre version.
 *
 * <h2>Accès volontairement NON LIÉ sur cette version</h2>
 *
 * {@code FOG_SET_ENABLED} seulement : le brouillard de cette version passe par
 * {@code FogDataAccessor1211}, pas par un drapeau global. Le registre le
 * signale une fois et sert la valeur de repli — c'est le comportement prévu,
 * pas un échec.
 *
 * <p>Tous les autres points sont liés depuis le 2026-09-12. Les trois derniers
 * manquants l'ont été par des chemins PUBLICS que la 26.1.2 n'a pas :
 * {@code getBoundKeyTranslationKey()} pour le code de touche, et
 * {@code addMessage}/{@code refresh} pour le chat.
 */
@YarnNamed
public final class AccessorBindings1211 {

    private AccessorBindings1211() {
    }

    /**
     * Aliment de cette pile s'il est RÉELLEMENT consommable maintenant, en
     * {@code {faim, saturation}} — sinon {@code null}.
     *
     * <p>{@code canConsume(boolean)} est la garde qu'utilise vanilla lui-même ;
     * elle s'appelle {@code canEat(boolean)} en 26.1.2.
     */
    private static float[] edibleFood(PlayerEntity player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ComponentsAccess components = stack;
        Object raw = components.get(DataComponentTypes.FOOD);
        if (!(raw instanceof FoodComponent)) return null;
        FoodComponent food = (FoodComponent) raw;
        return player.canConsume(food.canAlwaysEat())
            ? new float[]{ food.nutrition(), food.saturation() } : null;
    }

    /** Lit un flux entier — les ressources visées sont de petits PNG d'interface. */
    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** La connexion courante, ou {@code null} en solo/hors partie. Méthode publique, pas un champ. */
    private static ClientPlayNetworkHandler connection() {
        MinecraftClient c = mc(null);
        return c == null ? null : c.getNetworkHandler();
    }

    /** Gris du compteur de répétitions — la même valeur qu'en 26.1.2, voir {@code AccessPoint.CHAT_MERGE_REPEATED}. */
    private static final int CHAT_COUNTER_COLOR = 0xAAAAAA;

    static {
        // ── Client ─────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CLIENT_INSTANCE, (r, a) -> client());
        AccessorRegistry.bind(AccessPoint.CLIENT_PLAYER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.player; });
        AccessorRegistry.bind(AccessPoint.CLIENT_LEVEL, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.world; });
        AccessorRegistry.bind(AccessPoint.CLIENT_OPTIONS, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.options; });
        AccessorRegistry.bind(AccessPoint.CLIENT_USER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getSession(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SCREEN, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.currentScreen; });
        // INVENTORY avant CONTAINER : InventoryScreen hérite de HandledScreen.
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
        AccessorRegistry.bind(AccessPoint.CLIENT_WINDOW, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getWindow(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_MOUSE_HANDLER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.mouse; });
        AccessorRegistry.bind(AccessPoint.CLIENT_RESOURCE_MANAGER, (r, a) -> { MinecraftClient c = mc(r); return c == null ? null : c.getResourceManager(); });
        // Champ statique PRIVÉ : seul accessor Mixin de la classe. Il lève s'il
        // n'est pas tissé (plutôt que de rendre un 0 faux) — AccessorRegistry
        // attrape, journalise une fois, et l'appelant reçoit son repli.
        AccessorRegistry.bind(AccessPoint.CLIENT_FPS, (r, a) -> Integer.valueOf(MinecraftClientAccessor1211.la$fps()));
        AccessorRegistry.bind(AccessPoint.CLIENT_USERNAME, (r, a) -> {
            MinecraftClient c = mc(r);
            Session s = c == null ? null : c.getSession();
            return s == null ? null : s.getUsername();
        });

        // ── Options ────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTIONS_FOV, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getFov(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_SENSITIVITY, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getMouseSensitivity(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_GAMMA, (r, a) -> { GameOptions o = options(r); return o == null ? null : o.getGamma(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_HIDE_GUI, (r, a) -> { GameOptions o = options(r); return o == null ? null : Boolean.valueOf(o.hudHidden); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE, (r, a) -> {
            GameOptions o = options(r);
            Perspective view = o == null ? null : o.getPerspective();
            if (view == Perspective.THIRD_PERSON_BACK) return "third_person_back";
            if (view == Perspective.THIRD_PERSON_FRONT) return "third_person_front";
            return view == null ? null : "first_person";
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE_SET, (r, a) -> {
            GameOptions o = options(r);
            if (o == null || a.length < 1) return null;
            Perspective view = perspective(a[0]);
            if (view == null) return null;
            o.setPerspective(view);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_MOVEMENT_KEYS, (r, a) -> {
            GameOptions o = options(r);
            if (o == null) return null;
            return new Object[]{ o.forwardKey, o.leftKey, o.backKey, o.rightKey, o.jumpKey };
        });

        // ── Poignée d'option ───────────────────────────────────────────────
        // Par le CHAMP et non par setValue(), pourtant publique : le setter
        // déclenche la validation vanilla, qui CLAMPE — exactement ce que ce
        // point d'accès existe pour éviter. Voir SimpleOptionAccessor1211 pour
        // les deux modules que le clamp cassait.
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE, (r, a) ->
            r instanceof SimpleOptionAccessor1211 ? ((SimpleOptionAccessor1211) r).la$value() : null);
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE_SET, (r, a) -> {
            if (!(r instanceof SimpleOptionAccessor1211) || a.length < 1) return null;
            ((SimpleOptionAccessor1211) r).la$setValue(a[0]);
            return null;
        });

        // ── Raccourci clavier ──────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.KEYBIND_IS_DOWN, (r, a) ->
            r instanceof KeyBinding ? Boolean.valueOf(((KeyBinding) r).isPressed()) : null);
        AccessorRegistry.bind(AccessPoint.KEYBIND_KEY_CODE, (r, a) -> {
            if (!(r instanceof KeyBinding)) return null;
            // AUCUN accessor Mixin ici, contrairement à la 26.1.2 : boundKey
            // est privé, mais la clé de TRADUCTION qui le désigne est publique,
            // et InputUtil sait revenir de l'une à l'autre. Un chemin public
            // vaut toujours mieux qu'un mixin de plus à tisser.
            String translationKey = ((KeyBinding) r).getBoundKeyTranslationKey();
            if (translationKey == null) return null;
            InputUtil.Key key = InputUtil.fromTranslationKey(translationKey);
            return key == null ? null : Integer.valueOf(key.getCode());
        });

        // ── Faim/saturation ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.FOOD_LEVEL, (r, a) ->
            r instanceof HungerManager ? Integer.valueOf(((HungerManager) r).getFoodLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_SATURATION, (r, a) ->
            r instanceof HungerManager ? Float.valueOf(((HungerManager) r).getSaturationLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_EXHAUSTION, (r, a) ->
            r instanceof HungerManagerAccessor1211 ? Float.valueOf(((HungerManagerAccessor1211) r).la$exhaustion()) : null);

        // ── Joueur local : OPÉRATIONS ──────────────────────────────────────
        // Mêmes opérations qu'en 26.1.2, noms Yarn : getYaw/getPitch (et non
        // getYRot/getXRot), getStandingEyeHeight (et non getEyeHeight),
        // getAttackCooldownProgress (et non getAttackStrengthScale). C'est
        // exactement ce que ces points d'accès existent pour absorber.
        // VARIABLES TYPÉES AVEC LA CLASSE DÉCLARANTE (Entity, LivingEntity,
        // PlayerEntity) et non ClientPlayerEntity : javac écrit le type STATIQUE
        // du receveur comme propriétaire de l'appel, or Yarn range chaque
        // méthode sous sa déclarante. Typer en ClientPlayerEntity produisait
        // `ClientPlayerEntity.getX()D`, introuvable après traduction — 6
        // références cassées, vues au banc RemapCheck avant toute mise en jeu.
        AccessorRegistry.bind(AccessPoint.PLAYER_POSITION, (r, a) -> {
            Entity p = player(r);
            return p == null ? null : new double[]{ p.getX(), p.getY(), p.getZ() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_YAW, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.getYaw()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_PITCH, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.getPitch()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_EYE_HEIGHT, (r, a) -> { Entity p = player(r); return p == null ? null : Float.valueOf(p.getStandingEyeHeight()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_PING, (r, a) -> {
            MinecraftClient c = mc(null);
            ClientPlayNetworkHandler net = c == null ? null : c.getNetworkHandler();
            Session s = c == null ? null : c.getSession();
            if (net == null || s == null) return null;
            // Recherche par PSEUDO et non par UUID : Entity n'expose pas de
            // getUuid() dans les mappings Yarn 1.21.11 (seuls le champ uuid et
            // setUuid y figurent). Le point d'accès absorbe la divergence.
            PlayerListEntry entry = net.getPlayerListEntry(s.getUsername());
            return entry == null ? null : Integer.valueOf(entry.getLatency());
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HAS_EFFECT, (r, a) -> {
            LivingEntity p = player(r);
            RegistryEntry effect = effectById(a);
            return p == null || effect == null ? null : Boolean.valueOf(p.hasStatusEffect(effect));
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_REMOVE_EFFECT, (r, a) -> {
            LivingEntity p = player(r);
            RegistryEntry effect = effectById(a);
            if (p != null && effect != null) p.removeStatusEffect(effect);
            return null;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_ACTIVE_EFFECTS, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null) return null;
            java.util.List<PlayerEffect> out = new java.util.ArrayList<>();
            for (Object o : p.getStatusEffects()) {
                if (!(o instanceof StatusEffectInstance)) continue;
                StatusEffectInstance i = (StatusEffectInstance) o;
                RegistryEntry holder = i.getEffectType();
                Object v = holder != null ? holder.value() : null;
                StatusEffect effect = v instanceof StatusEffect ? (StatusEffect) v : null;
                String name = "?";
                int color = 0xFFFFFF;
                boolean beneficial = false;
                if (effect != null) {
                    Text display = effect.getName();
                    if (display != null && display.getString() != null && !display.getString().isEmpty()) {
                        name = display.getString();
                    }
                    color = effect.getColor();
                    beneficial = effect.isBeneficial();
                }
                out.add(new PlayerEffect(name, i.getAmplifier(), i.getDuration(),
                    i.isInfinite(), color, beneficial, registryId(holder)));
            }
            return out;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HEALTH, (r, a) -> { LivingEntity p = player(r); return p == null ? null : Float.valueOf(p.getHealth()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_MAX_HEALTH, (r, a) -> { LivingEntity p = player(r); return p == null ? null : Float.valueOf(p.getMaxHealth()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_ATTACK_STRENGTH, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null || a.length < 1 || !(a[0] instanceof Number)) return null;
            return Float.valueOf(p.getAttackCooldownProgress(((Number) a[0]).floatValue()));
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_EQUIPMENT, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null) return null;
            return new ItemInfo[]{
                item(p.getEquippedStack(EquipmentSlot.HEAD)),
                item(p.getEquippedStack(EquipmentSlot.CHEST)),
                item(p.getEquippedStack(EquipmentSlot.LEGS)),
                item(p.getEquippedStack(EquipmentSlot.FEET)),
                item(p.getStackInHand(Hand.MAIN_HAND)),
                item(p.getStackInHand(Hand.OFF_HAND)),
            };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MOVEMENT_FLAGS, (r, a) -> {
            // Trois noms sur quatre diffèrent de la 26.1.2 — et le receveur est
            // typé avec la classe DÉCLARANTE : isGliding est sur LivingEntity,
            // les trois autres sur Entity (voir le commentaire ci-dessus).
            LivingEntity p = player(r);
            if (p == null) return null;
            Entity e = p;
            return new boolean[]{ e.hasVehicle(), p.isGliding(), e.isTouchingWater(),
                e.isSprinting(), e.isOnGround(), e.isSwimming() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MODE_FLAGS, (r, a) -> {
            // isCreative est sur PlayerEntity, isSpectator sur Entity : deux
            // classes déclarantes, donc deux variables typées différemment.
            PlayerEntity p = player(r);
            if (p == null) return null;
            Entity e = p;
            return new boolean[]{ p.isCreative(), e.isSpectator() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_FOOD, (r, a) -> {
            PlayerEntity p = player(r);
            HungerManager food = p == null ? null : p.getHungerManager();
            if (food == null) return null;
            // L'épuisement est le seul des trois à être privé : accessor Mixin,
            // comme en 26.1.2 (voir FOOD_EXHAUSTION).
            float exhaustion = food instanceof HungerManagerAccessor1211
                ? ((HungerManagerAccessor1211) food).la$exhaustion() : 0f;
            return new float[]{ food.getFoodLevel(), food.getSaturationLevel(), exhaustion };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MAIN_ARM_RIGHT, (r, a) -> {
            LivingEntity p = player(r);
            return p == null ? null : Boolean.valueOf(p.getMainArm() == Arm.RIGHT);
        });

        // ── Sons d'interface ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.SOUND_PLAY_UI, (r, a) -> {
            if (a.length < 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            SoundEvent sound = soundById(a[0]);
            MinecraftClient c = mc(null);
            SoundManager manager = c == null ? null : c.getSoundManager();
            if (sound == null || manager == null) return null;
            manager.play(PositionedSoundInstance.ui(sound,
                ((Number) a[1]).floatValue(), ((Number) a[2]).floatValue()));
            return Boolean.TRUE;
        });

        // ── Monde ──────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.LEVEL_BIOME_ID, (r, a) -> {
            if (a.length < 3 || !(a[0] instanceof Number) || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            MinecraftClient c = mc(null);
            if (c == null || c.world == null) return null;
            // Typé WorldView, la classe DÉCLARANTE de getBiome — pas
            // ClientWorld (leçon v1076 : javac écrit le type statique du
            // receveur comme propriétaire de l'appel).
            WorldView view = c.world;
            RegistryEntry biome = view.getBiome(BlockPos.ofFloored(
                ((Number) a[0]).intValue(), ((Number) a[1]).intValue(), ((Number) a[2]).intValue()));
            return biome == null ? null : registryId(biome);
        });
        AccessorRegistry.bind(AccessPoint.LEVEL_IS_CLIENT, (r, a) ->
            Boolean.valueOf(r instanceof net.minecraft.client.world.ClientWorld));
        AccessorRegistry.bind(AccessPoint.IDENTIFIER_PATH, (r, a) ->
            r instanceof Identifier ? ((Identifier) r).getPath() : null);

        // ── Connexion au serveur ───────────────────────────────────────────
        // Tout est PUBLIC ici comme en 26.1.2 ; seuls les noms changent
        // (sendChat→sendChatMessage, sendCommand→sendChatCommand,
        // getServerData→getServerInfo, getCommands→getCommandDispatcher,
        // getRemoteAddress→getAddress).
        AccessorRegistry.bind(AccessPoint.NETWORK_CONNECTION, (r, a) -> connection());
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_CHAT, (r, a) -> {
            ClientPlayNetworkHandler c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            c.sendChatMessage((String) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_COMMAND, (r, a) -> {
            ClientPlayNetworkHandler c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            c.sendChatCommand((String) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_SERVER_ADDRESS, (r, a) -> {
            ClientPlayNetworkHandler c = connection();
            if (c == null) return null;
            ServerInfo server = c.getServerInfo();
            String ip = server == null ? null : server.address;
            ClientConnection raw = c.getConnection();
            java.net.SocketAddress remote = raw == null ? null : raw.getAddress();
            return new Object[]{ ip, remote };
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_HAS_COMMAND, (r, a) -> {
            ClientPlayNetworkHandler c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            com.mojang.brigadier.CommandDispatcher<?> commands = c.getCommandDispatcher();
            if (commands == null || commands.getRoot() == null) return Boolean.FALSE;
            return Boolean.valueOf(commands.getRoot().getChild((String) a[0]) != null);
        });

        // ── Chat ───────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CHAT_HEAD_MESSAGE, (r, a) -> {
            ChatHud chat = chat();
            java.util.List<Object> messages = messages(chat);
            if (messages == null || messages.isEmpty()) return null;
            Object head = messages.get(0);
            if (!(head instanceof ChatHudLine)) return null;
            Text content = ((ChatHudLine) head).content();
            String plain = content == null ? null : content.getString();
            if (plain == null) return null;
            return new Object[]{ head, content, plain };
        });
        AccessorRegistry.bind(AccessPoint.CHAT_MERGE_REPEATED, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof Text) || !(a[1] instanceof Number)) return null;
            ChatHud chat = chat();
            java.util.List<Object> messages = messages(chat);
            if (messages == null || messages.size() < 2) return null;
            Object head = messages.get(0);
            if (!(head instanceof ChatHudLine)) return null;

            // Signature et indicateur REPRIS de la ligne remplacée : la ligne
            // fusionnée doit rester du même type aux yeux du jeu, sinon elle
            // change de nature en plus de changer de texte. Ce sont les deux
            // champs qui remplacent la {source, étiquette} de la 26.1.2.
            MessageSignatureData signature = ((ChatHudLine) head).signature();
            MessageIndicator indicator = ((ChatHudLine) head).indicator();
            messages.remove(0);
            messages.remove(0);

            MutableText combined = ((Text) a[0]).copy()
                .append(Text.literal(" (x" + ((Number) a[1]).intValue() + ")")
                    .withColor(CHAT_COUNTER_COLOR));
            // PUBLIQUES sur cette version, contrairement à la 26.1.2 : ni
            // Invoker ni accessor pour ces deux appels.
            //
            // reset() et NON refresh() : c'est reset() l'équivalent public de
            // rescaleChat(). La refresh() de Yarn est PRIVÉE — voir le piège
            // de nommage détaillé dans le stub ChatHud.
            chat.addMessage(combined, signature, indicator);
            chat.reset();
            return messages.isEmpty() ? null : messages.get(0);
        });

        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_ENCHANT_LEVEL, (r, a) -> {
            LivingEntity p = player(r);
            if (p == null || a.length < 1 || !(a[0] instanceof String)) return null;
            ItemStack stack = p.getStackInHand(Hand.MAIN_HAND);
            if (stack == null || stack.isEmpty()) return Integer.valueOf(0);
            // Receveur typé avec la classe DÉCLARANTE de get() (leçon v1076).
            ComponentsAccess components = stack;
            Object raw = components.get(DataComponentTypes.ENCHANTMENTS);
            if (!(raw instanceof ItemEnchantmentsComponent)) return Integer.valueOf(0);
            ItemEnchantmentsComponent enchantments = (ItemEnchantmentsComponent) raw;
            if (enchantments.isEmpty()) return Integer.valueOf(0);
            // getEnchantments() ici, keySet() en 26.1.2 ; et l'identifiant se
            // lit d'un appel (getIdAsString) au lieu de trois.
            for (Object entry : enchantments.getEnchantments()) {
                if (!(entry instanceof RegistryEntry)) continue;
                RegistryEntry holder = (RegistryEntry) entry;
                String id = registryId(holder);
                if (id == null) continue;
                int sep = id.indexOf(':');
                if (a[0].equals(sep < 0 ? id : id.substring(sep + 1))) {
                    return Integer.valueOf(enchantments.getLevel(holder));
                }
            }
            return Integer.valueOf(0);
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_FOOD, (r, a) -> {
            PlayerEntity p = player(r);
            if (p == null) return null;
            float[] main = edibleFood(p, p.getStackInHand(Hand.MAIN_HAND));
            return main != null ? main : edibleFood(p, p.getStackInHand(Hand.OFF_HAND));
        });

        // ── Fenêtre et ressources ──────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI_SIZE, (r, a) -> {
            MinecraftClient c = mc(r);
            Window w = c == null ? null : c.getWindow();
            return w == null ? null : new int[]{ w.getScaledWidth(), w.getScaledHeight() };
        });
        AccessorRegistry.bind(AccessPoint.RESOURCE_BYTES, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof String) || !(a[1] instanceof String)) return null;
            MinecraftClient c = mc(null);
            ResourceManager manager = c == null ? null : c.getResourceManager();
            if (manager == null) return null;
            try {
                // Receveur typé ResourceFactory, sa classe déclarante ; et
                // Identifier.of(ns, path) là où la 26.1.2 dit
                // fromNamespaceAndPath.
                ResourceFactory factory = manager;
                java.util.Optional res = factory.getResource(Identifier.of((String) a[0], (String) a[1]));
                if (res == null || !res.isPresent()) return null;
                Object resource = res.get();
                if (!(resource instanceof Resource)) return null;
                try (java.io.InputStream in = ((Resource) resource).getInputStream()) {
                    return readAll(in);
                }
            } catch (Throwable t) {
                LauncherLog.err("[AccessorBindings1211] RESOURCE_BYTES " + a[0] + ":" + a[1] + " : " + t);
                return null;
            }
        });

        // ── Types de composants d'item (champs STATIQUES publics) ──────────
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_FOOD, (r, a) -> DataComponentTypes.FOOD);
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_ENCHANTMENTS, (r, a) -> DataComponentTypes.ENCHANTMENTS);

        // ── Serveur courant (champs publics) ───────────────────────────────
        AccessorRegistry.bind(AccessPoint.SERVER_IP, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).address : null);
        AccessorRegistry.bind(AccessPoint.SERVER_NAME, (r, a) ->
            r instanceof ServerInfo ? ((ServerInfo) r).name : null);
    }

    /**
     * Poignée d'effet → identifiant de registre ({@code "minecraft:speed"}), ou
     * {@code null}.
     *
     * <p>Un appel là où la 26.1.2 en fait trois ({@code unwrapKey} → {@code get}
     * → {@code identifier}) : {@code getIdAsString()} n'existe que sur cette
     * ligne de versions. Le {@code Throwable} couvre l'entrée DIRECTE, sans clé
     * de registre, plutôt que de laisser remonter une erreur dans une liaison.
     * Il est journalisé UNE FOIS — cette méthode est appelée par effet et par
     * frame, un log par appel noierait le fichier.
     */
    private static boolean registryIdReported;

    private static String registryId(RegistryEntry holder) {
        if (holder == null) return null;
        try {
            return holder.getIdAsString();
        } catch (Throwable t) {
            if (!registryIdReported) {
                registryIdReported = true;
                LauncherLog.warn("[AccessorBindings1211] identifiant d'effet illisible : " + t);
            }
            return null;
        }
    }

    /**
     * Le chat vanilla, ou {@code null} s'il n'est pas encore là.
     *
     * <p>Toute la chaîne ({@code MinecraftClient} → {@code InGameHud} →
     * {@code ChatHud}) est faite ICI : c'est ce qui permet aux points d'accès
     * du chat de ne prendre aucun receveur.
     */
    private static ChatHud chat() {
        MinecraftClient c = mc(null);
        InGameHud hud = c == null ? null : c.inGameHud;
        return hud == null ? null : hud.getChatHud();
    }

    /**
     * L'historique du chat, ou {@code null} si l'accessor n'a pas été tissé.
     *
     * <p>Seul membre du chat qui en demande un — voir
     * {@code ChatHudAccessor1211}. Les éléments restent en {@link Object} :
     * c'est l'appelant, dans cette même unité, qui a le droit de les nommer.
     */
    private static java.util.List<Object> messages(ChatHud chat) {
        return chat instanceof ChatHudAccessor1211 ? ((ChatHudAccessor1211) chat).la$messages() : null;
    }

    /**
     * Pile du jeu → porteur neutre, {@link ItemInfo#EMPTY} pour une case vide.
     *
     * <p>Renommages de CETTE version : {@code isDamageable()} (et non
     * {@code isDamageableItem()}), {@code getDamage()} (et non
     * {@code getDamageValue()}) ; {@code getMaxDamage()} et {@code getCount()}
     * sont communs aux deux.
     */
    private static ItemInfo item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemInfo.EMPTY;
        boolean damageable = stack.isDamageable();
        return new ItemInfo(stack, false, stack.getCount(), damageable,
            damageable ? stack.getDamage() : 0,
            damageable ? stack.getMaxDamage() : 0);
    }

    /**
     * Chaîne → constante de point de vue de CETTE version, {@code null} si
     * inconnue (l'écriture est alors ignorée plutôt que devinée).
     */
    private static Perspective perspective(Object id) {
        if (!(id instanceof String)) return null;
        if ("third_person_back".equals(id)) return Perspective.THIRD_PERSON_BACK;
        if ("third_person_front".equals(id)) return Perspective.THIRD_PERSON_FRONT;
        if ("first_person".equals(id)) return Perspective.FIRST_PERSON;
        return null;
    }

    /**
     * Identifiant de registre → constante de son de CETTE version.
     *
     * <p>Même table explicite qu'en 26.1.2, avec un piège propre à Yarn : ses
     * champs portent la catégorie de registre en préfixe
     * ({@code BLOCK_AMETHYST_BLOCK_CHIME}) là où 26.1.2 l'omet
     * ({@code AMETHYST_BLOCK_CHIME}). Deviner l'un depuis l'autre ne marche
     * pas — chaque nom est vérifié dans les mappings.
     */
    private static SoundEvent soundById(Object id) {
        if (!(id instanceof String)) return null;
        if ("minecraft:block.amethyst_block.chime".equals(id)) return SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME;
        if ("minecraft:entity.experience_orb.pickup".equals(id)) return SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
        return null;
    }

    /**
     * Identifiant de registre → constante d'effet de CETTE version.
     *
     * <p>Même table explicite qu'en 26.1.2, et pour la même raison : on ne sert
     * que des effets dont le nom a été vérifié, un identifiant inconnu rend
     * {@code null} et le point d'accès répond « indisponible ».
     */
    private static RegistryEntry effectById(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof String)) return null;
        String id = (String) args[0];
        if ("minecraft:darkness".equals(id)) return StatusEffects.DARKNESS;
        return null;
    }

    /**
     * Receveur → joueur local. Receveur {@code null} = celui du client courant,
     * même convention que {@link #mc(Object)}.
     */
    private static ClientPlayerEntity player(Object receiver) {
        if (receiver instanceof ClientPlayerEntity) return (ClientPlayerEntity) receiver;
        MinecraftClient c = mc(null);
        return c == null ? null : c.player;
    }

    /**
     * Receveur → client. Receveur {@code null} = « prends l'instance courante »,
     * pour que les appelants neutres n'aient jamais à fabriquer le receveur.
     */
    private static MinecraftClient mc(Object receiver) {
        Object target = receiver != null ? receiver : client();
        return target instanceof MinecraftClient ? (MinecraftClient) target : null;
    }

    /**
     * Instance courante, ou {@code null}. Le {@code Throwable} n'est pas
     * décoratif : {@code getInstance()} peut être appelé avant que la classe ne
     * soit initialisable, et on y récolte alors une erreur de liaison.
     */
    private static MinecraftClient client() {
        try {
            return MinecraftClient.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Receveur → options, {@code null} = celles du client courant. */
    private static GameOptions options(Object receiver) {
        Object target = receiver;
        if (target == null) {
            MinecraftClient c = mc(null);
            target = c == null ? null : c.options;
        }
        return target instanceof GameOptions ? (GameOptions) target : null;
    }
}
