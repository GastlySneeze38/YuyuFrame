package com.yuyuframe.launcheragent.runtime.combat;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import com.yuyuframe.launcheragent.apimixin.data.ItemCooldown;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.data.ItemUse;
import com.yuyuframe.launcheragent.apimixin.data.PlayerEffect;
import com.yuyuframe.launcheragent.apimixin.data.SoundPlayed;
import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.runtime.game.NetworkData;
import com.yuyuframe.launcheragent.runtime.game.PlayerData;

import java.net.InetSocketAddress;
import java.util.UUID;

/**
 * Relève ce qui se passe en combat et le transmet au noyau natif
 * ({@link NativeCombat}). C'est tout : cette classe ne retient rien, ne juge
 * rien, n'écrit rien. Savoir si l'on est en combat, ce qui vaut d'être gardé
 * et où le journal s'écrit se décide côté Rust (docs/scoring/).
 *
 * <p>Deux flux :
 * <ul>
 *   <li>à chaque tick du jeu, l'état de notre joueur et celui des joueurs
 *       proches ; une fois par seconde, qui ils sont et ce qu'ils portent ;</li>
 *   <li>les événements, au moment où le jeu les produit : coup porté, paquet
 *       de dégâts, événement d'entité, objet utilisé.</li>
 * </ul>
 *
 * <p>Tout se passe sur le fil du jeu. Éteint ({@link #setActive}), plus rien
 * n'est lu ni envoyé.
 */
public final class CombatTracker {

    private CombatTracker() {
    }

    /**
     * Au-delà, un joueur n'est pas transmis. Très large exprès : c'est le
     * noyau qui décide de la distance d'engagement, et il tient un adversaire
     * qui n'est plus transmis pour parti (match nul). Un adversaire qui
     * s'éloigne dans l'arène — perle, élytres — doit donc rester transmis.
     */
    private static final double RANGE = 128.0;
    /** Identité et équipement : une fois par seconde suffit, le noyau n'écrit que les changements. */
    private static final int IDENTITY_EVERY_TICKS = 20;
    /**
     * Sauf pour les joueurs proches, redits quatre fois par seconde : le
     * noyau lit la fin d'un duel dans l'équipement de l'adversaire, qui peut
     * disparaître dans la seconde qui suit.
     */
    private static final int CLOSE_EVERY_TICKS = 5;
    private static final double CLOSE_RANGE = 24.0;

    /**
     * Les enchantements relevés sur chaque objet porté — ceux qui entrent
     * dans les dégâts d'un coup de masse ou dans leur réduction. La liste
     * dit ce qu'on RELÈVE ; ce qu'on en fait est l'affaire du noyau.
     */
    private static final String[] ENCHANTS = {
        "density", "breach", "wind_burst", "protection", "blast_protection", "projectile_protection", "feather_falling",
    };

    /**
     * « Pas d'identifiant ». Pas −1 : un identifiant réseau peut être négatif
     * — les personnages du lobby de Minemen en portent (−1234567…), et le
     * noyau a besoin de savoir qui ils sont pour les écarter.
     */
    private static final int NO_ID = Integer.MIN_VALUE;

    private static boolean active;
    /** Le contexte du monde courant a-t-il été envoyé ? Remis à faux à chaque extinction. */
    private static boolean contextSent;
    private static int ticks;
    private static boolean errorLogged;

    private static final double[] ME = new double[NativeCombat.ME_LEN];
    private static final double[] OTHERS = new double[NativeCombat.MAX_OTHERS * NativeCombat.OTHER_LEN];
    private static final Object[] NEAR = new Object[NativeCombat.MAX_OTHERS];

    /**
     * Dernière position vue de chaque autre joueur — x, y, z et le tick —, pour
     * calculer sa vitesse : celle que le client tient pour un autre joueur est
     * souvent nulle (ABI 22, docs/scoring/refonte/donnees-a-capturer.md).
     */
    private static final java.util.HashMap<Integer, double[]> LAST_POS = new java.util.HashMap<>();
    /** Au-delà, une position vue est trop vieille pour une vitesse. */
    private static final int VELOCITY_GAP_TICKS = 3;

