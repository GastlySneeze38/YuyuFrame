import { SkinViewer } from 'skinview3d'

/**
 * Rendus 3D cuits en images fixes.
 *
 * ── Pourquoi ──────────────────────────────────────────────────────────────
 * Le catalogue affiche une grille de skins, et un skin ne se juge pas sur une
 * tête de 8×8 : il faut voir le personnage. Mais un `SkinViewer` par case, ce
 * serait un contexte WebGL par case — les navigateurs en plafonnent une
 * quinzaine et détruisent les plus anciens au-delà, donc la grille
 * clignoterait et ramerait.
 *
 * Alors on rend chaque skin UNE fois, hors écran, et on garde le PNG obtenu.
 * La grille n'affiche plus que des `<img>`. La 3D vivante est réservée à la
 * case survolée, et il n'y en a jamais qu'une.
 *
 * Total : un contexte pour la cuisson, un pour le survol. Deux, quoi que
 * compte la grille.
 *
 * ── Un seul moteur, à la queue leu leu ────────────────────────────────────
 * Le viewer est partagé et réutilisé : `loadSkin` puis `render` puis
 * `toDataURL`. Comme il n'y en a qu'un, deux cuissons simultanées se
 * marcheraient dessus — la seconde chargerait sa texture pendant que la
 * première lit le tampon. D'où la file : les demandes s'enchaînent.
 *
 * `preserveDrawingBuffer` est indispensable ici. Sans lui, le tampon est vidé
 * dès la fin du rendu et `toDataURL` rendrait une image transparente. On crée
 * donc le contexte nous-mêmes avant `skinview3d` — la spécification WebGL
 * impose que les `getContext` suivants du même type rendent le contexte déjà
 * créé, attributs compris. Même manœuvre que pour la transparence ailleurs
 * dans le launcher.
 */

/** Taille du rendu cuit. Généreuse : l'image est réduite par la grille, et
 *  elle sert aussi d'agrandissement quand une case est mise en avant. */
const BAKE_WIDTH = 180
const BAKE_HEIGHT = 288

/**
 * Plafond du cache.
 *
 * Parcourir le catalogue longtemps finirait par retenir des centaines
 * d'images de quelques dizaines de kilo-octets. On garde les plus récentes et
 * on jette les premières entrées — revenir très en arrière recuit, ce qui est
 * rapide puisque le PNG source, lui, est toujours en cache côté Rust.
 */
const MAX_CACHED = 240

const cache = new Map<string, string>()
let viewer: SkinViewer | null = null
let queue: Promise<unknown> = Promise.resolve()

function ensureViewer(): SkinViewer {
  if (viewer) return viewer

  const canvas = document.createElement('canvas')
  const attributes = { alpha: true, premultipliedAlpha: true, preserveDrawingBuffer: true }
  canvas.getContext('webgl2', attributes) ?? canvas.getContext('webgl', attributes)

  viewer = new SkinViewer({
    canvas,
    width: BAKE_WIDTH,
    height: BAKE_HEIGHT,
    // Aucune boucle d'animation : on rend à la demande, une fois par skin.
    renderPaused: true,
  })
  viewer.background = null
  viewer.zoom = 0.88
  viewer.fov = 42
  // Trois quarts plutôt que de face : on voit le profil et le côté du corps,
  // là où la plupart des skins mettent ce qui les distingue.
  viewer.playerWrapper.rotation.y = -Math.PI / 7
  return viewer
}

async function bakeNow(key: string, src: string, slim: boolean): Promise<string> {
  // Une autre demande a pu cuire ce skin pendant l'attente dans la file.
  const done = cache.get(key)
  if (done) return done

  const v = ensureViewer()
  await v.loadSkin(src, { model: slim ? 'slim' : 'default' })
  v.render()
  const uri = v.canvas.toDataURL('image/png')

  if (cache.size >= MAX_CACHED) {
    const oldest = cache.keys().next()
    if (!oldest.done) cache.delete(oldest.value)
  }
  cache.set(key, uri)
  return uri
}

/**
 * Image fixe du rendu 3D d'un skin.
 *
 * `src` est le PNG du skin (data URI venu du Rust), `key` son adresse — c'est
 * elle qui identifie l'entrée en cache, le data URI étant trop long pour en
 * faire une clé.
 */
export function bakeSkin(key: string, src: string, slim: boolean): Promise<string> {
  const hit = cache.get(key)
  if (hit) return Promise.resolve(hit)

  const run = queue.then(() => bakeNow(key, src, slim))
  // La file ne doit pas s'arrêter sur un échec : un skin illisible ne gèle pas
  // la cuisson des suivants.
  queue = run.catch(() => {})
  return run
}
