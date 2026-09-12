package com.yuyuframe.launcheragent.apimixin.v26_1.core;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.PlayerEffect;
import com.yuyuframe.launcheragent.base.log.LauncherLog;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Liaisons {@link AccessPoint} → accessors de la tranche 26.1.2 — le SEUL
 * endroit de la tranche qui nomme à la fois un {@link AccessPoint} neutre et
 * un accessor {@code *261}.
 *
 * <h2>Chargée par NOM, jamais référencée</h2>
 *
 * {@code AccessorRegistry.ensureInitialized()} la charge par
 * {@code Class.forName} quand la version détectée est 26.1.2, ce qui exécute
 * le {@code <clinit>} ci-dessous. Personne ne doit la nommer depuis du code
 * neutre : elle référence {@code net.minecraft.client.Minecraft} et consorts,
 * qui n'existent sous ces noms qu'à partir de la ligne 26.1 (MC non obfusqué)
 * — un import depuis {@code runtime/} ferait tomber toute autre tranche en
 * {@code NoClassDefFoundError}.
 *
 * <h2>Écrire la même chose pour une nouvelle version</h2>
 *
 * Copier ce fichier dans {@code apimixin/vXX_Y/core/}, remplacer les accessors
 * par ceux de la tranche, et ajouter la ligne correspondante dans
 * {@code AccessorRegistry.bindingsClassFor()}. Un accès que la tranche ne sait
 * pas servir se laisse simplement NON LIÉ : {@code AccessorRegistry} le
 * signale une fois dans le log et sert la valeur de repli, plutôt que de faire
 * échouer le chargement entier.
 *
 * <p>Chaque {@code instanceof} n'est pas décoratif : il vaut « ce mixin
 * s'est-il réellement tissé ? ». Un accessor non tissé laisse l'objet du jeu
 * sans notre interface, et le cast lèverait — le test le convertit en
 * « non disponible », que le registre traduit en valeur de repli.
 */
public final class AccessorBindings261 {

    private AccessorBindings261() {}

    /**
     * Gris du compteur de répétitions — voir {@link AccessPoint#CHAT_MERGE_REPEATED}.
     *
     * <p>Volontairement neutre : le compteur ne doit imiter la mise en forme
     * d'aucun serveur, seulement rester lisible par-dessus.
     */
    private static final int CHAT_COUNTER_COLOR = 0xAAAAAA;