    /** Les raccourcis lus pour {@link NativeCombat#ME_INPUT}, résolus une fois. */
    private static Object[] movementKeys;
    private static Object[] actionKeys;

    /** L'inventaire envoyé en dernier, et sa signature (piles et tailles), pour n'envoyer qu'un changement. */
    private static String lastInventory;
    private static long lastInventorySignature;

    /** Branche les points d'accroche. Une seule fois, à la construction du module. */
    public static void install() {
        VanillaHookRegistry.register(HookPoint.CLIENT_TICK, ctx -> { guarded(CombatTracker::tick); return false; });
        VanillaHookRegistry.register(HookPoint.SERVER_CONNECT, ctx -> { guarded(CombatTracker::sendContext); return false; });
        VanillaHookRegistry.register(HookPoint.CLIENT_LEVEL_LOAD, ctx -> {
            // Le noyau ferme le duel en cours à tout changement de monde.
            if (active) NativeCombat.world();
            // Les identifiants réseau ne valent plus rien ; l'inventaire est
            // redit au noyau, qui l'a oublié avec le monde.
            LAST_POS.clear();
            lastInventory = null;
            lastInventorySignature = 0;
            return false;
        });
        VanillaHookRegistry.register(HookPoint.ENTITY_ATTACK, ctx -> {
            // L'objet en main et la case AU MOMENT du coup : un attribute
            // swap (changer d'objet et frapper dans le même tick) ne se lit
            // qu'ici — le relevé du tick, lui, a vu l'objet d'avant.
            if (active) guarded(() -> NativeCombat.event(NativeCombat.EVENT_ATTACK,
                AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, ctx, NO_ID),
                AccessorRegistry.getInt(AccessPoint.PLAYER_SELECTED_SLOT, null, -1), 0, heldItem()));
            return false;
        });
        VanillaHookRegistry.register(HookPoint.SOUND_PLAYED, ctx -> {
            if (active && ctx instanceof SoundPlayed) guarded(() -> combatSound((SoundPlayed) ctx));
            return false;
        });
        VanillaHookRegistry.register(HookPoint.ITEM_COOLDOWN, ctx -> {
            if (active && ctx instanceof ItemCooldown) {
                ItemCooldown c = (ItemCooldown) ctx;
                NativeCombat.event(NativeCombat.EVENT_COOLDOWN, c.ticks, 0, 0, c.group);
            }
            return false;
        });
        VanillaHookRegistry.register(HookPoint.DAMAGE_EVENT, ctx -> {
            if (active && ctx instanceof DamageEvent) {
                DamageEvent d = (DamageEvent) ctx;
                NativeCombat.event(NativeCombat.EVENT_DAMAGE, d.entityId, d.causeId, d.directId, d.type);
            }
            return false;
        });
        VanillaHookRegistry.register(HookPoint.ENTITY_EVENT, ctx -> {
            if (active && ctx instanceof EntityEvent) {
                EntityEvent e = (EntityEvent) ctx;
                NativeCombat.event(NativeCombat.EVENT_ENTITY, e.entityId, e.status, 0, null);
            }
            return false;
        });
        VanillaHookRegistry.register(HookPoint.ITEM_USE, ctx -> {
            if (active && ctx instanceof ItemUse) {
                ItemUse u = (ItemUse) ctx;
                NativeCombat.event(NativeCombat.EVENT_ITEM_USE, u.mainHand ? 1 : 0, 0, 0, u.item);
            }
            return false;
        });
    }

    /** Allume ou éteint le suivi. Éteindre termine le journal en cours. */
    public static void setActive(boolean on) {
        if (active == on) {
            return;
        }
        active = on;
        contextSent = false;
        LAST_POS.clear();
        lastInventory = null;
        lastInventorySignature = 0;
        if (!on) {
            NativeCombat.close();
        }
    }

    /** Un duel est-il ouvert ? Le noyau les ouvre et les ferme, lui seul le sait. */
    public static boolean recording() {
        return active && NativeCombat.recording();
    }


    /** Une lecture qui lève ne doit ni casser le tick du jeu, ni remplir le journal de l'agent. */
    private static void guarded(Runnable step) {
        if (!active) {
            return;
        }
        try {
            step.run();
        } catch (Throwable error) {
            if (!errorLogged) {
                errorLogged = true;
                LauncherLog.err("[COMBAT] Relevé du combat en échec", error);
            }
        }
    }

    /** Où le journal s'écrit, et de quel serveur il parle. */
    private static void sendContext() {
        NativeCombat.context(System.getProperty("user.dir", ""), serverAddress(), System.getProperty("launcheragent.mcVersion", ""));
        contextSent = true;
    }

    /** L'adresse du serveur telle que le joueur l'a tapée, sinon celle de la connexion ; {@code null} en solo. */
    private static String serverAddress() {
        Object[] sources = NetworkData.serverAddressSources();
        if (sources == null) {
            return null;
        }
        if (sources[0] instanceof String && !((String) sources[0]).isEmpty()) {
            return (String) sources[0];
        }
        if (sources[1] instanceof InetSocketAddress) {
            InetSocketAddress remote = (InetSocketAddress) sources[1];
            return remote.getHostString() + ":" + remote.getPort();
        }
        return null;
    }

    private static void tick() {
        Object self = AccessorRegistry.get(AccessPoint.CLIENT_PLAYER, null);
        if (self == null) {
            return;
        }
        // Module allumé en cours de partie : aucun événement de connexion ne viendra.
        if (!contextSent) {
            sendContext();
        }
        int selfId = AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, self, NO_ID);
        double[] at = vector(AccessPoint.ENTITY_POSITION, self);
        if (selfId == NO_ID || at == null) {
            return;
        }
        fillSelf(self, selfId, at);
        int count = fillOthers(self, at);
        NativeCombat.tick(ME, OTHERS, count);
        sendInventory();
        // Les joueurs qu'on ne voit plus : leur dernière position ne sert plus.
        if (ticks % IDENTITY_EVERY_TICKS == 0) {
            LAST_POS.values().removeIf(p -> ticks - p[3] > IDENTITY_EVERY_TICKS);
        }

        boolean everyone = ticks % IDENTITY_EVERY_TICKS == 0;
        boolean closeIdentity = ticks % CLOSE_EVERY_TICKS == 0;
        ticks++;
        // L'équipement de notre joueur et des joueurs proches est relu À
        // CHAQUE TICK et transmis seulement s'il a changé : un totem remis,
        // un élytre échangé contre un plastron, un objet sorti au dernier
        // moment se lisent au tick près (docs/scoring/familles/ressources.md,
        // bug-use.md). Les autres, une fois par seconde.
        describe(self, selfId, closeIdentity, true);
        for (int i = 0; i < count; i++) {
            boolean near = DIST_SQ[i] <= CLOSE_RANGE * CLOSE_RANGE;
            if (near || everyone) {
                describe(NEAR[i], (int) OTHERS[i * NativeCombat.OTHER_LEN], everyone || (near && closeIdentity), true);
            }
        }
        java.util.Arrays.fill(NEAR, 0, count, null);
    }

    private static void fillSelf(Object self, int selfId, double[] at) {
        double[] speed = vector(AccessPoint.ENTITY_DELTA_MOVEMENT, self);
        ME[NativeCombat.ME_ID] = selfId;
        ME[NativeCombat.ME_X] = at[0];
        ME[NativeCombat.ME_Y] = at[1];
        ME[NativeCombat.ME_Z] = at[2];
        ME[NativeCombat.ME_VX] = speed == null ? 0 : speed[0];
        ME[NativeCombat.ME_VY] = speed == null ? 0 : speed[1];
        ME[NativeCombat.ME_VZ] = speed == null ? 0 : speed[2];
        ME[NativeCombat.ME_YAW] = AccessorRegistry.getFloat(AccessPoint.PLAYER_YAW, null, 0f);
        ME[NativeCombat.ME_PITCH] = AccessorRegistry.getFloat(AccessPoint.PLAYER_PITCH, null, 0f);
        ME[NativeCombat.ME_HEALTH] = PlayerData.health();
        ME[NativeCombat.ME_MAX_HEALTH] = PlayerData.maxHealth();
        ME[NativeCombat.ME_FALL] = AccessorRegistry.getDouble(AccessPoint.PLAYER_FALL_DISTANCE, null, 0);
        ME[NativeCombat.ME_ATTACK] = PlayerData.attackStrengthScale(0f, -1f);
        ME[NativeCombat.ME_FLAGS] = selfFlags(self);
        ME[NativeCombat.ME_PING] = AccessorRegistry.getInt(AccessPoint.PLAYER_PING, null, -1);
        float[] food = PlayerData.food();
        ME[NativeCombat.ME_FOOD] = food == null ? -1 : food[0];
        ME[NativeCombat.ME_SATURATION] = food == null ? -1 : food[1];
        ME[NativeCombat.ME_SLOT] = AccessorRegistry.getInt(AccessPoint.PLAYER_SELECTED_SLOT, null, -1);
        ME[NativeCombat.ME_GROUND] = groundHeight(at);
        int strength = 0, speedLevel = 0;
        for (PlayerEffect effect : PlayerData.activeEffects()) {
            String path = effect.registryPath();
            if ("strength".equals(path)) strength = effect.amplifier + 1;
            else if ("speed".equals(path)) speedLevel = effect.amplifier + 1;
        }
        ME[NativeCombat.ME_STRENGTH] = strength;
        ME[NativeCombat.ME_SPEED] = speedLevel;
        // ABI 22 — données de la refonte de la note, −1 si la version ne sait pas les lire.
        ME[NativeCombat.ME_INPUT] = inputBits();
        Object target = AccessorRegistry.get(AccessPoint.CLIENT_CROSSHAIR_ENTITY, null);
        ME[NativeCombat.ME_TARGET] = target == null ? -1 : AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, target, -1);
        ME[NativeCombat.ME_SWING] = AccessorRegistry.getFloat(AccessPoint.ENTITY_SWING_PROGRESS, self, -1f);
        ME[NativeCombat.ME_ABSORPTION] = AccessorRegistry.getFloat(AccessPoint.ENTITY_ABSORPTION, self, -1f);
    }

    /**
     * Les touches tenues, bits {@code NativeCombat.INPUT_*}, lues sur les
     * raccourcis (l'état réel de la touche, même quand un module substitue ce
     * que voit la logique de déplacement). −1 si la version ne les donne pas.
     */
    private static double inputBits() {
        if (movementKeys == null) {
            Object keys = AccessorRegistry.get(AccessPoint.OPTIONS_MOVEMENT_KEYS, null);
            if (keys instanceof Object[] && ((Object[]) keys).length >= 5) movementKeys = (Object[]) keys;
        }
        if (actionKeys == null) {
            Object keys = AccessorRegistry.get(AccessPoint.OPTIONS_ACTION_KEYS, null);
            if (keys instanceof Object[] && ((Object[]) keys).length >= 4) actionKeys = (Object[]) keys;
        }
        if (movementKeys == null || actionKeys == null) {
            return -1;
        }
        // OPTIONS_MOVEMENT_KEYS : avant, gauche, arrière, droite, saut.
        // OPTIONS_ACTION_KEYS : attaquer, utiliser, s'accroupir, sprinter.
        int bits = 0;
        if (down(movementKeys[0])) bits |= NativeCombat.INPUT_FORWARD;
        if (down(movementKeys[1])) bits |= NativeCombat.INPUT_LEFT;
        if (down(movementKeys[2])) bits |= NativeCombat.INPUT_BACK;
        if (down(movementKeys[3])) bits |= NativeCombat.INPUT_RIGHT;
        if (down(movementKeys[4])) bits |= NativeCombat.INPUT_JUMP;
        if (down(actionKeys[0])) bits |= NativeCombat.INPUT_ATTACK;
        if (down(actionKeys[1])) bits |= NativeCombat.INPUT_USE;
        if (down(actionKeys[2])) bits |= NativeCombat.INPUT_SNEAK;
        if (down(actionKeys[3])) bits |= NativeCombat.INPUT_SPRINT;
        return bits;
    }

    private static boolean down(Object key) {
        return key != null && AccessorRegistry.getBoolean(AccessPoint.KEYBIND_IS_DOWN, key, false);
    }

    /**
     * L'inventaire de notre joueur, seulement s'il a changé
     * ({@link NativeCombat#EVENT_INVENTORY}). Les identifiants ne sont relus
     * que si une pile ou une taille a bougé : d'un tick à l'autre, la
     * signature suffit.
     */
    private static void sendInventory() {
        Object items = AccessorRegistry.get(AccessPoint.PLAYER_INVENTORY_ITEMS, null);
        if (!(items instanceof ItemInfo[])) {
            return;
        }
        ItemInfo[] slots = (ItemInfo[]) items;
        long signature = slots.length;
        for (ItemInfo slot : slots) {
            signature = signature * 31 + (slot == null || slot.empty ? 0 : System.identityHashCode(slot.handle) * 64L + slot.count);
        }
        if (lastInventory != null && signature == lastInventorySignature) {
            return;
        }
        lastInventorySignature = signature;
        StringBuilder bar = new StringBuilder();
        java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
        for (int i = 0; i < slots.length; i++) {
            String id = itemId(slots[i]);
            if (i < 9) {
                if (i > 0) bar.append(',');
                if (id != null) bar.append(id);
            }
            if (id != null) counts.merge(id, slots[i].count, Integer::sum);
        }
        StringBuilder text = bar.append(';');
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) text.append(',');
            text.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        String inventory = text.toString();
        if (!inventory.equals(lastInventory)) {
            lastInventory = inventory;
            NativeCombat.event(NativeCombat.EVENT_INVENTORY, 0, 0, 0, inventory);
        }
    }

    /** L'identifiant d'une pile, sans espace de noms ; {@code null} pour une case vide. */
    private static String itemId(ItemInfo slot) {
        if (slot == null || slot.empty || slot.handle == null) {
            return null;
        }
        Object id = AccessorRegistry.get(AccessPoint.ITEM_ID, slot.handle);
        return id instanceof String ? (String) id : null;
    }

    /**
     * La hauteur des pieds au-dessus du premier bloc solide en dessous, en
     * blocs ; −1 sans sol à {@link #GROUND_SCAN} blocs. On lit le terrain : la
     * plupart des arènes sont en relief, la hauteur depuis le dernier contact
     * avec le sol y serait fausse (docs/scoring/familles/defense.md).
     */
    private static double groundHeight(double[] pos) {
        int x = (int) Math.floor(pos[0]);
        int z = (int) Math.floor(pos[2]);
        int start = (int) Math.floor(pos[1] - 1e-3);
        for (int dy = 0; dy < GROUND_SCAN; dy++) {
            int y = start - dy;
            Object solid = AccessorRegistry.invoke(AccessPoint.LEVEL_SOLID_BLOCK, null, x, y, z);
            if (solid == null) {
                return -1; // version sans lecture du terrain, ou pas de monde
            }
            if (Boolean.TRUE.equals(solid)) {
                return Math.max(0, pos[1] - (y + 1));
            }
        }
        return -1;
    }

    /** Profondeur de la lecture du terrain sous un joueur. */
    private static final int GROUND_SCAN = 48;

    /** L'objet en main de notre joueur, maintenant ; {@code null} main vide. */
    private static String heldItem() {
        Object equipment = AccessorRegistry.get(AccessPoint.ENTITY_EQUIPMENT, null);
        if (!(equipment instanceof ItemInfo[]) || ((ItemInfo[]) equipment).length < 5) {
            return null;
        }
        ItemInfo hand = ((ItemInfo[]) equipment)[4];
        if (hand == null || hand.empty || hand.handle == null) {
            return null;
        }
        Object id = AccessorRegistry.get(AccessPoint.ITEM_ID, hand.handle);
        return id instanceof String ? (String) id : null;
    }

    /**
     * Les sons du combat, rattachés au joueur sur qui ils sont joués : le plus
     * proche de leur position, à moins de 2 blocs. Le son du bouclier —
     * blocage ({@code item.shield.block}) ou bouclier désactivé
     * ({@code item.shield.break}) — est joué aux pieds de celui qui bloque ;
     * celui du coup critique ({@code entity.player.attack.crit}) aux pieds de
     * celui qui frappe ({@code Player.attackVisualEffects}, jar 26.3) : c'est
     * la seule trace du critique de l'adversaire. Depuis le 2026-10-22 (mode
     * épée), tous les sons d'un coup ({@code entity.player.attack.*} :
     * {@code knockback} pour un coup sprinté, {@code strong} / {@code weak}
     * selon la charge, {@code sweep}, {@code nodamage}) : les seules traces de
     * la charge et du sprint de l'adversaire. Les autres sons ne sont pas
     * transmis.
     */
    private static void combatSound(SoundPlayed sound) {
        if (sound.sound == null || !(sound.sound.startsWith("item.shield") || sound.sound.startsWith(ATTACK_SOUNDS))) {
            return;
        }
        Object players = AccessorRegistry.get(AccessPoint.LEVEL_PLAYERS, null);
        if (!(players instanceof Object[])) {
            return;
        }
        int nearest = NO_ID;
        double best = SOUND_MATCH * SOUND_MATCH;
        for (Object player : (Object[]) players) {
            double[] pos = vector(AccessPoint.ENTITY_POSITION, player);
            if (pos == null) {
                continue;
            }
            double dx = pos[0] - sound.x, dy = pos[1] - sound.y, dz = pos[2] - sound.z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= best) {
                best = distSq;
                nearest = AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, player, NO_ID);
            }
        }
        NativeCombat.event(NativeCombat.EVENT_SOUND, nearest, 0, 0, sound.sound);
    }

    /** Distance au-delà de laquelle un son n'est rattaché à aucun joueur. */
    private static final double SOUND_MATCH = 2.0;
    /** Les sons d'un coup ({@code SoundEvents.PLAYER_ATTACK_*}), critique compris. */
    private static final String ATTACK_SOUNDS = "entity.player.attack.";

    private static int selfFlags(Object self) {
        boolean[] move = PlayerData.movementFlags();
        boolean[] mode = PlayerData.modeFlags();
        int flags = 0;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_BLOCKING, self, false)) flags |= NativeCombat.FLAG_BLOCKING;
        if (move[PlayerData.FLAG_ON_GROUND]) flags |= NativeCombat.FLAG_ON_GROUND;
        if (move[PlayerData.FLAG_SPRINTING]) flags |= NativeCombat.FLAG_SPRINTING;
        if (move[PlayerData.FLAG_GLIDING]) flags |= NativeCombat.FLAG_GLIDING;
        if (move[PlayerData.FLAG_IN_WATER]) flags |= NativeCombat.FLAG_IN_WATER;
        if (move[PlayerData.FLAG_PASSENGER]) flags |= NativeCombat.FLAG_PASSENGER;
        if (PlayerData.itemUse() != null) flags |= NativeCombat.FLAG_USING_ITEM;
        if (mode[PlayerData.MODE_SPECTATOR]) flags |= NativeCombat.FLAG_SPECTATOR;
        if (mode[PlayerData.MODE_CREATIVE]) flags |= NativeCombat.FLAG_CREATIVE;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_SNEAKING, self, false)) flags |= NativeCombat.FLAG_SNEAKING;
        return flags;
    }

    /**
     * Les joueurs à portée, dans {@link #OTHERS} et {@link #NEAR}. Au-delà de
     * {@link NativeCombat#MAX_OTHERS}, les plus proches remplacent les plus
     * lointains : dans une foule, ce sont eux qui comptent.
     */
    private static int fillOthers(Object self, double[] at) {
        Object players = AccessorRegistry.get(AccessPoint.LEVEL_PLAYERS, null);
        if (!(players instanceof Object[])) {
            return 0;
        }
        int count = 0;
        int farthest = -1;
        double farthestSq = -1;
        for (Object other : (Object[]) players) {
            if (other == null || other == self) {
                continue;
            }
            double[] pos = vector(AccessPoint.ENTITY_POSITION, other);
            if (pos == null) {
                continue;
            }
            double dx = pos[0] - at[0], dy = pos[1] - at[1], dz = pos[2] - at[2];
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > RANGE * RANGE) {
                continue;
            }
            int slot;
            if (count < NativeCombat.MAX_OTHERS) {
                slot = count++;
            } else if (distSq < farthestSq) {
                slot = farthest;
            } else {
                continue;
            }
            writeOther(slot, other, pos, distSq);
            // Le plus lointain de ceux qu'on garde, pour savoir qui céder au prochain.
            farthest = -1;
            farthestSq = -1;
            for (int i = 0; i < count; i++) {
                if (DIST_SQ[i] > farthestSq) {
                    farthestSq = DIST_SQ[i];
                    farthest = i;
                }
            }
        }
        return count;
    }

    private static final double[] DIST_SQ = new double[NativeCombat.MAX_OTHERS];

    private static void writeOther(int slot, Object other, double[] pos, double distSq) {
        int base = slot * NativeCombat.OTHER_LEN;
        int id = AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, other, NO_ID);
        OTHERS[base] = id;
        OTHERS[base + 1] = pos[0];
        OTHERS[base + 2] = pos[1];
        OTHERS[base + 3] = pos[2];
        // La vitesse depuis deux positions successives (ABI 22) : celle que le
        // client tient pour un autre joueur est souvent nulle. Sans position
        // récente, celle du client, faute de mieux.
        double[] last = LAST_POS.get(id);
        if (last != null && ticks > last[3] && ticks - last[3] <= VELOCITY_GAP_TICKS) {
            double dt = ticks - last[3];
            OTHERS[base + 4] = (pos[0] - last[0]) / dt;
            OTHERS[base + 5] = (pos[1] - last[1]) / dt;
            OTHERS[base + 6] = (pos[2] - last[2]) / dt;
        } else {
            double[] speed = vector(AccessPoint.ENTITY_DELTA_MOVEMENT, other);
            OTHERS[base + 4] = speed == null ? 0 : speed[0];
            OTHERS[base + 5] = speed == null ? 0 : speed[1];
            OTHERS[base + 6] = speed == null ? 0 : speed[2];
        }
        if (id != NO_ID) {
            LAST_POS.put(id, new double[]{ pos[0], pos[1], pos[2], ticks });
        }
        int flags = 0;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_ON_GROUND, other, false)) flags |= NativeCombat.FLAG_ON_GROUND;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_GLIDING, other, false)) flags |= NativeCombat.FLAG_GLIDING;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_USING_ITEM, other, false)) flags |= NativeCombat.FLAG_USING_ITEM;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_BLOCKING, other, false)) flags |= NativeCombat.FLAG_BLOCKING;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_SPRINTING, other, false)) flags |= NativeCombat.FLAG_SPRINTING;
        if (AccessorRegistry.getBoolean(AccessPoint.ENTITY_IS_SNEAKING, other, false)) flags |= NativeCombat.FLAG_SNEAKING;
        OTHERS[base + NativeCombat.OTHER_FLAGS] = flags;
        OTHERS[base + NativeCombat.OTHER_HEAD_YAW] = AccessorRegistry.getFloat(AccessPoint.ENTITY_HEAD_YAW, other, 0f);
        // Le terrain sous lui, seulement à portée de combat : au-delà, ça ne
        // sert à rien et la lecture coûte.
        OTHERS[base + NativeCombat.OTHER_GROUND] = distSq <= CLOSE_RANGE * CLOSE_RANGE ? groundHeight(pos) : -1;
        // ABI 22 — calibrage seulement, −1 si la version ne sait pas les lire.
        OTHERS[base + NativeCombat.OTHER_PITCH] = AccessorRegistry.getFloat(AccessPoint.ENTITY_PITCH, other, -1f);
        OTHERS[base + NativeCombat.OTHER_SWING] = AccessorRegistry.getFloat(AccessPoint.ENTITY_SWING_PROGRESS, other, -1f);
        NEAR[slot] = other;
        DIST_SQ[slot] = distSq;
    }

    /**
     * Qui est ce joueur ({@code identity}) et ce qu'il porte. L'équipement
     * n'est retranscrit en texte — une quarantaine de lectures d'enchantements
     * — que si l'une des six piles a changé depuis le dernier passage : la
     * relecture à chaque tick ne coûte presque rien quand rien ne bouge.
     */
    private static void describe(Object entity, int id, boolean identity, boolean equipment) {
        if (entity == null || id == NO_ID) {
            return;
        }
        if (identity) {
            Object uuid = AccessorRegistry.get(AccessPoint.ENTITY_UUID, entity);
            Object name = AccessorRegistry.get(AccessPoint.ENTITY_NAME, entity);
            if (uuid instanceof UUID && name instanceof String) {
                NativeCombat.player(id, (UUID) uuid, (String) name);
            }
        }
        if (!equipment) {
            return;
        }
        Object slots = AccessorRegistry.get(AccessPoint.ENTITY_EQUIPMENT, entity);
        if (!(slots instanceof ItemInfo[])) {
            return;
        }
        ItemInfo[] items = (ItemInfo[]) slots;
        Object[] handles = new Object[items.length + 1];
        for (int i = 0; i < items.length; i++) {
            handles[i] = items[i] == null ? null : items[i].handle;
        }
        // Le nombre d'objets de la main : une pile qui se vide garde la même poignée.
        handles[items.length] = items.length > 4 && items[4] != null ? items[4].count : 0;
        Object[] last = LAST_EQUIPMENT.get(id);
        if (last != null && sameEquipment(last, handles) && !identity) {
            return;
        }
        LAST_EQUIPMENT.put(id, handles);
        NativeCombat.equipment(id, equipmentText(items));
    }

    /** Les dernières piles vues pour chaque joueur, pour ne retranscrire que ce qui change. Vidé quand il grossit trop. */
    private static final java.util.Map<Integer, Object[]> LAST_EQUIPMENT = new java.util.HashMap<>();

    private static boolean sameEquipment(Object[] a, Object[] b) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (i == a.length - 1 ? !java.util.Objects.equals(a[i], b[i]) : a[i] != b[i]) {
                return false;
            }
        }
        if (LAST_EQUIPMENT.size() > 256) {
            LAST_EQUIPMENT.clear();
        }
        return true;
    }

    /**
     * Six cases séparées par des points-virgules (tête, torse, jambes, pieds,
     * main principale, main secondaire), chacune vide ou
     * « identifiant,enchantement=niveau,… ».
     */
    private static String equipmentText(ItemInfo[] slots) {
        StringBuilder text = new StringBuilder(96);
        for (int i = 0; i < slots.length; i++) {
            if (i > 0) {
                text.append(';');
            }
            ItemInfo item = slots[i];
            if (item == null || item.empty || item.handle == null) {
                continue;
            }
            Object id = AccessorRegistry.get(AccessPoint.ITEM_ID, item.handle);
            if (!(id instanceof String)) {
                continue;
            }
            text.append((String) id);
            for (String enchant : ENCHANTS) {
                Object level = AccessorRegistry.invoke(AccessPoint.ITEM_ENCHANT_LEVEL, item.handle, enchant);
                if (level instanceof Number && ((Number) level).intValue() > 0) {
                    text.append(',').append(enchant).append('=').append(((Number) level).intValue());
                }
            }
        }
        return text.toString();
    }

    private static double[] vector(AccessPoint point, Object entity) {
        Object v = AccessorRegistry.get(point, entity);
        return v instanceof double[] && ((double[]) v).length == 3 ? (double[]) v : null;
    }
}
