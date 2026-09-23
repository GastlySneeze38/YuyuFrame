import { useEffect, useRef } from 'react'
import { SkinViewer } from 'skinview3d'

/**
 * Le skin du joueur en 3D, cadré sur le buste, au cœur de la bannière
 * d'accueil.
 *
 * Un vrai rendu du skin et pas une vignette plate : la tête seule ne montre
 * ni la couche « chapeau » ni les épaules, alors que c'est précisément là que
 * se voit ce qui distingue un skin d'un autre — cornes, capuche, col.
 *
 * ── Cadrage ───────────────────────────────────────────────────────────────
 * `skinview3d` vise le centre du personnage, donc à fort grossissement on
 * regarde le torse. On descend le modèle entier pour amener la tête et les
 * épaules dans le cadre : c'est le décalage de `playerWrapper`, pas la
 * caméra, qui est déplacé — les contrôles d'orbite continuent de tourner
 * autour du même axe, et le buste ne dérive pas quand le modèle pivote.
 *
 * ── Coût ──────────────────────────────────────────────────────────────────
 * C'est une boucle de rendu WebGL sur l'écran le plus regardé du launcher.
 * Elle est donc mise en pause dès que la fenêtre passe en arrière-plan, et
 * la rotation reste lente : le rendu doit se remarquer sans consommer une
 * carte graphique pendant qu'on ne regarde pas.
 */

/** Descente du modèle, en unités du monde 3D. Réglée pour poser le bas du
 *  cadre à hauteur de poitrine. */
const BUST_OFFSET_Y = -13
/** Plus la valeur est haute, plus on est près. */
const BUST_ZOOM = 2.1

export function SkinBust({ skinUrl, className = '' }: {
  /** URL ou data URI du skin. `null` = aucun compte, rien n'est rendu. */
  skinUrl: string | null
  className?: string
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)

  useEffect(() => {
    if (!canvasRef.current || !containerRef.current) return
    const container = containerRef.current
    const { width, height } = container.getBoundingClientRect()

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || 200,
      height: height || 200,
    })
    viewer.background = null
    viewer.autoRotate = true
    viewer.autoRotateSpeed = 0.55
    viewer.zoom = BUST_ZOOM
    viewer.fov = 40
    viewer.playerWrapper.position.y = BUST_OFFSET_Y
    // Pas de contrôle à la souris : la bannière n'est pas une visionneuse, et
    // un clic dessus doit ouvrir les comptes, pas faire tourner le modèle.
    viewer.controls.enabled = false
    viewerRef.current = viewer

    const ro = new ResizeObserver(() => {
      const r = container.getBoundingClientRect()
      if (r.width > 0 && r.height > 0) viewer.setSize(r.width, r.height)
    })
    ro.observe(container)

    // Fenêtre masquée ou réduite : plus personne ne regarde, la boucle de
    // rendu n'a aucune raison de continuer à tourner.
    const onVisibility = () => { viewer.renderPaused = document.hidden }
    document.addEventListener('visibilitychange', onVisibility)
    onVisibility()

    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      ro.disconnect()
      viewer.dispose()
      viewerRef.current = null
    }
  }, [])

  useEffect(() => {
    const viewer = viewerRef.current
    if (!viewer) return
    if (!skinUrl) {
      viewer.loadSkin(null)
      return
    }
    // `auto-detect` distingue les modèles fins (Alex) des classiques (Steve)
    // d'après la texture ; un skin injoignable laisse simplement le modèle
    // précédent plutôt que de vider la bannière.
    ;(viewer.loadSkin(skinUrl, { model: 'auto-detect' }) as Promise<void> | void)?.catch?.(() => {})
  }, [skinUrl])

  return (
    <div ref={containerRef} className={className}>
      <canvas ref={canvasRef} className="h-full w-full" />
    </div>
  )
}
