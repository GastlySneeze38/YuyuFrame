package com.yuyuframe.launcheragent.runtime.presence;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Le module « Joueur » sur les versions récentes du jeu (1.21.11 et 26.x) :
 * l'aspect de vanilla est gardé, la seule marque d'un joueur YuyuFrame est le
 * logo en pixels devant son pseudo — sur l'étiquette au-dessus de sa tête et
 * dans la liste Tab.
 *
 * <p>Cette classe tient ce qui ne dépend d'aucune version : qui marquer, où
 * est la place du logo, et la mémoire entre les deux temps du rendu de ces
 * versions (l'état d'une entité est d'abord copié, puis dessiné). Les types du
 * jeu restent dans le dossier de chaque version ({@code PlayerBadge261}…).
 * Tout se passe sur le thread de rendu.
 *
 * <p><b>La place du logo</b> est réservée par des espaces mis devant le
 * pseudo : vanilla mesure, centre et dessine alors l'étiquette (ou la colonne
 * de la liste Tab) avec cette place, sans qu'on touche à ses calculs.
 */
public final class PlayerBadge {

    private PlayerBadge() {
    }

    /**
     * Trois espaces, soit 12 pixels dans la police du jeu : une demi-espace
     * ({@link #INSET}), le logo ({@link #SIZE}), une demi-espace. Réglé avec
     * l'utilisateur le 2026-10-08 : une espace entière de chaque côté
     * détachait trop le logo du grade et du pseudo.
     */
    public static final String LEAD = "   ";

    /** Côté du logo, en pixels de police : la hauteur d'une ligne. */
    public static final float SIZE = 8.0F;

    /** L'écart laissé de chaque côté du logo dans sa réserve : la moitié d'une espace. */
    public static final float INSET = 2.0F;

    // ── Qui marquer ──────────────────────────────────────────────────────────

    /** L'étiquette de ce joueur prend-elle le logo ? {@code self} : c'est son propre nametag, réglé à part. */
    public static boolean marksLabel(UUID id, String name, boolean self) {
        if (!PlayerStyle.logo) {
            return false;
        }
        return self ? PlayerStyle.ownStyled : PlayerStyle.applies(id, name);
    }

    /** Sa ligne de la liste Tab prend-elle le logo ? {@code self} : c'est notre propre ligne, marquée sans question au serveur. */
    public static boolean marksTab(UUID id, String name, boolean self) {
        if (!PlayerStyle.logo || !PlayerStyle.tab) {
            return false;
        }
        return self ? PlayerStyle.enabled : PlayerStyle.applies(id, name);
    }

    // ── Nametag : son propre pseudo ──────────────────────────────────────────

    /** Dessins de poupée en cours (inventaire) : le nametag ne s'y applique pas. */
    private static int dolls;

    public static void dollBegins() {
        dolls++;
    }

    public static void dollEnds() {
        if (dolls > 0) {
            dolls--;
        }
    }

    /**
     * Cette entité est-elle notre joueur, vu de l'extérieur, interface
     * affichée, nametag demandé ? L'invisibilité reste à l'appelant, qui a les
     * types. L'interface masquée (F1) passe par le point d'accès commun : le
     * jeu a changé sa façon de le dire entre 26.1 et 26.2.
     */
    public static boolean ownNametag(Object entity) {
        if (!PlayerStyle.ownLabel || dolls > 0 || entity == null) {
            return false;
        }
        if (entity != AccessorRegistry.get(AccessPoint.CLIENT_PLAYER)) {
            return false;
        }
        if ("first_person".equals(AccessorRegistry.get(AccessPoint.OPTIONS_PERSPECTIVE))) {
            return false;
        }
        return !Boolean.TRUE.equals(AccessorRegistry.get(AccessPoint.OPTIONS_HIDE_GUI));
    }

    // ── Étiquette : de la copie de l'état à son dessin ───────────────────────

    /**
     * Le texte qu'on a posé, et où y commence la place du logo. Le logo se met
     * juste devant le PSEUDO, pas en tête du texte : beaucoup de serveurs
     * placent avant lui un grade — souvent un caractère que leur pack de
     * ressources remplace par une image.
     */
    private static final class Mark {
        final Object name;
        final float lead;

        Mark(Object name, float lead) {
            this.name = name;
            this.lead = lead;
        }
    }

    /** État d'entité → ce qu'on y a posé. Clés faibles : un état abandonné par le jeu s'en va seul. */
    private static final Map<Object, Mark> MARKED = new WeakHashMap<>();

    /** @param lead largeur, en pixels de police, de ce qui précède la place du logo dans {@code paddedName} */
    public static void mark(Object state, Object paddedName, float lead) {
        MARKED.put(state, new Mark(paddedName, lead));
    }

    public static void unmark(Object state) {
        if (!MARKED.isEmpty()) {
            MARKED.remove(state);
        }
    }

    /**
     * Où commence la place du logo dans le pseudo que porte cet état, ou une
     * valeur négative si ce pseudo n'est pas (ou plus) celui qu'on y a posé —
     * un autre code a pu le remplacer depuis.
     */
    public static float markedLead(Object state, Object currentName) {
        if (currentName == null || MARKED.isEmpty()) {
            return -1.0F;
        }
        Mark mark = MARKED.get(state);
        return mark != null && mark.name == currentName ? mark.lead : -1.0F;
    }

    // ── Liste Tab ────────────────────────────────────────────────────────────

    private static boolean inTab;
    /** Les pseudos rendus avec la place du logo pendant le dessin en cours, reconnus par identité → où y commence cette place. */
    private static final Map<Object, Float> TAB_NAMES = new IdentityHashMap<>();

    public static void tabBegins() {
        inTab = true;
        TAB_NAMES.clear();
    }

    public static void tabEnds() {
        inTab = false;
        TAB_NAMES.clear();
    }

    /** Hors du dessin de la liste, les pseudos ne sont pas touchés : la même méthode du jeu sert ailleurs. */
    public static boolean inTab() {
        return inTab;
    }

    public static void tabMark(Object paddedName, float lead) {
        TAB_NAMES.put(paddedName, lead);
    }

    /** Où commence la place du logo dans ce pseudo de la liste, ou une valeur négative s'il n'est pas marqué. */
    public static float tabLead(Object name) {
        Float lead = name == null ? null : TAB_NAMES.get(name);
        return lead == null ? -1.0F : lead;
    }
}
