/**
 * Drapeaux dessinés, pour désigner les langues du jeu.
 *
 * ── Pourquoi pas des émojis ───────────────────────────────────────────────
 * `🇫🇷` serait plus court à écrire, mais Windows ne sait pas les rendre : sa
 * police d'émojis ne contient aucun drapeau national, et le système affiche
 * alors les deux lettres du code — « FR » dans une boîte. Le launcher tourne
 * d'abord sous Windows, donc l'émoji est à écarter, pas à essayer.
 *
 * ── Pourquoi pas une bibliothèque ─────────────────────────────────────────
 * Une trentaine de drapeaux, ça ne vaut pas une dépendance de plusieurs
 * centaines. Et la plupart sont des bandes : un petit moteur déclaratif les
 * couvre tous avec quelques formes.
 *
 * Les drapeaux à emblème (Espagne, Portugal, Corée…) sont rendus sans leur
 * emblème. À 18 pixels de large il serait de toute façon une tache ; ce qui
 * identifie un drapeau à cette taille, ce sont ses couleurs et leur
 * disposition.
 */

export type FlagSpec =
  /** Bandes verticales, de gauche à droite. */
  | { type: 'v'; colors: string[] }
  /** Bandes horizontales, de haut en bas. */
  | { type: 'h'; colors: string[] }
  /** Croix scandinave, décalée vers la gauche comme il se doit. */
  | { type: 'cross'; bg: string; cross: string; inner?: string }
  /** Disque centré (Japon, Bangladesh…). */
  | { type: 'disc'; bg: string; disc: string; cx?: number }
  /** Coin haut-gauche coloré par-dessus des bandes (États-Unis, Grèce…). */
  | { type: 'canton'; stripes: string[]; canton: string; w: number; h: number }
  /** Triangle depuis le bord gauche par-dessus deux bandes (Tchéquie…). */
  | { type: 'wedge'; colors: string[]; wedge: string }
  /** Croix superposées du Royaume-Uni. */
  | { type: 'union' }

const W = 3
const H = 2

function Stripes({ colors, vertical }: { colors: string[]; vertical: boolean }) {
  const span = (vertical ? W : H) / colors.length
  return (
    <>
      {colors.map((color, i) => (
        <rect
          key={i}
          x={vertical ? i * span : 0}
          y={vertical ? 0 : i * span}
          width={vertical ? span : W}
          height={vertical ? H : span}
          fill={color}
        />
      ))}
    </>
  )
}

function Shapes({ spec }: { spec: FlagSpec }) {
  switch (spec.type) {
    case 'v':
      return <Stripes colors={spec.colors} vertical />
    case 'h':
      return <Stripes colors={spec.colors} vertical={false} />
    case 'cross':
      // Barre verticale à 1.1 plutôt qu'au centre : c'est ce décalage qui
      // rend une croix scandinave reconnaissable.
      return (
        <>
          <rect width={W} height={H} fill={spec.bg} />
          <rect x={0} y={0.8} width={W} height={0.42} fill={spec.cross} />
          <rect x={0.9} y={0} width={0.42} height={H} fill={spec.cross} />
          {spec.inner && (
            <>
              <rect x={0} y={0.9} width={W} height={0.22} fill={spec.inner} />
              <rect x={1} y={0} width={0.22} height={H} fill={spec.inner} />
            </>
          )}
        </>
      )
    case 'disc':
      return (
        <>
          <rect width={W} height={H} fill={spec.bg} />
          <circle cx={spec.cx ?? W / 2} cy={H / 2} r={0.52} fill={spec.disc} />
        </>
      )
    case 'canton':
      return (
        <>
          <Stripes colors={spec.stripes} vertical={false} />
          <rect width={spec.w} height={spec.h} fill={spec.canton} />
        </>
      )
    case 'wedge':
      return (
        <>
          <Stripes colors={spec.colors} vertical={false} />
          <polygon points={`0,0 ${W / 2},${H / 2} 0,${H}`} fill={spec.wedge} />
        </>
      )
    case 'union':
      return (
        <>
          <rect width={W} height={H} fill="#012169" />
          <path d={`M0,0 L${W},${H} M${W},0 L0,${H}`} stroke="#fff" strokeWidth={0.42} />
          <path d={`M0,0 L${W},${H} M${W},0 L0,${H}`} stroke="#C8102E" strokeWidth={0.2} />
          <path d={`M${W / 2},0 V${H} M0,${H / 2} H${W}`} stroke="#fff" strokeWidth={0.62} />
          <path d={`M${W / 2},0 V${H} M0,${H / 2} H${W}`} stroke="#C8102E" strokeWidth={0.36} />
        </>
      )
  }
}

export function Flag({ spec, size = 18, className = '' }: {
  spec: FlagSpec
  /** Largeur en pixels ; la hauteur suit le rapport 3:2. */
  size?: number
  className?: string
}) {
  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      width={size}
      height={(size * H) / W}
      // Le liseré remplace le contour blanc que beaucoup de drapeaux n'ont
      // pas : sans lui, un drapeau à bande claire se fond dans le fond sombre.
      className={`flex-shrink-0 rounded-[2px] ring-1 ring-[rgba(255,255,255,0.14)] ${className}`}
      aria-hidden
    >
      <Shapes spec={spec} />
    </svg>
  )
}
