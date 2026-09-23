import { motion } from 'framer-motion'

/**
 * Ligne d'arbres au pied de la bannière d'accueil.
 *
 * ── Pourquoi elle se répète au lieu de s'étirer ───────────────────────────
 * La version précédente était un unique tracé posé dans un `viewBox` en
 * `preserveAspectRatio="none"` : il s'étirait donc sur toute la largeur de la
 * bannière. Des formes carrées devenaient des rectangles de plus en plus
 * allongés à mesure que la fenêtre s'élargissait, et le motif perdait
 * exactement ce qui en faisait du pixel : ses proportions.
 *
 * Ici le motif est une tuile dessinée une fois, affichée en fond répété
 * horizontalement. `background-size: auto 100%` la met à l'échelle de la
 * hauteur en conservant son rapport, et `repeat-x` remplit la largeur avec
 * autant de copies qu'il faut. Les blocs gardent donc la même forme quelle
 * que soit la taille de la fenêtre — c'est le nombre d'arbres qui varie, pas
 * leur dessin.
 *
 * La tuile est refermée sur elle-même : le sol la traverse de bord à bord et
 * aucun arbre ne chevauche les extrémités, donc les raccords ne se voient
 * pas.
 */

/**
 * Encre des arbres, transparence comprise.
 *
 * L'opacité est DANS la couleur, pas sur l'élément. Posée sur un élément par
 * ailleurs animé, elle en fait une couche de composition distincte — et le
 * bord de cette couche laisse un liseré sombre quand ses limites ne tombent
 * pas sur des pixels entiers. Le haut de la bande traversait ainsi la
 * bannière d'un trait. Dans la couleur, elle ne crée aucune couche.
 */
const INK = 'rgba(4,3,12,0.88)'

/** Un arbre : tronc puis houppier, en blocs. Coordonnées dans la tuile. */
function tree(trunkX: number, trunkW: number, trunkTop: number, leafX: number, leafW: number, leafTop: number, leafH: number) {
  return (
    `<rect x="${trunkX}" y="${trunkTop}" width="${trunkW}" height="${84 - trunkTop}"/>`
    + `<rect x="${leafX}" y="${leafTop}" width="${leafW}" height="${leafH}"/>`
  )
}

const TILE = `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="100" viewBox="0 0 240 100">`
  + `<g fill="${INK}">`
  // Le sol, d'un bord à l'autre : c'est lui qui rend le raccord invisible.
  + `<rect x="0" y="84" width="240" height="16"/>`
  // Quelques bosses de terrain, pour que la ligne d'horizon ne soit pas plate.
  + `<rect x="24" y="76" width="40" height="10"/>`
  + `<rect x="150" y="78" width="56" height="8"/>`
  // Les arbres, de tailles inégales — alignés, ils feraient une palissade.
  + tree(24, 8, 58, 12, 32, 32, 30)
  + tree(68, 6, 64, 58, 26, 44, 24)
  + tree(152, 8, 54, 138, 36, 26, 32)
  + tree(204, 6, 66, 194, 26, 46, 24)
  // Deux buissons bas, pour combler les vides sans ajouter de hauteur.
  + `<rect x="100" y="72" width="24" height="14"/>`
  + `<rect x="176" y="74" width="18" height="12"/>`
  + `</g></svg>`

const TILE_URL = `url("data:image/svg+xml,${encodeURIComponent(TILE)}")`

export function Skyline() {
  return (
    <motion.div
      // Le relief respire lentement.
      animate={{ y: [0, -16, 0] }}
      transition={{ duration: 6, repeat: Infinity, ease: 'easeInOut' }}
      // Hauteur bornée en pixels plutôt qu'en pourcentage de la bannière :
      // le motif garde ainsi une taille lisible même quand la bannière
      // rétrécit, au lieu de se tasser avec elle.
      // Posée au ras du bord bas, pas en dessous. J'avais décalé la bande vers
      // le bas pour qu'elle ne découvre rien en montant — c'était inutile,
      // le dégradé sombre du pied de bannière masque déjà ce vide, et ça
      // rognait le sol au repos tout en amputant le balancement de sa course.
      className="pointer-events-none absolute inset-x-0 bottom-0 h-[clamp(62px,15vh,124px)]"
      style={{
        backgroundImage: TILE_URL,
        backgroundRepeat: 'repeat-x',
        backgroundPosition: 'bottom center',
        backgroundSize: 'auto 100%',
        // La couche est de toute façon promue par l'animation : le dire
        // évite au navigateur de la créer et de la détruire à chaque cycle.
        willChange: 'transform',
      }}
    />
  )
}
