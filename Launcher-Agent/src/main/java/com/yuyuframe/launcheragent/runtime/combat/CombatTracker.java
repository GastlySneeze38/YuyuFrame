package com.yuyuframe.launcheragent.runtime.combat;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;
import com.yuyuframe.launcheragent.apimixin.HookPoint;
import com.yuyuframe.launcheragent.apimixin.VanillaHookRegistry;
import com.yuyuframe.launcheragent.apimixin.data.DamageEvent;
import com.yuyuframe.launcheragent.apimixin.data.EntityEvent;
import com.yuyuframe.launcheragent.apimixin.data.ItemInfo;
import com.yuyuframe.launcheragent.apimixin.data.ItemUse;
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
     * Au-delà, un joueur n'est pas transmis. Large exprès : c'est le noyau
     * qui décide de la distance d'engagement, pas ce filtre, qui ne sert qu'à
     * ne pas envoyer tout un lobby vingt fois par seconde.
     */
    private static final double RANGE = 48.0;
    /** Identité et équipement : une fois par seconde suffit, le noyau n'écrit que les changements. */
    private static final int IDENTITY_EVERY_TICKS = 20;

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

    /** Branche les points d'accroche. Une seule fois, à la construction du module. */
    public static void install() {
        VanillaHookRegistry.register(HookPoint.CLIENT_TICK, ctx -> { guarded(CombatTracker::tick); return false; });
        VanillaHookRegistry.register(HookPoint.SERVER_CONNECT, ctx -> { guarded(CombatTracker::sendContext); return false; });
        VanillaHookRegistry.register(HookPoint.CLIENT_LEVEL_LOAD, ctx -> {
            // Le noyau ferme le duel en cours à tout changement de monde.
            recording = false;
            if (active) NativeCombat.world();
            return false;
        });
        VanillaHookRegistry.register(HookPoint.ENTITY_ATTACK, ctx -> {
            if (active) NativeCombat.event(NativeCombat.EVENT_ATTACK, AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, ctx, NO_ID), 0, 0, null);
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
        recording = false;
        if (!on) {
            NativeCombat.close();
        }
    }

    /**
     * Un duel est-il ouvert ? C'est l'état de la touche, pas un jugement :
     * le joueur l'ouvre et le ferme lui-même, le noyau écrit entre les deux.
     */
    private static boolean recording;

    public static boolean recording() {
        return recording;
    }

    /**
     * La touche du duel : ouvre s'il n'y en a pas, ferme sinon. Sans effet
     * hors d'un monde. Rend le nouvel état.
     */
    public static boolean toggleMatch() {
        if (!active || AccessorRegistry.get(AccessPoint.CLIENT_PLAYER, null) == null) {
            return recording;
        }
        // Le noyau doit savoir où écrire avant d'ouvrir le fichier.
        if (!contextSent) {
            sendContext();
        }
        recording = !recording;
        NativeCombat.match(recording);
        return recording;
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

        if (ticks++ % IDENTITY_EVERY_TICKS == 0) {
            describe(self, selfId);
            for (int i = 0; i < count; i++) {
                describe(NEAR[i], (int) OTHERS[i * NativeCombat.OTHER_LEN]);
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
        ME[NativeCombat.ME_FLAGS] = selfFlags();
        ME[NativeCombat.ME_PING] = AccessorRegistry.getInt(AccessPoint.PLAYER_PING, null, -1);
    }

    private static int selfFlags() {
        boolean[] move = PlayerData.movementFlags();
        boolean[] mode = PlayerData.modeFlags();
        int flags = 0;
        if (move[PlayerData.FLAG_ON_GROUND]) flags |= NativeCombat.FLAG_ON_GROUND;
        if (move[PlayerData.FLAG_SPRINTING]) flags |= NativeCombat.FLAG_SPRINTING;
        if (move[PlayerData.FLAG_GLIDING]) flags |= NativeCombat.FLAG_GLIDING;
        if (move[PlayerData.FLAG_IN_WATER]) flags |= NativeCombat.FLAG_IN_WATER;
        if (move[PlayerData.FLAG_PASSENGER]) flags |= NativeCombat.FLAG_PASSENGER;
        if (PlayerData.itemUse() != null) flags |= NativeCombat.FLAG_USING_ITEM;
        if (mode[PlayerData.MODE_SPECTATOR]) flags |= NativeCombat.FLAG_SPECTATOR;
        if (mode[PlayerData.MODE_CREATIVE]) flags |= NativeCombat.FLAG_CREATIVE;
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
        double[] speed = vector(AccessPoint.ENTITY_DELTA_MOVEMENT, other);
        int base = slot * NativeCombat.OTHER_LEN;
        OTHERS[base] = AccessorRegistry.getInt(AccessPoint.ENTITY_NETWORK_ID, other, NO_ID);
        OTHERS[base + 1] = pos[0];
        OTHERS[base + 2] = pos[1];
        OTHERS[base + 3] = pos[2];
        OTHERS[base + 4] = speed == null ? 0 : speed[0];
        OTHERS[base + 5] = speed == null ? 0 : speed[1];
        OTHERS[base + 6] = speed == null ? 0 : speed[2];
        OTHERS[base + 7] = AccessorRegistry.getBoolean(AccessPoint.ENTITY_ON_GROUND, other, false) ? NativeCombat.FLAG_ON_GROUND : 0;
        NEAR[slot] = other;
        DIST_SQ[slot] = distSq;
    }

    /** Qui est ce joueur et ce qu'il porte. */
    private static void describe(Object entity, int id) {
        if (entity == null || id == NO_ID) {
            return;
        }
        Object uuid = AccessorRegistry.get(AccessPoint.ENTITY_UUID, entity);
        Object name = AccessorRegistry.get(AccessPoint.ENTITY_NAME, entity);
        if (uuid instanceof UUID && name instanceof String) {
            NativeCombat.player(id, (UUID) uuid, (String) name);
        }
        Object equipment = AccessorRegistry.get(AccessPoint.ENTITY_EQUIPMENT, entity);
        if (equipment instanceof ItemInfo[]) {
            NativeCombat.equipment(id, equipmentText((ItemInfo[]) equipment));
        }
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
