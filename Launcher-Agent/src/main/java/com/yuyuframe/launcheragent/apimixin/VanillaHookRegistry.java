package com.yuyuframe.launcheragent.apimixin;

import com.yuyuframe.launcheragent.runtime.log.LauncherLog;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
 */
public final class VanillaHookRegistry {

    private VanillaHookRegistry() {}

    private static final Map<HookPoint, List<Consumer<Object>>> HANDLERS = new EnumMap<>(HookPoint.class);

    /** Enregistre {@code handler} pour {@code point} — appelé à chaque {@link #dispatch} de ce HookPoint, tant que le module reste construit (pas de désenregistrement : le tissage Mixin est figé au chargement de classe, voir la javadoc de {@link HookPoint}). */
    public static void register(HookPoint point, Consumer<Object> handler) {
        HANDLERS.computeIfAbsent(point, p -> new ArrayList<>()).add(handler);
    }

    /**
     * Appelé par le mixin de {@code apimixin/} qui cible ce {@code point} —
     * jamais l'inverse (un module ne déclenche jamais lui-même un dispatch).
     * Chaque handler est isolé par son propre {@code try/catch} : un module
     * qui lève ne doit jamais empêcher les autres modules enregistrés sur ce
     * même {@code point} de recevoir l'appel, ni faire remonter l'exception
     * dans le pipeline de rendu/tick vanilla qui a déclenché ce hook.
     */
    public static void dispatch(HookPoint point, Object ctx) {
        List<Consumer<Object>> handlers = HANDLERS.get(point);
        if (handlers == null || handlers.isEmpty()) return;
        for (Consumer<Object> handler : handlers) {
            try {
                handler.accept(ctx);
            } catch (Throwable t) {
                LauncherLog.err("[VanillaHookRegistry] handler en erreur pour " + point + ": " + t);
            }
        }
    }

    /**
     * {@code true} si au moins un module a déclaré ce {@code point} —
     * consulté par {@code shouldApplyMixin()} (voir {@code LauncherMixinConfigPlugin})
     * pour décider si le mixin correspondant doit être tissé du tout (un
     * mixin par HookPoint, voir la javadoc de {@link HookPoint} — donc cette
     * décision reste possible hook par hook, jamais tout ou rien pour un
     * fichier qui en regrouperait plusieurs).
     */
    public static boolean isUsed(HookPoint point) {
        List<Consumer<Object>> handlers = HANDLERS.get(point);
        return handlers != null && !handlers.isEmpty();
    }
}
