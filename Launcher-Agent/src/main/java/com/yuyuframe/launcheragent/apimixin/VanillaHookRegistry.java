package com.yuyuframe.launcheragent.apimixin;

import com.yuyuframe.launcheragent.base.log.LauncherLog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatcher générique par {@link HookPoint} — voir ROADMAP-agent.md §3.2.
 *
 * Remplace le modèle Fabric API ({@code Event<T>}/{@code EventFactory},
 * pensé pour un nombre INCONNU de mods tiers qui s'enregistrent
 * dynamiquement — tableau de listeners reconstruit à chaque
 * register/unregister, tri par phase, invoker générique par réflexion) par
 * quelque chose de bien plus simple : ce projet connaît à l'avance
 * l'ensemble FIXE de ses propres modules (~44, voir {@code ModuleRegistry}),
 * donc un lookup direct suffit — pas de notion de phase, pas d'{@code Event}
 * générique, pas d'invoker synthétisé.
 *
 * Chaque mixin de {@code apimixin/} appelle {@link #dispatch} au point
 * d'accroche vanilla qu'il cible ; chaque module intéressé s'enregistre via
 * {@link #register} pour ce {@link HookPoint} précis (le câblage exact
 * module → HookPoint — déclaration au constructeur de {@code LauncherModule}
 * façon {@code super("mon-module", HookPoint.ATTACK_ENTITY)} — reste à faire,
 * voir ROADMAP-agent.md §3.2, ceci est volontairement juste le dispatcher).
 *
 * {@code ctx} générique ({@code Object}, pas un type dédié par HookPoint) —
 * décision actée : simplicité de cette première version, chaque module
 * caste vers le type réel qu'il sait attendre pour CE HookPoint précis
 * (documenté dans le commentaire du mixin correspondant dans {@code apimixin/}).
 *
 * ENRICHISSEMENT (annulation/remplacement — voir la javadoc de {@link HookHandler}) :
 * {@link #dispatch} retourne désormais {@code boolean} au lieu de {@code void}
 * — {@code true} si AU MOINS UN handler a pris en charge le rendu lui-même,
 * auquel cas le mixin appelant NE DOIT PAS dessiner vanilla ensuite. Pour
 * l'instant, seuls les mixins de {@code apimixin/v26_1/hud/} consultent
 * réellement cette valeur de retour (les autres catégories continuent à
 * l'ignorer, donc à toujours dessiner vanilla en plus — comportement
 * inchangé pour elles, migration au cas par cas plus tard si besoin).
 */
public final class VanillaHookRegistry {

    private VanillaHookRegistry() {}

    /**
     * Handler enregistré pour un {@link HookPoint}.
     *
     * @return {@code true} si CE handler a lui-même pris en charge le rendu
     *         (dessiné quelque chose à la place de vanilla, ou décidé de ne
     *         rien dessiner du tout) — le mixin appelant doit alors sauter
     *         son propre appel vanilla. {@code false} pour un handler
     *         purement notificatif (le cas de loin le plus courant — ex:
     *         un module qui veut juste savoir qu'un élément va se dessiner,
     *         sans le remplacer) : retourner {@code false} systématiquement
     *         équivaut exactement à l'ancien {@code Consumer<Object>}.
     *
     *         Si PLUSIEURS handlers sont enregistrés sur le même HookPoint,
     *         TOUS sont appelés (pas de court-circuit au premier qui prend
     *         la main) — un module qui observe seulement ne doit pas être
     *         privé de sa notification parce qu'un autre a remplacé le
     *         dessin vanilla. Le résultat final de {@link #dispatch} est le
     *         OU logique de tous les retours.
     */
    @FunctionalInterface
    public interface HookHandler {
        boolean handle(Object ctx);
    }

    private static final Map<HookPoint, List<HookHandler>> HANDLERS = new EnumMap<>(HookPoint.class);

    /** Enregistre {@code handler} pour {@code point} — appelé à chaque {@link #dispatch} de ce HookPoint, tant que le module reste construit (pas de désenregistrement : le tissage Mixin est figé au chargement de classe, voir la javadoc de {@link HookPoint}). */
    public static void register(HookPoint point, HookHandler handler) {
        HANDLERS.computeIfAbsent(point, p -> new ArrayList<>()).add(handler);
    }

    /**
     * Appelé par le mixin de {@code apimixin/} qui cible ce {@code point} —
     * jamais l'inverse (un module ne déclenche jamais lui-même un dispatch).
     * Chaque handler est isolé par son propre {@code try/catch} : un module
     * qui lève ne doit jamais empêcher les autres modules enregistrés sur ce
     * même {@code point} de recevoir l'appel, ni faire remonter l'exception
     * dans le pipeline de rendu/tick vanilla qui a déclenché ce hook — une
     * exception compte comme {@code false} (pas de prise en charge) pour ce
     * handler précis.
     *
     * @return {@code true} si au moins un handler a retourné {@code true}
     *         (voir {@link HookHandler}) — l'appelant doit alors sauter son
     *         dessin vanilla. {@code false} si la liste est vide/absente ou
     *         si tous les handlers ont retourné {@code false}.
     */
    public static boolean dispatch(HookPoint point, Object ctx) {
        List<HookHandler> handlers = HANDLERS.get(point);
        if (handlers == null || handlers.isEmpty()) return false;
        boolean handled = false;
        for (HookHandler handler : handlers) {
            try {
                if (handler.handle(ctx)) handled = true;
            } catch (Throwable t) {
                LauncherLog.err("[VanillaHookRegistry] handler en erreur pour " + point + ": " + t);
            }
        }
        return handled;
    }

    /**
     * Variante "remplacement de valeur de retour" — pour les hooks type
     * {@code ClientClockManagerWorldTimeMixin261} où {@link HookHandler}
     * (booléen "annulé ou pas") ne suffit pas : le mixin appelant a besoin
     * d'une VALEUR à renvoyer, pas juste d'un signal cancel/pas-cancel. Reste
     * séparé de {@link HookHandler}/{@link #dispatch} plutôt que de forcer
     * {@code Object} en résultat partout — la plupart des HookPoint restent
     * de simples notifications booléennes, pas besoin d'alourdir ce cas
     * courant pour ce cas rare.
     */
    @FunctionalInterface
    public interface ValueHandler {
        /** Retourne une valeur de remplacement, ou {@code null} pour laisser vanilla faire son calcul normal. */
        Object resolve(Object ctx);
    }

    private static final Map<HookPoint, List<ValueHandler>> VALUE_HANDLERS = new EnumMap<>(HookPoint.class);

    public static void registerValue(HookPoint point, ValueHandler handler) {
        VALUE_HANDLERS.computeIfAbsent(point, p -> new ArrayList<>()).add(handler);
    }

    /**
     * Ensemble des {@link HookPoint} sur lesquels au moins un handler s'est
     * réellement enregistré (les deux familles confondues : {@link HookHandler}
     * et {@link ValueHandler}).
     *
     * Sert à l'audit de cohérence avec les déclarations statiques
     * {@code LauncherModule.hookPoints} — voir {@link #auditDeclarations}.
     * NE PAS utiliser pour décider d'un tissage : à l'instant du tissage ce
     * jeu est TOUJOURS vide, les enregistrements n'ayant lieu qu'à la première
     * frame — c'est pour ça que le filtrage réel se fait par lecture de
     * bytecode AVANT le tissage, voir {@code
     * IsolatedBootstrap.filterConfigByHookPoints()}.
     */
    public static java.util.Set<HookPoint> usedPoints() {
        java.util.EnumSet<HookPoint> used = java.util.EnumSet.noneOf(HookPoint.class);
        for (Map.Entry<HookPoint, List<HookHandler>> e : HANDLERS.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) used.add(e.getKey());
        }
        for (Map.Entry<HookPoint, List<ValueHandler>> e : VALUE_HANDLERS.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) used.add(e.getKey());
        }
        return used;
    }

    /**
     * Compare ce qui est RÉELLEMENT enregistré à ce qui est DÉCLARÉ
     * statiquement, et journalise tout écart (2026-08-25, §12).
     *
     * Le catalogue statique ({@code LauncherModule.hookPoints} + les
     * registrants d'infrastructure passés en {@code extraDeclared}) est la
     * seule source d'information exploitable AVANT le tissage. Il n'a de
     * valeur que s'il reste exact : cet audit existe pour qu'une dérive se
     * voie immédiatement dans launcher-agent.log au lieu de se traduire, plus
     * tard, par un mixin écarté à tort si la gate est un jour activée.
     *
     * @param declared union des HookPoint déclarés statiquement
     */
    public static void auditDeclarations(java.util.Set<HookPoint> declared) {
        java.util.Set<HookPoint> used = usedPoints();

        java.util.EnumSet<HookPoint> undeclared = java.util.EnumSet.noneOf(HookPoint.class);
        undeclared.addAll(used);
        undeclared.removeAll(declared);

        java.util.EnumSet<HookPoint> unused = java.util.EnumSet.noneOf(HookPoint.class);
        unused.addAll(declared);
        unused.removeAll(used);

        if (!undeclared.isEmpty()) {
            // Cas GRAVE pour une future gate : ces hooks sont utilisés sans
            // être annoncés, donc leur mixin serait écarté à tort.
            LauncherLog.err("[HookPointAudit] UTILISÉS MAIS NON DÉCLARÉS (" + undeclared.size()
                + ") — à ajouter au super(...) du module concerné : " + undeclared);
        }
        if (!unused.isEmpty()) {
            // Bénin : déclaration trop large (module désactivé, ou hook prévu
            // mais pas encore branché).
            LauncherLog.warn("[HookPointAudit] déclarés mais non enregistrés (" + unused.size()
                + ") — bénin (module inactif ou hook prévu) : " + unused);
        }
        LauncherLog.ui(3, "[HookPointAudit] " + used.size() + " HookPoint utilisés, "
            + declared.size() + " déclarés, " + undeclared.size() + " écart(s) bloquant(s)");
    }

    /**
     * @return la première valeur de remplacement non-nulle fournie par un
     *         handler enregistré sur {@code point}, ou {@code null} si aucun
     *         n'en fournit (l'appelant doit alors laisser vanilla s'exécuter
     *         normalement) — PAS de OU logique ici (contrairement à {@link
     *         #dispatch}) : une seule valeur peut être renvoyée à l'appelant,
     *         le premier handler qui en fournit une gagne.
     */
    public static Object dispatchValue(HookPoint point, Object ctx) {
        List<ValueHandler> handlers = VALUE_HANDLERS.get(point);
        if (handlers == null) return null;
        for (ValueHandler handler : handlers) {
            try {
                Object result = handler.resolve(ctx);
                if (result != null) return result;
            } catch (Throwable t) {
                LauncherLog.err("[VanillaHookRegistry] value handler en erreur pour " + point + ": " + t);
            }
        }
        return null;
    }
}
