/**
 * La tête d'un skin en 2D, comme le jeu la dessine.
 *
 * ── Les DEUX couches, et le piège qu'on s'est tendu ───────────────────────
 * Une tête de skin n'est pas une image mais deux, superposées :
 *
 *   base     carré de 8×8 à l'offset (8, 8)  — le visage
 *   dessus   carré de 8×8 à l'offset (40, 8) — cheveux, chapeau, lunettes…
 *
 * Tous les recadrages du launcher ne prenaient que la base. Les skins dont la
 * chevelure vit dans la couche du dessus — c'est-à-dire l'immense majorité —
 * apparaissaient donc chauves, ou amputés de leur casque, alors que l'aperçu
 * 3D juste à côté les montrait entiers. Le rendu semblait cassé au hasard.
 *
 * On empile donc deux fonds dans le même élément. En CSS, le PREMIER calque
 * est peint au-dessus : le dessus vient donc en tête, la base ensuite.
 *
 * ── Pourquoi des pixels en ligne plutôt que des classes ───────────────────
 * Les trois nombres dépendent de `size` : un fond de 8 × size (la texture fait
 * 64 de large, la tête 8), décalé de size pour la base et de 5 × size pour le
 * dessus. Tailwind lit le source et ne génère pas une classe calculée à
 * l'exécution, donc la géométrie passe par `style` — c'est le seul endroit où
 * elle est juste par construction plutôt que par recopie.
 *
 * Fonctionne pour les deux formats de skin : la tête est au même endroit en
 * 64×64 et en 64×32.
 */
export function SkinFace({
  dataUri,
  size,
  className = '',
}: {
  /** PNG du skin, en data URI ou en URL. */
  dataUri: string
  /** Côté du carré rendu, en pixels. */
  size: number
  className?: string
}) {
  const sheet = `${size * 8}px ${size * 8}px`
  return (
    <div
      aria-hidden="true"
      className={`shrink-0 [image-rendering:pixelated] ${className}`}
      style={{
        width: size,
        height: size,
        backgroundImage: `url(${dataUri}), url(${dataUri})`,
        backgroundSize: `${sheet}, ${sheet}`,
        backgroundPosition: `${-size * 5}px ${-size}px, ${-size}px ${-size}px`,
        backgroundRepeat: 'no-repeat, no-repeat',
      }}
    />
  )
}
