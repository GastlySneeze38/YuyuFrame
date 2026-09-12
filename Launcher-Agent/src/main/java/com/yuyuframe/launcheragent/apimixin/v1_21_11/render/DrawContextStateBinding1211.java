package com.yuyuframe.launcheragent.apimixin.v1_21_11.render;

/**
 * Associe un {@code DrawContext} 1.21.11 à SON {@code GuiRenderState}.
 *
 * <h2>Pourquoi ce détour</h2>
 *
 * Les hooks {@code HUD_EXTRACT_*} de la 1.21.11 transportent un
 * {@code DrawContext} ; émettre dans l'état de GUI de vanilla demande le
 * {@code GuiRenderState} qu'il porte. Or ce dernier est un champ PRIVÉ
 * ({@code state}) sans accesseur public — vérifié par {@code javap} sur le jar
 * client réel.
 *
 * <p>La réflexion est exclue (norme du projet), et un {@code @Accessor} Mixin
 * ne convient pas non plus : il devrait déclarer le type de retour réel, que
 * l'unité de compilation principale ne peut pas nommer (nom Yarn inexistant au
 * runtime sous Fabric). Les DEUX constructeurs de {@code DrawContext} reçoivent
 * en revanche l'état en paramètre — et le public délègue au privé (vérifié au
 * bytecode) : {@code DrawContextStateMixin1211} capte donc l'état là, en
 * {@code @Coerce Object}, et le dépose ici.
 *
 * <p>Poignées opaques ({@code Object}) : ce paquet est compilé une seule fois,
 * sans types du jeu. C'est {@code VanillaGuiSink1211} (unité 1.21.11, typée)
 * qui les retranstype.
 *
 * <p>Thread de rendu uniquement — ni synchronisation ni volatile, comme le
 * reste du chemin de rendu.
 */
public final class DrawContextStateBinding1211 {

    private DrawContextStateBinding1211() {
    }

    /**
     * Clés FAIBLES : un {@code DrawContext} est créé par passe de GUI, donc
     * plusieurs fois par seconde — les retenir fortement serait une fuite.
     */
    private static final java.util.Map<Object, Object> STATES = new java.util.WeakHashMap<>();

    /** Dernier couple vu — court-circuit du cas courant (un seul contexte vivant à la fois). */
    private static Object lastContext;
    private static Object lastState;

    /** Appelé par {@code DrawContextStateMixin1211} à la construction du contexte. */
    public static void bind(Object drawContext, Object guiRenderState) {
        if (drawContext == null || guiRenderState == null) return;
        STATES.put(drawContext, guiRenderState);
        lastContext = drawContext;
        lastState = guiRenderState;
    }

    /** @return l'état de GUI porté par ce contexte, ou {@code null} si le mixin n'a pas été tissé. */
    public static Object stateFor(Object drawContext) {
        if (drawContext == null) return null;
        if (drawContext == lastContext) return lastState;
        return STATES.get(drawContext);
    }
}