    static {
        // ── Client ─────────────────────────────────────────────────────────
        // getInstance() est une méthode publique statique, pas un champ : pas
        // d'accessor, appel direct. Elle est quand même exposée ici pour que
        // les appelants neutres n'aient jamais à nommer Minecraft eux-mêmes.
        AccessorRegistry.bind(AccessPoint.CLIENT_INSTANCE, (r, a) -> client());
        AccessorRegistry.bind(AccessPoint.CLIENT_PLAYER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$player(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_LEVEL, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$level(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_OPTIONS, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$options(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_USER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$user(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SCREEN, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$screen(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_SET_SCREEN, (r, a) -> {
            Minecraft c = client();
            if (c == null || a.length < 1) return null;
            // a[0] peut être null (fermeture) ; setScreen(Screen) est la SEULE
            // surcharge à ce nom, aucune ambiguïté à lever.
            c.setScreen((net.minecraft.client.gui.screens.Screen) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$gui(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_WINDOW, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$window(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_MOUSE_HANDLER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$mouseHandler(); });
        AccessorRegistry.bind(AccessPoint.CLIENT_RESOURCE_MANAGER, (r, a) -> { MinecraftAccessor261 m = mc(r); return m == null ? null : m.la$resourceManager(); });
        // Champ STATIQUE : aucun receveur. L'accessor lève s'il n'est pas
        // tissé (plutôt que de renvoyer un 0 faux) — AccessorRegistry.invoke()
        // attrape, journalise une fois, et l'appelant reçoit son repli.
        AccessorRegistry.bind(AccessPoint.CLIENT_FPS, (r, a) -> MinecraftAccessor261.la$fps());
        AccessorRegistry.bind(AccessPoint.CLIENT_USERNAME, (r, a) -> {
            MinecraftAccessor261 m = mc(r);
            net.minecraft.client.User u = m == null ? null : m.la$user();
            return u == null ? null : u.getName();
        });

        // ── Options ────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTIONS_FOV, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$fov(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_SENSITIVITY, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$sensitivity(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_GAMMA, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : o.la$gamma(); });
        AccessorRegistry.bind(AccessPoint.OPTIONS_HIDE_GUI, (r, a) -> { OptionsAccessor261 o = options(r); return o == null ? null : Boolean.valueOf(o.la$hideGui()); });

        // ── Poignée d'option ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE, (r, a) ->
            r instanceof OptionInstanceAccessor261 ? ((OptionInstanceAccessor261) r).la$value() : null);
        AccessorRegistry.bind(AccessPoint.OPTION_VALUE_SET, (r, a) -> {
            if (!(r instanceof OptionInstanceAccessor261) || a.length < 1) return null;
            ((OptionInstanceAccessor261) r).la$setValue(a[0]);
            return null;
        });

        // ── Raccourci clavier ──────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.KEYBIND_IS_DOWN, (r, a) ->
            r instanceof KeyMappingAccessor261 ? Boolean.valueOf(((KeyMappingAccessor261) r).la$isDown()) : null);
        AccessorRegistry.bind(AccessPoint.KEYBIND_KEY_CODE, (r, a) -> {
            if (!(r instanceof KeyMappingAccessor261)) return null;
            // Champ PRIVÉ ici (accessor), puis getValue() — nommée getCode()
            // partout ailleurs, y compris en Yarn. La 1.21.11 n'a pas besoin
            // d'accessor du tout : elle a un chemin public équivalent.
            InputConstants.Key key = ((KeyMappingAccessor261) r).la$key();
            return key == null ? null : Integer.valueOf(key.getValue());
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE, (r, a) -> {
            Object target = options(r);
            if (!(target instanceof Options)) return null;
            CameraType view = ((Options) target).getCameraType();
            if (view == CameraType.THIRD_PERSON_BACK) return "third_person_back";
            if (view == CameraType.THIRD_PERSON_FRONT) return "third_person_front";
            return view == null ? null : "first_person";
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_PERSPECTIVE_SET, (r, a) -> {
            Object target = options(r);
            if (!(target instanceof Options) || a.length < 1) return null;
            CameraType view = cameraType(a[0]);
            if (view == null) return null;
            ((Options) target).setCameraType(view);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.OPTIONS_MOVEMENT_KEYS, (r, a) -> {
            // Les cinq champs sont PUBLICS sur cette version : pas d'accessor.
            // Le receveur résolu EST l'instance d'Options (l'interface
            // d'accessor est tissée dessus), d'où le transtypage direct.
            Object target = options(r);
            if (!(target instanceof Options)) return null;
            Options o = (Options) target;
            return new Object[]{ o.keyUp, o.keyLeft, o.keyDown, o.keyRight, o.keyJump };
        });

        // ── Joueur local : OPÉRATIONS ──────────────────────────────────────
        // Reprises TELLES QUELLES de ce que les modules faisaient en ligne sur
        // l'objet joueur : rien ne change ici pour la 26.1.2, l'appel a juste
        // déménagé du module vers la liaison — c'est ce qui rend le module
        // portable (voir PlayerData).
        AccessorRegistry.bind(AccessPoint.PLAYER_POSITION, (r, a) -> {
            LocalPlayer p = player(r);
            return p == null ? null : new double[]{ p.getX(), p.getY(), p.getZ() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_YAW, (r, a) -> { LocalPlayer p = player(r); return p == null ? null : Float.valueOf(p.getYRot()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_PITCH, (r, a) -> { LocalPlayer p = player(r); return p == null ? null : Float.valueOf(p.getXRot()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_EYE_HEIGHT, (r, a) -> { LocalPlayer p = player(r); return p == null ? null : Float.valueOf(p.getEyeHeight()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_ACTIVE_EFFECTS, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null) return null;
            java.util.List<PlayerEffect> out = new java.util.ArrayList<>();
            for (net.minecraft.world.effect.MobEffectInstance i : p.getActiveEffects()) {
                Holder<MobEffect> holder = i.getEffect();
                MobEffect effect = holder != null ? holder.value() : null;
                String name = "?";
                int color = 0xFFFFFF;
                boolean beneficial = false;
                if (effect != null) {
                    net.minecraft.network.chat.Component display = effect.getDisplayName();
                    if (display != null && display.getString() != null && !display.getString().isEmpty()) {
                        name = display.getString();
                    }
                    color = effect.getColor();
                    beneficial = effect.isBeneficial();
                }
                out.add(new PlayerEffect(name, i.getAmplifier(), i.getDuration(),
                    i.isInfiniteDuration(), color, beneficial, registryId(holder)));
            }
            return out;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_PING, (r, a) -> {
            LocalPlayer p = player(r);
            Minecraft client = client();
            if (p == null || client == null) return null;
            // getConnection() est PUBLIQUE : pas d'accessor à ajouter.
            net.minecraft.client.multiplayer.ClientPacketListener c = client.getConnection();
            if (c == null) return null;
            net.minecraft.client.multiplayer.PlayerInfo info = c.getPlayerInfo(p.getUUID());
            return info == null ? null : Integer.valueOf(info.getLatency());
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HAS_EFFECT, (r, a) -> {
            LocalPlayer p = player(r);
            Holder<MobEffect> effect = effectById(a);
            return p == null || effect == null ? null : Boolean.valueOf(p.hasEffect(effect));
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_REMOVE_EFFECT, (r, a) -> {
            LocalPlayer p = player(r);
            Holder<MobEffect> effect = effectById(a);
            if (p != null && effect != null) p.removeEffect(effect);
            return null;
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HEALTH, (r, a) -> { LocalPlayer p = player(r); return p == null ? null : Float.valueOf(p.getHealth()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_MAX_HEALTH, (r, a) -> { LocalPlayer p = player(r); return p == null ? null : Float.valueOf(p.getMaxHealth()); });
        AccessorRegistry.bind(AccessPoint.PLAYER_ATTACK_STRENGTH, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null || a.length < 1 || !(a[0] instanceof Number)) return null;
            return Float.valueOf(p.getAttackStrengthScale(((Number) a[0]).floatValue()));
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_EQUIPMENT, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null) return null;
            return new ItemInfo[]{
                item(p.getItemBySlot(EquipmentSlot.HEAD)),
                item(p.getItemBySlot(EquipmentSlot.CHEST)),
                item(p.getItemBySlot(EquipmentSlot.LEGS)),
                item(p.getItemBySlot(EquipmentSlot.FEET)),
                item(p.getItemInHand(InteractionHand.MAIN_HAND)),
                item(p.getItemInHand(InteractionHand.OFF_HAND)),
            };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MOVEMENT_FLAGS, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null) return null;
            return new boolean[]{ p.isPassenger(), p.isFallFlying(), p.isInWater(),
                p.isSprinting(), p.onGround(), p.isSwimming() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MODE_FLAGS, (r, a) -> {
            LocalPlayer p = player(r);
            return p == null ? null : new boolean[]{ p.isCreative(), p.isSpectator() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_FOOD, (r, a) -> {
            LocalPlayer p = player(r);
            Object food = p == null ? null : p.getFoodData();
            if (!(food instanceof FoodDataAccessor261)) return null;
            FoodDataAccessor261 f = (FoodDataAccessor261) food;
            return new float[]{ f.la$foodLevel(), f.la$saturationLevel(), f.la$exhaustionLevel() };
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_MAIN_ARM_RIGHT, (r, a) -> {
            LocalPlayer p = player(r);
            // "HumanoidArm" est le VRAI nom 26.1.2 de ce que Yarn appelle "Arm"
            // (confirmé par désassemblage de Gui.extractItemHotbar).
            return p == null ? null : Boolean.valueOf(p.getMainArm() == HumanoidArm.RIGHT);
        });

        // ── Sons d'interface ───────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.SOUND_PLAY_UI, (r, a) -> {
            if (a.length < 3 || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            SoundEvent sound = soundById(a[0]);
            Minecraft c = client();
            SoundManager manager = c == null ? null : c.getSoundManager();
            if (sound == null || manager == null) return null;
            manager.play(SimpleSoundInstance.forUI(sound,
                ((Number) a[1]).floatValue(), ((Number) a[2]).floatValue()));
            return Boolean.TRUE;
        });

        // ── Faim/saturation ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.FOOD_LEVEL, (r, a) ->
            r instanceof FoodDataAccessor261 ? Integer.valueOf(((FoodDataAccessor261) r).la$foodLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_SATURATION, (r, a) ->
            r instanceof FoodDataAccessor261 ? Float.valueOf(((FoodDataAccessor261) r).la$saturationLevel()) : null);
        AccessorRegistry.bind(AccessPoint.FOOD_EXHAUSTION, (r, a) ->
            r instanceof FoodDataAccessor261 ? Float.valueOf(((FoodDataAccessor261) r).la$exhaustionLevel()) : null);

        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_ENCHANT_LEVEL, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null || a.length < 1 || !(a[0] instanceof String)) return null;
            ItemStack stack = p.getItemInHand(InteractionHand.MAIN_HAND);
            if (stack == null || stack.isEmpty()) return Integer.valueOf(0);
            net.minecraft.world.item.enchantment.ItemEnchantments enchantments =
                stack.get(DataComponentsAccessor261.la$enchantments());
            if (enchantments == null || enchantments.isEmpty()) return Integer.valueOf(0);
            for (Object entry : enchantments.keySet()) {
                @SuppressWarnings("unchecked")
                Holder<net.minecraft.world.item.enchantment.Enchantment> holder =
                    (Holder<net.minecraft.world.item.enchantment.Enchantment>) entry;
                if (holder == null) continue;
                java.util.Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment>> key =
                    holder.unwrapKey();
                if (key == null || !key.isPresent()) continue;
                net.minecraft.resources.Identifier id = key.get().identifier();
                if (id != null && a[0].equals(id.getPath())) return Integer.valueOf(enchantments.getLevel(holder));
            }
            return Integer.valueOf(0);
        });
        AccessorRegistry.bind(AccessPoint.PLAYER_HELD_FOOD, (r, a) -> {
            LocalPlayer p = player(r);
            if (p == null) return null;
            float[] main = edibleFood(p, p.getItemInHand(InteractionHand.MAIN_HAND));
            return main != null ? main : edibleFood(p, p.getItemInHand(InteractionHand.OFF_HAND));
        });

        // ── Fenêtre et ressources ──────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CLIENT_GUI_SIZE, (r, a) -> {
            MinecraftAccessor261 m = mc(r);
            com.mojang.blaze3d.platform.Window w = m == null ? null : m.la$window();
            return w == null ? null : new int[]{ w.getGuiScaledWidth(), w.getGuiScaledHeight() };
        });
        AccessorRegistry.bind(AccessPoint.RESOURCE_BYTES, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof String) || !(a[1] instanceof String)) return null;
            MinecraftAccessor261 m = mc(null);
            net.minecraft.server.packs.resources.ReloadableResourceManager manager =
                m == null ? null : m.la$resourceManager();
            if (manager == null) return null;
            try {
                java.util.Optional<net.minecraft.server.packs.resources.Resource> res = manager.getResource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath((String) a[0], (String) a[1]));
                if (res == null || !res.isPresent()) return null;
                try (java.io.InputStream in = res.get().open()) {
                    return readAll(in);
                }
            } catch (Throwable t) {
                LauncherLog.err("[AccessorBindings261] RESOURCE_BYTES " + a[0] + ":" + a[1] + " : " + t);
                return null;
            }
        });

        // ── Types de composants d'item (champs STATIQUES) ──────────────────
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_FOOD, (r, a) -> DataComponentsAccessor261.la$food());
        AccessorRegistry.bind(AccessPoint.COMPONENT_TYPE_ENCHANTMENTS, (r, a) -> DataComponentsAccessor261.la$enchantments());

        // ── Serveur courant ────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.SERVER_IP, (r, a) ->
            r instanceof ServerDataAccessor261 ? ((ServerDataAccessor261) r).la$ip() : null);
        AccessorRegistry.bind(AccessPoint.SERVER_NAME, (r, a) ->
            r instanceof ServerDataAccessor261 ? ((ServerDataAccessor261) r).la$name() : null);

        // ── Monde ──────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.LEVEL_BIOME_ID, (r, a) -> {
            if (a.length < 3 || !(a[0] instanceof Number) || !(a[1] instanceof Number) || !(a[2] instanceof Number)) return null;
            MinecraftAccessor261 m = mc(null);
            net.minecraft.client.multiplayer.ClientLevel level = m == null ? null : m.la$level();
            if (level == null) return null;
            net.minecraft.world.level.biome.BiomeManager biomes = level.getBiomeManager();
            if (biomes == null) return null;
            Holder<net.minecraft.world.level.biome.Biome> holder = biomes.getBiome(
                new net.minecraft.core.BlockPos(((Number) a[0]).intValue(),
                    ((Number) a[1]).intValue(), ((Number) a[2]).intValue()));
            if (holder == null) return null;
            // unwrapKey()/identifier() sont les VRAIS noms 26.1.2 — getKey()/
            // getValue() n'existent pas, piège déjà payé au prix fort ici même.
            java.util.Optional<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> key = holder.unwrapKey();
            if (key == null || !key.isPresent()) return null;
            net.minecraft.resources.Identifier id = key.get().identifier();
            return id == null ? null : id.toString();
        });

        // ── Connexion au serveur ───────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.NETWORK_CONNECTION, (r, a) -> connection());
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_CHAT, (r, a) -> {
            ClientPacketListener c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            c.sendChat((String) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_SEND_COMMAND, (r, a) -> {
            ClientPacketListener c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            c.sendCommand((String) a[0]);
            return Boolean.TRUE;
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_SERVER_ADDRESS, (r, a) -> {
            ClientPacketListener c = connection();
            if (c == null) return null;
            ServerData server = c.getServerData();
            String ip = server instanceof ServerDataAccessor261 ? ((ServerDataAccessor261) server).la$ip() : null;
            // Les DEUX sources, brutes : l'entrée de liste de serveurs et
            // l'adresse distante de la connexion. Laquelle l'emporte et comment
            // la normaliser est de la POLITIQUE, elle reste chez l'appelant —
            // et {@code SocketAddress} est du java.net, donc neutre.
            //
            // La seconde n'est pas décorative : en « quick play », le jeu ne
            // construit pas d'entrée de liste et la première est nulle.
            net.minecraft.network.Connection raw = c.getConnection();
            java.net.SocketAddress remote = raw == null ? null : raw.getRemoteAddress();
            return new Object[]{ ip, remote };
        });
        AccessorRegistry.bind(AccessPoint.NETWORK_HAS_COMMAND, (r, a) -> {
            ClientPacketListener c = connection();
            if (c == null || a.length < 1 || !(a[0] instanceof String)) return null;
            com.mojang.brigadier.CommandDispatcher<?> commands = c.getCommands();
            if (commands == null || commands.getRoot() == null) return Boolean.FALSE;
            return Boolean.valueOf(commands.getRoot().getChild((String) a[0]) != null);
        });

        // ── Chat ───────────────────────────────────────────────────────────
        AccessorRegistry.bind(AccessPoint.CHAT_HEAD_MESSAGE, (r, a) -> {
            ChatComponent chat = chat();
            if (chat == null) return null;
            List<GuiMessage> messages = ((ChatComponentAccessor261) chat).la$allMessages();
            if (messages == null || messages.isEmpty()) return null;
            GuiMessage head = messages.get(0);
            Component content = head == null ? null : head.content();
            String plain = content == null ? null : content.getString();
            if (plain == null) return null;
            return new Object[]{ head, content, plain };
        });
        AccessorRegistry.bind(AccessPoint.CHAT_MERGE_REPEATED, (r, a) -> {
            if (a.length < 2 || !(a[0] instanceof Component) || !(a[1] instanceof Number)) return null;
            ChatComponent chat = chat();
            if (chat == null) return null;
            List<GuiMessage> messages = ((ChatComponentAccessor261) chat).la$allMessages();
            if (messages == null || messages.size() < 2) return null;

            // Source et étiquette REPRISES de la ligne remplacée : la ligne
            // fusionnée doit rester du même type aux yeux du jeu (filtrage,
            // indicateurs de signature), sinon elle change de nature en plus
            // de changer de texte.
            GuiMessage headLine = messages.get(0);
            GuiMessageSource source = headLine.source();
            GuiMessageTag tag = headLine.tag();
            messages.remove(0);
            messages.remove(0);

            // copy() et non une modification en place : le jeu garde peut-être
            // ce composant ailleurs. Compteur en gris pour rester lisible
            // par-dessus n'importe quelle mise en forme du serveur.
            Component combined = ((Component) a[0]).copy()
                .append(Component.literal(" (x" + ((Number) a[1]).intValue() + ")")
                    .withColor(CHAT_COUNTER_COLOR));
            ((ChatComponentAccessor261) chat).la$addMessage(combined, null, source, tag);
            chat.rescaleChat();
            return messages.isEmpty() ? null : messages.get(0);
        });

        // ── Brouillard (champ STATIQUE en écriture) ────────────────────────
        AccessorRegistry.bind(AccessPoint.FOG_SET_ENABLED, (r, a) -> {
            if (a.length < 1 || !(a[0] instanceof Boolean)) return null;
            FogRendererAccessor261.la$setFogEnabled((Boolean) a[0]);
            return null;
        });
    }

    /**
     * Identifiant de registre COMPLET d'un effet ({@code "minecraft:speed"}),
     * ou {@code null}.
     *
     * <p>{@code unwrapKey()} et {@code identifier()} sont les VRAIS noms
     * 26.1.2 — les noms « évidents » {@code getKey()}/{@code getValue()}
     * n'existent pas ici (piège déjà payé sur le biome de {@code CoordsModule}).
     *
     * <p>L'échec est journalisé UNE FOIS : appelée par effet et par frame,
     * cette méthode noierait le fichier de log à raison d'une ligne par appel.
     */
    private static boolean registryIdReported;

    private static String registryId(Holder<MobEffect> holder) {
        if (holder == null) return null;
        try {
            java.util.Optional<net.minecraft.resources.ResourceKey<MobEffect>> key = holder.unwrapKey();
            if (key == null || !key.isPresent()) return null;
            net.minecraft.resources.Identifier id = key.get().identifier();
            return id == null ? null : id.toString();
        } catch (Throwable t) {
            if (!registryIdReported) {
                registryIdReported = true;
                LauncherLog.warn("[AccessorBindings261] identifiant d'effet illisible : " + t);
            }
            return null;
        }
    }

    /**
     * Le chat vanilla, ou {@code null} s'il n'est pas encore là.
     *
     * <p>Toute la chaîne ({@code Minecraft} → {@code Gui} → {@code ChatComponent})
     * est faite ICI et non chez l'appelant : c'est ce qui permet aux deux points
     * d'accès du chat de ne prendre aucun receveur, et donc à l'appelant de ne
     * nommer aucun de ces trois types.
     */
    private static ChatComponent chat() {
        MinecraftAccessor261 mc = mc(null);
        Gui gui = mc == null ? null : mc.la$gui();
        ChatComponent chat = gui == null ? null : gui.getChat();
        return chat instanceof ChatComponentAccessor261 ? chat : null;
    }

    /**
     * Pile du jeu → porteur neutre, {@link ItemInfo#EMPTY} pour une case vide.
     *
     * <p>Renommages 26.1.2 à connaître pour un portage :
     * {@code isDamageable}→{@code isDamageableItem},
     * {@code getDamage}→{@code getDamageValue} ({@code getMaxDamage} et
     * {@code getCount} inchangés).
     */
    private static ItemInfo item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemInfo.EMPTY;
        boolean damageable = stack.isDamageableItem();
        return new ItemInfo(stack, false, stack.getCount(), damageable,
            damageable ? stack.getDamageValue() : 0,
            damageable ? stack.getMaxDamage() : 0);
    }

    /**
     * Aliment de cette pile s'il est RÉELLEMENT consommable maintenant, en
     * {@code {faim, saturation}} — sinon {@code null}.
     *
     * <p>{@code canEat(canAlwaysEat)} est la garde qu'utilise vanilla lui-même :
     * faux barre pleine, sauf aliment marqué {@code canAlwaysEat}.
     */
    private static float[] edibleFood(LocalPlayer player, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        net.minecraft.world.food.FoodProperties food = stack.get(DataComponentsAccessor261.la$food());
        if (food == null) return null;
        return player.canEat(food.canAlwaysEat())
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
    private static ClientPacketListener connection() {
        Minecraft mc = client();
        return mc == null ? null : mc.getConnection();
    }

    /**
     * Chaîne → constante de point de vue de CETTE version, {@code null} si
     * inconnue (l'écriture est alors ignorée plutôt que devinée).
     */
    private static CameraType cameraType(Object id) {
        if (!(id instanceof String)) return null;
        if ("third_person_back".equals(id)) return CameraType.THIRD_PERSON_BACK;
        if ("third_person_front".equals(id)) return CameraType.THIRD_PERSON_FRONT;
        if ("first_person".equals(id)) return CameraType.FIRST_PERSON;
        return null;
    }

    /**
     * Identifiant de registre → constante de son de CETTE version.
     *
     * <p>Même table explicite que {@link #effectById} : on ne sert que des sons
     * dont le nom a été vérifié, et ajouter un son demande une ligne des DEUX
     * côtés. {@code SoundEvents} range d'ailleurs une partie de ses entrées en
     * {@code Holder$Reference} plutôt qu'en {@code SoundEvent} — raison de plus
     * pour vérifier chacune plutôt que de les résoudre à l'aveugle.
     */
    private static SoundEvent soundById(Object id) {
        if (!(id instanceof String)) return null;
        if ("minecraft:block.amethyst_block.chime".equals(id)) return SoundEvents.AMETHYST_BLOCK_CHIME;
        if ("minecraft:entity.experience_orb.pickup".equals(id)) return SoundEvents.EXPERIENCE_ORB_PICKUP;
        return null;
    }

    /**
     * Identifiant de registre → constante d'effet de CETTE version.
     *
     * <p>Table explicite plutôt que recherche dans le registre : on ne sert que
     * des effets dont le nom a été vérifié ici, et un identifiant inconnu rend
     * {@code null} — le point d'accès répond alors « indisponible » au lieu de
     * deviner. Ajouter un effet = une ligne ici ET dans la liaison de l'autre
     * version.
     */
    private static Holder<MobEffect> effectById(Object[] args) {
        if (args.length < 1 || !(args[0] instanceof String)) return null;
        String id = (String) args[0];
        if ("minecraft:darkness".equals(id)) return MobEffects.DARKNESS;
        return null;
    }

    /**
     * Receveur → joueur local. Receveur {@code null} = celui du client courant,
     * même convention que {@link #mc(Object)} : aucun appelant neutre n'a ainsi
     * à fabriquer le receveur, ce qui l'obligerait à nommer {@code LocalPlayer}.
     */
    private static LocalPlayer player(Object receiver) {
        if (receiver instanceof LocalPlayer) return (LocalPlayer) receiver;
        MinecraftAccessor261 client = mc(null);
        return client == null ? null : client.la$player();
    }

    /**
     * Receveur → accessor {@code Minecraft}. Receveur {@code null} = « prends
     * l'instance courante » : les appelants neutres n'ont ainsi jamais à
     * fabriquer le receveur eux-mêmes, ce qui les obligerait à nommer
     * {@code Minecraft}.
     */
    private static MinecraftAccessor261 mc(Object receiver) {
        Object target = receiver != null ? receiver : client();
        return target instanceof MinecraftAccessor261 ? (MinecraftAccessor261) target : null;
    }

    /**
     * Instance courante du client, ou {@code null}. Le {@code Throwable} n'est
     * pas décoratif : {@code Minecraft.getInstance()} peut être appelé avant
     * que la classe ne soit initialisable, et on y récolte alors une erreur de
     * liaison, pas une exception ordinaire.
     */
    private static Minecraft client() {
        try {
            return Minecraft.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Receveur → accessor {@code Options}. Receveur {@code null} = les options
     * du client courant, même raison que {@link #mc(Object)}.
     */
    private static OptionsAccessor261 options(Object receiver) {
        Object target = receiver;
        if (target == null) {
            MinecraftAccessor261 client = mc(null);
            target = client == null ? null : client.la$options();
        }
        return target instanceof OptionsAccessor261 ? (OptionsAccessor261) target : null;
    }
}
