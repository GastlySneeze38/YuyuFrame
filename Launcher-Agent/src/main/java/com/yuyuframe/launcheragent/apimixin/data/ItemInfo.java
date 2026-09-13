package com.yuyuframe.launcheragent.apimixin.data;

/**
 * Une pile d'objets, en données NEUTRES — pendant de {@link PlayerEffect} pour
 * l'inventaire.
 *
 * <h2>Pourquoi un porteur ET une poignée</h2>
 *
 * Contrairement à {@link PlayerEffect}, ce porteur garde {@link #handle}, la
 * pile du jeu telle quelle. C'est assumé : l'appelant en fait DEUX choses de
 * nature différente.
 * <ul>
 *   <li>Il la LIT (durabilité, taille de pile) — et ces lectures-là doivent
 *       être neutres, sinon il faut nommer {@code ItemStack} et le module ne
 *       tourne plus que sur une version. D'où les champs ci-dessous, remplis
 *       par la liaison de la tranche active.</li>
 *   <li>Il la REPASSE au moteur de rendu pour dessiner l'icône vanilla
 *       ({@code UiRenderer.drawVanillaItemIcon}), qui a besoin de l'objet
 *       réel. Une poignée OPAQUE suffit alors : l'appelant ne fait que la
 *       transporter, sans jamais nommer son type.</li>
 * </ul>
 *
 * <p>Une pile vide est représentée par {@link #EMPTY} plutôt que par
 * {@code null} : les appelants parcourent des emplacements d'équipement, dont
 * certains sont vides par nature, et un tableau sans trous se lit sans test de
 * nullité à chaque case.
 */
public final class ItemInfo {

    /** Emplacement vide — poignée {@code null}, tout à zéro. */
    public static final ItemInfo EMPTY = new ItemInfo(null, true, 0, false, 0, 0);

    /**
     * La pile du jeu, OPAQUE — à ne transmettre qu'au moteur de rendu, jamais
     * à transtyper. {@code null} pour un emplacement vide.
     */
    public final Object handle;

    /** Emplacement vide (ou pile vide au sens du jeu). */
    public final boolean empty;

    /** Taille de la pile ; 0 si vide. */
    public final int count;

    /** L'objet s'use-t-il ? Faux pour un bloc, une nourriture, etc. */
    public final boolean damageable;

    /** Usure actuelle, 0 = neuf. Sans objet si {@link #damageable} est faux. */
    public final int damage;

    /** Usure maximale ; la durabilité restante vaut {@code maxDamage - damage}. */
    public final int maxDamage;

    public ItemInfo(Object handle, boolean empty, int count,
                    boolean damageable, int damage, int maxDamage) {
        this.handle = handle;
        this.empty = empty;
        this.count = count;
        this.damageable = damageable;
        this.damage = damage;
        this.maxDamage = maxDamage;
    }

    /**
     * Durabilité restante, ou {@code -1} si l'objet ne s'use pas — l'appelant
     * DOIT distinguer les deux : « 0 restant » est une pioche sur le point de
     * casser, pas un bloc de pierre.
     */
    public int remaining() {
        return damageable && maxDamage > 0 ? maxDamage - damage : -1;
    }
}
