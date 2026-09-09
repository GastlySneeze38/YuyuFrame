package com.yuyuframe.launcheragent.mixin.client.v1_16;

import com.yuyuframe.launcheragent.base.log.LauncherLog;
import com.yuyuframe.launcheragent.apimixin.mapping.MappingsRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Résolution partagée entre {@link GlobalUiRenderMixin116} (dessin, TAIL de
 * {@code GameRenderer.render()}) et {@link GlobalTickMixin116} (transitions
 * d'écran, {@code MinecraftClient.tick()}) — extrait ici pour que les deux
 * Mixins (classes cible DIFFÉRENTES, donc impossible à fusionner en un seul
 * fichier Mixin) partagent la même Method mise en cache pour setScreen (voir
 * historique de session : redécouvrir indépendamment cette Method à chaque
 * fermeture avait fini par retomber sur un mauvais candidat une fois).
 *
 * PLUS de {@code execute(Runnable)} ici (contrairement à l'ancienne version
 * de ce code, qui vivait dans GlobalUiRenderMixin116) : cette indirection ne
 * servait qu'à différer l'appel hors du contexte réentrant du TAIL de
 * render() (voir historique — la vraie setScreen() pompe les évènements GLFW
 * en direct). Maintenant que les appels viennent de {@code tick()} — un point
 * de la boucle principale déjà sûr et non-réentrant, exactement le point que
 * les vrais mods Fabric utilisent pour ouvrir un écran (voir doc Fabric,
 * "Custom Screens") — cette indirection n'a plus de raison d'être.
 *
 * PUBLIC (classe ET membres) — INDISPENSABLE, pas juste une convention : les
 * deux Mixins qui appellent cette classe sont FUSIONNÉS par bytecode dans
 * leurs cibles respectives (`GameRenderer`/`MinecraftClient`, obfusquées,
 * package `net.minecraft.*`) — une fois fusionné, l'appel à `ScreenBridge116`
 * s'exécute comme s'il faisait partie de LEUR package, pas du nôtre. Une
 * visibilité package-private (constatée en jeu : {@code
 * java.lang.IllegalAccessError: tried to access class ...ScreenBridge116
 * from class dzz}) casse donc l'accès dès que le code est fusionné hors de
 * son package d'origine — leçon distincte de celle sur les méthodes
 * non-privées DANS un Mixin (voir historique de session, erreur similaire
 * mais cause inverse : ici il faut ÉLARGIR la visibilité, pas la restreindre).
 */
public final class ScreenBridge116 {

    private ScreenBridge116() {}

    /**
     * Drapeau LATCHÉ partagé entre les deux Mixins — voir GlobalUiRenderMixin116
     * (pose) et GlobalTickMixin116 (consomme). DOIT vivre ici, PAS dans une
     * classe Mixin : Sponge Mixin rejette au chargement toute méthode
     * non-{@code private} déclarée dans le corps d'un Mixin ("contains
     * non-private static method") — un simple accesseur package-private
     * suffisant pour un partage normal entre classes Java échoue donc au
     * tissage si placé directement dans GlobalUiRenderMixin116 (constaté en
     * jeu : `InvalidMixinException` au chargement).
     */
    private static volatile boolean pendingMenuOpen;

    public static void requestMenuOpen() {
        pendingMenuOpen = true;
    }

    public static boolean consumePendingMenuOpen() {
        boolean v = pendingMenuOpen;
        pendingMenuOpen = false;
        return v;
    }

    private static final String YARN_MC = "net/minecraft/client/MinecraftClient";

    public static Object getCurrentScreen(Object mc) {
        return getNamedField(mc, YARN_MC, "currentScreen");
    }

    public static Object getNamedField(Object obj, String yarnOwner, String yarnField) {
        String fieldName = MappingsRegistry.getObfFieldName(yarnOwner, yarnField);
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Classe RÉELLE (celle du jeu, plus haut dans la hiérarchie que notre
     * propre package) au-dessus de {@code screenClass} — sert à construire le
     * descripteur officiel exact pour {@link #resolveSetScreenMethod}.
     */
    private static Class<?> nativeScreenClass(Class<?> screenClass) {
        Class<?> c = screenClass;
        while (c != null && c.getName().startsWith("com.yuyuframe.launcheragent")) {
            c = c.getSuperclass();
        }
        return c;
    }

    /**
     * BUG TROUVÉ (voir historique de session — incohérence totale entre
     * sessions de test, certaines fonctionnaient, d'autres non, sans
     * changement de code) : `MinecraftClient` a en réalité QUATRE méthodes de
     * la forme EXACTE {@code (Screen):void} — {@code openScreen} (la vraie,
     * voir plus bas pourquoi ce nom), {@code disconnect} (déconnexion du
     * monde en affichant l'écran donné pendant la transition), une 3e sans
     * nom Yarn (jamais mappée dans ce jar), et {@code reset}. L'ancienne
     * résolution par FORME SEULE ({@code findSetScreenMethod}, un seul
     * paramètre de type Screen) ne les distinguait jamais — et {@code
     * Class.getMethods()} ne garantit AUCUN ordre stable d'une exécution JVM
     * à l'autre, donc retombait tantôt sur l'une, tantôt sur l'autre. Quand
     * c'était {@code disconnect} : le "menu" s'affichait quand même (le
     * Screen donné est bien montré pendant la transition) mais rien d'autre
     * ne fonctionnait normalement (déconnexion réelle en arrière-plan, clics/
     * touches jamais dispatchés par le moteur).
     *
     * 1er correctif tenté (nom Yarn "setScreen") — ÉCHOUÉ : {@code
     * NoSuchMethodException}, silencieusement retombé sur le nom Yarn
     * inchangé (comportement de repli de {@code getObfMethodName} quand
     * aucune entrée ne correspond). Cause : dans CE jar de mappings précis
     * ({@code yarn-1.16.5-mergedv2.jar}), le nom Yarn de cette méthode sur
     * `MinecraftClient` n'est PAS "setScreen" (convention plus récente) mais
     * **"openScreen"** (convention Yarn plus ancienne pour ce même concept,
     * confirmé en listant TOUTES les méthodes `(Ldot;)V` du bloc `djz` dans
     * les mappings extraites — {@code method_1507 openScreen}, PAS
     * {@code method_25289 setScreen} qui, lui, appartient à une classe
     * Realms totalement différente, `LongRunningTask`, coïncidence de forme
     * qui a fait échouer le lookup sans lever d'erreur explicite).
     *
     * FIX final : résolution par NOM Yarn "openScreen" (avec repli sur
     * "setScreen" si un futur bracket/jar de mappings utilise l'autre
     * convention) + DESCRIPTEUR OFFICIEL EXACT (construit dynamiquement à
     * partir de la vraie classe Screen déjà résolue) — lève l'ambiguïté des
     * deux côtés à la fois (la classe ET la méthode), plus aucune dépendance
     * à l'ordre de {@code getMethods()}.
     */
    private static Method resolveSetScreenMethod(Class<?> mcClass, Class<?> nativeScreen) {
        String officialDesc = "(L" + nativeScreen.getName().replace('.', '/') + ";)V";
        String obfName = MappingsRegistry.getObfMethodName(YARN_MC, "openScreen", officialDesc);
        if ("openScreen".equals(obfName)) {
            // Repli (jamais résolu → nom Yarn inchangé, voir getObfMethodName) —
            // essaie l'autre convention de nommage Yarn pour ce même concept.
            obfName = MappingsRegistry.getObfMethodName(YARN_MC, "setScreen", officialDesc);
        }
        try {
            return mcClass.getMethod(obfName, nativeScreen);
        } catch (NoSuchMethodException e) {
            LauncherLog.err("[LauncherAgent] ScreenBridge116: setScreen introuvable (nom résolu=" + obfName + "): " + e);
            return null;
        }
    }

    /**
     * Mise en cache après la première résolution réussie — plus une question
     * d'ambiguïté maintenant (voir {@link #resolveSetScreenMethod}), juste
     * pour éviter de refaire le lookup à chaque appel.
     */
    private static Method cachedSetScreenMethod;

    public static void setScreen(Object mc, Object screen) throws Exception {
        if (cachedSetScreenMethod == null) {
            Class<?> nativeScreen = nativeScreenClass(screen.getClass());
            cachedSetScreenMethod = resolveSetScreenMethod(mc.getClass(), nativeScreen);
            LauncherLog.info("[LauncherAgent] DIAG-116: méthode setScreen résolue = " + cachedSetScreenMethod);
        }
        invokeSetScreen(mc, screen);
    }

    public static void closeScreen(Object mc, Class<?> closingScreenType) throws Exception {
        if (cachedSetScreenMethod == null) {
            Class<?> nativeScreen = nativeScreenClass(closingScreenType);
            cachedSetScreenMethod = resolveSetScreenMethod(mc.getClass(), nativeScreen);
        }
        invokeSetScreen(mc, null);
    }

    private static void invokeSetScreen(Object mc, Object screen) throws Exception {
        if (cachedSetScreenMethod == null) {
            LauncherLog.warn("[LauncherAgent] ScreenBridge116: setScreen introuvable");
            return;
        }
        cachedSetScreenMethod.invoke(mc, screen);
    }
}
