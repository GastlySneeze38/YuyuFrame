package net.minecraft.network.chat;

/**
 * Stub compile-only (26.1+) — type de retour RÉEL de
 * {@link Component#literal(String)} et {@link Component#translatable(String)}.
 *
 * <p>Créé le 2026-08-27 : le stub de {@code Component} déclarait ces deux
 * fabriques comme renvoyant {@code Component}, alors qu'elles renvoient
 * {@code MutableComponent}. Le type de retour faisant partie du descripteur
 * d'appel, {@code Component.literal(...)} partait en
 * {@code NoSuchMethodError} au runtime — la fusion des messages répétés de
 * {@code ChatEnhancementsModule} ne fonctionnait donc que via le repli
 * réflexif, et sa suppression a rendu la panne visible.
 *
 * <p>Hiérarchie vérifiée sur le jar client 26.1.2 :
 * {@code final class MutableComponent implements Component} — d'où
 * l'affectation directe à une variable {@code Component} côté appelant.
 */
public final class MutableComponent implements Component {
    private MutableComponent() {}

    /** Ajoute un composant à la suite — descripteur vérifié sur le jar 26.1.2 : {@code (Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;}. */
    public MutableComponent append(Component sibling) { return null; }

    /**
     * Teinte le composant (RGB 24 bits). Préféré à {@code withStyle(ChatFormatting)}
     * — même résultat visuel ici, mais SANS avoir à stuber l'énumération
     * {@code net.minecraft.ChatFormatting} au passage. Descripteur vérifié sur
     * le jar 26.1.2 : {@code (I)Lnet/minecraft/network/chat/MutableComponent;}.
     */
    public MutableComponent withColor(int rgb) { return null; }
}
