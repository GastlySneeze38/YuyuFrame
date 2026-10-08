package com.yuyuframe.launcheragent.runtime.presence;

import com.yuyuframe.launcheragent.apimixin.AccessPoint;
import com.yuyuframe.launcheragent.apimixin.AccessorRegistry;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
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

    /** Deux espaces, soit 8 pixels dans la police du jeu : le logo (7) et un pixel d'écart. */
    public static final String LEAD = "  ";

    /** Côté du logo, en pixels de police : la hauteur d'une majuscule. */
    public static final float SIZE = 7.0F;

    // ── Qui marquer ──────────────────────────────────────────────────────────

    /** L'étiquette de ce joueur prend-elle le logo ? {@code self} : c'est son propre nametag, réglé à part. */
    public static boolean marksLabel(UUID id, String name, boolean self) {
        if (!PlayerStyle.logo) {
            return false;
        }
        return self ? PlayerStyle.ownStyled : PlayerStyle.applies(id, name);
    }

    /** Sa ligne de la liste Tab prend-elle le logo ? */
    public static boolean marksTab(UUID id, String name) {
        return PlayerStyle.logo && PlayerStyle.tab && PlayerStyle.applies(id, name);
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
     * Cette entité est-elle notre joueur, vu de l'extérieur, nametag demandé ?
     * Les conditions propres au jeu (interface masquée, invisibilité) restent
     * à l'appelant, qui a les types.
     */
    public static boolean ownNametag(Object entity) {
        if (!PlayerStyle.ownLabel || dolls > 0 || entity == null) {
            return false;
        }
        if (entity != AccessorRegistry.get(AccessPoint.CLIENT_PLAYER)) {
            return false;
        }
        return !"first_person".equals(AccessorRegistry.get(AccessPoint.OPTIONS_PERSPECTIVE));
    }

    // ── Étiquette : de la copie de l'état à son dessin ───────────────────────

    /** État d'entité → le pseudo qu'on y a posé. Clés faibles : un état abandonné par le jeu s'en va seul. */
    private static final Map<Object, Object> MARKED = new WeakHashMap<>();

    public static void mark(Object state, Object paddedName) {
        MARKED.put(state, paddedName);
    }

    public static void unmark(Object state) {
        if (!MARKED.isEmpty()) {
            MARKED.remove(state);
        }
    }

    /** Vrai si le pseudo que porte cet état est bien celui qu'on y a posé (un autre code a pu le remplacer depuis). */
    public static boolean isMarked(Object state, Object currentName) {
        return currentName != null && !MARKED.isEmpty() && MARKED.get(state) == currentName;
    }

    // ── Liste Tab ────────────────────────────────────────────────────────────

    private static boolean inTab;
    /** Les pseudos rendus avec la place du logo pendant le dessin en cours, reconnus par identité. */
    private static final Set<Object> TAB_NAMES = Collections.newSetFromMap(new IdentityHashMap<>());

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

    public static void tabMark(Object paddedName) {
        TAB_NAMES.add(paddedName);
    }

    public static boolean tabMarked(Object name) {
        return name != null && TAB_NAMES.contains(name);
    }
}
