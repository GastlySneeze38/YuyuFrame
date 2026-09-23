import { useCallback, useEffect, useRef } from 'react'
import { SkinViewer } from 'skinview3d'

/**
 * Le buste du joueur en 3D, au cœur de la bannière d'accueil.
 *
 * ── Ce qui a mal tourné avant, et pourquoi c'est réglé ────────────────────
 * Trois causes distinctes se sont additionnées, ce qui a beaucoup brouillé le
 * diagnostic. Elles sont notées ici pour qu'on ne les recherche pas une
 * quatrième fois.
 *
 * 1. Le canevas sortait OPAQUE. `skinview3d` construit son `WebGLRenderer`
 *    sans préciser `alpha`, et three.js le laisse à `false` : son
 *    `setClearColor(0, 0)` peignait donc du noir au lieu de laisser passer le
 *    fond. Invisible sur l'écran des comptes, dont le fond est déjà sombre.
 *
 * 2. Le CADRAGE reposait sur des constantes écrites à la main, déduites d'une
 *    lecture du repère du modèle. Cette lecture était fausse, et trois
 *    tentatives de la corriger « au signe près » ont échoué. Plus aucune
 *    constante de position ici : la hauteur de la tête est MESURÉE au
 *    montage, et le cadrage en découle (voir `frame`).
 *
 * 3. Le skin lui-même est parfois presque NOIR. Un modèle noir, sur un
 *    canevas noir, mal cadré : il n'y avait rien à lire, et on pouvait croire
 *    la texture cassée alors qu'elle était juste.
 */

const BUST_FOV = 40

// ── Les deux réglages du cadrage ───────────────────────────────────────────
//
// Ils se lisent ensemble, et il faut voir qu'ils commandent DEUX choses à la
// fois — c'est ce qui m'a piégé une fois :
//
//   taille   la tête fait 9 unités, la hauteur visible vaut
//            `9 + MARGIN_TOP + SHOULDERS`. Plus la SOMME est grande, plus la
//            tête paraît petite. Ici 19,5 → elle occupe ~46 % de la bannière.
//
//   coupe    la ligne d'épaules tombe à `(MARGIN_TOP + 9) / somme` du haut du
//            cadre. Ici 72 %. Elle dépend donc du PARTAGE entre les deux, pas
//            de leur somme : pour la remonter sans retoucher la taille, on
//            déplace de SHOULDERS vers MARGIN_TOP à somme constante.
//
// Les deux réglages se lisent ainsi : la somme fait la taille, le partage
// fait le cadrage. Les confondre mène à corriger l'un en cassant l'autre —
// ça m'est arrivé deux fois, dans les deux sens.

/** Air au-dessus du crâne. */
const MARGIN_TOP = 5
/** Hauteur de torse montrée sous le menton. La réduire remonte la coupe :
 *  c'est ce qui donne une silhouette « tête et amorce d'épaules » plutôt
 *  qu'un bloc sombre qui descend jusqu'au texte. */
const SHOULDERS = 5.5
/** Largeur à garder visible : le torse fait 8, les bras portent l'ensemble à
 *  16. Un peu plus pour ne pas les raser. */
const BUST_WIDTH = 17.5

/**
 * Rotation de la tête seule, en radians (~17°).
 *
 * C'est la tête qui pivote, pas le modèle entier : les épaules restent
 * carrées face à nous et le regard part de côté. Tourner tout le buste
 * donnerait un personnage de profil. `resetJoints` ne touche pas à la
 * rotation de la tête, elle survit donc au chargement d'un skin.
 */
const HEAD_TURN = 0.12

/**
 * Rotation du buste entier, en radians (~12°).
 *
 * La tête seule tournait, épaules carrées : de face, un buste carré ne montre
 * qu'une seule facette et se lit comme une image plate. En tournant
 * l'ensemble, la tranche du torse et celle d'un bras entrent dans le champ —
 * c'est ce qui donne à voir que le rendu est en volume.
 *
 * La tête garde sa propre rotation par-dessus, plus faible : elle regarde
 * donc un peu plus de côté que les épaules, comme quelqu'un qui tourne la
 * tête sans bouger le corps.
 */
const BODY_TURN = 0.22

/**
 * Étirement horizontal du rendu.
 *
 * Obtenu en mentant à la caméra sur le rapport de la toile : si elle la croit
 * plus étroite qu'elle n'est, la projection écarte l'image d'autant. C'est
 * préférable à un `scaleX` en CSS, qui rééchantillonnerait une image déjà
 * dessinée — ici le rendu reste net, il est simplement projeté autrement.
 *
 * 1,08 : assez pour que la tête et les épaules perdent leur carré parfait et
 * paraissent un peu plus larges, trop peu pour qu'on y voie une déformation.
 */
const STRETCH = 1.08

/**
 * Rugosité appliquée aux matériaux du skin.
 *
 * `skinview3d` les crée en `MeshStandardMaterial` sans préciser `roughness`,
 * donc three.js la laisse à 1 : surface parfaitement mate, aucun reflet. Sur
 * un skin sombre, cela donne du noir sur du noir — le diffus d'un albédo nul
 * est nul quelle que soit la lumière, et il n'y a rien d'autre pour le
 * rattraper.
 *
 * En abaissant la rugosité, le reflet spéculaire diélectrique apparaît. Il ne
 * dépend PAS de la couleur de base : même un noir absolu accroche alors la
 * lumière et les faces se distinguent. C'est le seul levier qui agisse sur un
 * skin noir.
 */
const ROUGHNESS = 0.55

/**
 * Ambiante de la bibliothèque, abaissée.
 *
 * Elle vaut 3 par défaut et éclaire toutes les faces à l'identique. C'est
 * elle qui saturait les zones claires une fois les lampes d'appoint
 * ajoutées : un blanc déjà proche du maximum ne peut que déborder quand on
 * empile de la lumière plate par-dessus.
 *
 * L'abaisser ne renoircit pas le skin sombre pour autant : sur un albédo nul,
 * l'ambiante n'apportait rien de toute façon. Ce qui rend le sombre lisible,
 * c'est le spéculaire (voir ROUGHNESS) et la séparation du fond (voir le
 * halo). L'ambiante, elle, ne servait qu'aux zones claires — qu'elle brûlait.
 */
const AMBIENT = 1.9

/**
 * Éclairage d'appoint, en plus de l'ambiante de la bibliothèque.
 *
 * L'ambiante seule éclaire toutes les faces pareil : le cube reste plat. Deux
 * sources placées de part et d'autre creusent l'écart entre la face de devant
 * et la face de côté rendue visible par la rotation de la tête — c'est cet
 * écart qui fait lire le volume.
 *
 * `decay: 0` supprime l'atténuation avec la distance : la lampe se comporte
 * comme une source lointaine, et son intensité ne dépend plus d'un calcul de
 * distance qu'il faudrait réajuster à chaque changement de cadrage.
 */
// Intensités volontairement faibles : leur rôle est de CREUSER l'écart entre
// les faces, pas d'ajouter de la luminosité. La somme des trois reste sous
// l'ambiante, sinon on retombe sur des blancs brûlés.
const LIGHTS = [
  /** Clé : en haut à gauche, devant. Creuse la face de côté. */
  { position: [-14, 12, 12], intensity: 0.6, color: 0xfdfbff },
  /** Appoint : en bas à droite, devant. Évite que l'ombre portée ne bouche
   *  complètement le côté opposé. */
  { position: [12, -2, 10], intensity: 0.3, color: 0x9fb4ff },
  /** Rasante : très haut, presque au-dessus. Pose une arête claire sur le
   *  sommet du crâne, qui détache la silhouette du ciel de la bannière. */
  { position: [2, 20, 4], intensity: 0.45, color: 0xc9b8ff },
] as const

/**
 * Place la caméra pour que la tête et les épaules remplissent le cadre.
 *
 * ── Pourquoi on mesure au lieu de régler ──────────────────────────────────
 * Les tentatives précédentes posaient une hauteur en dur, déduite d'une
 * lecture du code de la bibliothèque. Chaque lecture semblait juste et chaque
 * résultat tombait à côté. On ne suppose donc plus rien : la matrice monde de
 * la tête donne sa position réelle, et tout le reste s'en déduit. Si une
 * version suivante déplace le modèle, ce code suit sans qu'on y touche.
 *
 * `matrixWorld.elements[13]` est la translation Y de la matrice — les
 * éléments 12, 13 et 14 d'une matrice 4×4 en colonnes majeures. C'est le seul
 * accès à la position réelle : `innerLayer` est typé `Object3D`, sa géométrie
 * n'est pas exposée.
 */
/**
 * Taille de mise en page d'un élément, **sans les transformations CSS**.
 *
 * `getBoundingClientRect` inclut les transformations. Or le buste entre en
 * scène avec une animation d'échelle : mesuré pendant ce mouvement, il
 * renvoyait une taille réduite, dont le rendu héritait.
 *
 * Et rien ne venait la corriger ensuite : un `ResizeObserver` se déclenche
 * sur la boîte de MISE EN PAGE, que les transformations ne changent pas. La
 * fin de l'animation ne produisait donc aucune nouvelle mesure, et le canevas
 * restait à l'échelle où il avait été surpris. D'où une taille de skin qui
 * dépendait de l'instant où l'observateur s'était déclenché.
 *
 * `offsetWidth` et `offsetHeight` ignorent les transformations : ils donnent
 * la même valeur du début à la fin de l'animation.
 */
function layoutSize(box: HTMLElement) {
  return { width: box.offsetWidth, height: box.offsetHeight }
}

/**
 * Taille du canevas : on la laisse entièrement à three.js.
 *
 * `setSize` écrit `width` et `height` en pixels dans le style inline, en plus
 * du tampon de rendu. Forcer ensuite une taille relative par-dessus semblait
 * malin — ça garantissait que le canevas remplisse sa zone — mais dès que les
 * deux ne coïncident plus, l'image est ÉTIRÉE : le tampon est dessiné à un
 * rapport et affiché à un autre. Le sommet du crâne s'en trouvait rogné par
 * la même occasion.
 *
 * Les deux restent donc toujours d'accord, et c'est la mise en page autour
 * qui absorbe un écart passager : le canevas est centré en bas de sa zone,
 * jamais posé en haut à gauche.
 */
/**
 * Rend le skin lisible : matériaux moins mats, puis lampes d'appoint.
 *
 * Appelé après chaque chargement de skin — la bibliothèque reconstruit ses
 * matériaux à ce moment-là, un réglage posé avant serait perdu.
 */
function light(viewer: SkinViewer) {
  // Les matériaux sont privés dans les types de la bibliothèque : on passe
  // par l'arbre de scène, qui lui est public. Chaque maillage porte le sien.
  viewer.playerObject.traverse((node) => {
    const material = (node as { material?: { roughness?: number } }).material
    if (material && typeof material.roughness === 'number') {
      material.roughness = ROUGHNESS
    }
  })
}

/** Règle l'éclairage. Une seule fois, au montage. */
function addLights(viewer: SkinViewer) {
  viewer.globalLight.intensity = AMBIENT
  for (const spec of LIGHTS) {
    // Clonée depuis la lampe de la caméra plutôt que construite : cela évite
    // d'importer `three` directement, qui n'est ici qu'une dépendance
    // indirecte de `skinview3d` et dont on ne veut pas fixer la version.
    const lamp = viewer.cameraLight.clone()
    lamp.decay = 0
    lamp.intensity = spec.intensity
    lamp.color.set(spec.color)
    lamp.position.set(spec.position[0], spec.position[1], spec.position[2])
    viewer.scene.add(lamp)
  }
}

function frame(viewer: SkinViewer, box: HTMLElement) {
  // Le rapport réel est relu sur la boîte, pas sur `camera.aspect` : celui-ci
  // porte déjà l'étirement dès le deuxième appel, et le cumulerait à chaque
  // fois. Repartir de la mesure rend la fonction rejouable sans dériver.
  const { width, height } = layoutSize(box)
  if (width <= 0 || height <= 0) return
  const aspect = width / height / STRETCH
  viewer.camera.aspect = aspect

  const head = viewer.playerObject.skin.head
  // Sans ça, la matrice date d'avant les réglages posés plus haut.
  viewer.playerObject.updateMatrixWorld(true)

  const headCentreY = head.innerLayer.matrixWorld.elements[13]
  // La boîte de la tête fait 8 de côté, sa couche externe 9 : une demi-hauteur
  // de 4,5 couvre les deux. C'est la seule dimension supposée, et c'est une
  // dimension — pas une position, donc pas ce qui nous a piégés.
  const top = headCentreY + 4.5 + MARGIN_TOP
  const bottom = headCentreY - 4.5 - SHOULDERS
  const centreY = (top + bottom) / 2

  const tanHalf = Math.tan((BUST_FOV / 2) * (Math.PI / 180))
  // Le champ de vision est VERTICAL : la largeur visible dépend du rapport de
  // la toile. On prend la distance qui satisfait les deux axes, sinon les
  // épaules sortent par les côtés sur une toile étroite.
  const forHeight = (top - bottom) / 2 / tanHalf
  const forWidth = BUST_WIDTH / 2 / (tanHalf * aspect)

  viewer.camera.position.set(0, centreY, Math.max(forHeight, forWidth))
  viewer.controls.target.set(0, centreY, 0)
  viewer.camera.lookAt(viewer.controls.target)
  viewer.camera.updateProjectionMatrix()
  viewer.controls.update()
}

// ── Zone cliquable ─────────────────────────────────────────────────────────
//
// Déduite des mêmes constantes que le cadrage, donc toujours d'accord avec
// lui. Le canevas occupe toute la bannière — il est transparent partout sauf
// sur le modèle — donc l'envelopper d'un bouton rendait la bannière entière
// cliquable, y compris le ciel.
//
// La boîte part du sommet du crâne et descend jusqu'au bas : c'est la part de
// la hauteur visible qu'occupe le buste.
const TOTAL_UNITS = 9 + MARGIN_TOP + SHOULDERS
const HIT_TOP = `${(MARGIN_TOP / TOTAL_UNITS) * 100}%`
const HIT_HEIGHT = `${((9 + SHOULDERS) / TOTAL_UNITS) * 100}%`
/** Largeur du buste, bras compris. Exprimée en rapport avec sa hauteur : le
 *  navigateur en déduit la largeur, sans qu'on ait à connaître la taille du
 *  canevas en pixels. L'étirement horizontal est répercuté ici, sinon la
 *  boîte serait plus étroite que le rendu. */
const HIT_RATIO = `${16 * STRETCH} / ${9 + SHOULDERS}`

export function SkinBust({ uuid, localSkin, className = '', onClick, title }: {
  /** UUID du compte, pour aller chercher sa texture. */
  uuid: string | null
  /** PNG du skin d'un compte hors ligne, quand il en a défini un. */
  localSkin: string | null
  className?: string
  /** Clic sur le buste — et sur lui seul, pas sur la bannière. */
  onClick: () => void
  title: string
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const boxRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<SkinViewer | null>(null)

  // Le skin local l'emporte : un compte hors ligne n'a rien à attendre du
  // service, son UUID étant inventé — il y récupérerait un Steve par défaut.
  const source = localSkin ?? (uuid ? `https://mc-heads.net/skin/${uuid}` : null)

  const reframe = useCallback(() => {
    if (viewerRef.current && boxRef.current) frame(viewerRef.current, boxRef.current)
  }, [])

  useEffect(() => {
    if (!canvasRef.current || !boxRef.current) return
    const box = boxRef.current
    const { width, height } = layoutSize(box)

    // ── Fond transparent ──────────────────────────────────────────────────
    // On ne peut pas passer d'options de rendu à `skinview3d`, mais on peut
    // créer le contexte avant lui : la spécification WebGL impose que les
    // appels suivants à `getContext` du même type rendent le contexte déjà
    // créé, en ignorant leurs attributs. Les nôtres l'emportent donc.
    canvasRef.current.getContext('webgl2', { alpha: true, premultipliedAlpha: true })
      ?? canvasRef.current.getContext('webgl', { alpha: true, premultipliedAlpha: true })

    const viewer = new SkinViewer({
      canvas: canvasRef.current,
      width: width || 220,
      height: height || 220,
    })
    viewer.background = null
    // Aucun mouvement : le buste reste face à nous, toujours au même endroit.
    // `autoRotate` ne tourne pas la caméra mais le modèle lui-même
    // (`playerWrapper.rotation.y`), donc le laisser actif faisait dériver le
    // cadrage en continu — un repère qui bouge n'est plus un repère.
    viewer.autoRotate = false
    viewer.fov = BUST_FOV
    viewer.playerWrapper.rotation.y = BODY_TURN
    viewer.playerObject.skin.head.rotation.y = HEAD_TURN
    // La bannière n'est pas une visionneuse : un clic dessus ouvre les
    // comptes, il ne fait pas tourner le modèle.
    viewer.controls.enabled = false
    viewer.controls.enableRotate = false
    viewer.controls.enableZoom = false
    viewer.controls.enablePan = false
    addLights(viewer)
    viewerRef.current = viewer
    // Le premier cadrage n'a lieu que si la zone est déjà mesurable : sinon
    // `camera.aspect` vaut n'importe quoi et le cadrage serait faux le temps
    // d'une image. L'observateur ci-dessous s'en charge dès que la taille est
    // connue.
    if (width > 0 && height > 0) frame(viewer, box)

    const ro = new ResizeObserver(() => {
      const r = layoutSize(box)
      if (r.width > 0 && r.height > 0) {
        viewer.setSize(r.width, r.height)
        // `setSize` a remis `camera.aspect` au rapport réel : il faut rejouer
        // le cadrage pour y réappliquer l'étirement, en plus de recalculer la
        // largeur visible.
        frame(viewer, box)
      }
    })
    ro.observe(box)

    // Fenêtre masquée : plus personne ne regarde, la boucle de rendu n'a
    // aucune raison de continuer à tourner sur l'écran le plus ouvert du
    // launcher.
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
    if (!source) {
      viewer.loadSkin(null)
      return
    }
    // `auto-detect` distingue les modèles fins (Alex) des classiques d'après
    // la texture. Un skin injoignable laisse le modèle précédent plutôt que
    // de vider la bannière. On recadre après coup : un modèle fin n'a pas
    // tout à fait les mêmes dimensions.
    const after = () => { light(viewer); reframe() }
    const done = viewer.loadSkin(source, { model: 'auto-detect' }) as Promise<void> | void
    if (done && typeof done.then === 'function') done.then(after).catch(() => {})
    else after()
  }, [source, reframe])

  // Centré en bas plutôt qu'en flux normal : le canevas porte sa propre
  // taille en pixels, posée par three.js, et celle-ci peut retarder d'une
  // image sur celle de la zone. Centré, ce retard se voit à peine ; en flux
  // normal il collait le rendu en haut à gauche. Le bas sert d'ancre pour que
  // la coupe des épaules reste plaquée au bord de la bannière.
  return (
    <div ref={boxRef} className={`relative flex items-end justify-center overflow-hidden ${className}`}>
      {/* Lueur derrière le buste — elle éclaircit le ciel juste là où la
          silhouette se découpe. Aucune lampe ne peut faire ça : le problème
          n'est pas que le skin soit sous-éclairé, c'est qu'il est noir sur du
          bleu nuit. Remonter le fond localement crée le contraste que la
          lumière ne peut pas créer. */}
      <div
        aria-hidden
        className="pointer-events-none absolute bottom-0 left-1/2 h-[85%] w-[52%] -translate-x-1/2 rounded-full bg-[radial-gradient(ellipse_at_50%_60%,rgba(120,105,225,0.42)_0%,rgba(80,70,170,0.18)_45%,transparent_72%)] blur-[2px]"
      />
      {/* Le bouton précède le canevas pour que `peer-hover` l'atteigne — la
          règle CSS ne porte que sur les frères SUIVANTS. Son `z-10` le
          maintient malgré tout au-dessus pour recevoir les clics. */}
      <button
        onClick={onClick}
        title={title}
        aria-label={title}
        style={{ top: HIT_TOP, height: HIT_HEIGHT, aspectRatio: HIT_RATIO }}
        className="peer pointer-events-auto absolute left-1/2 z-10 -translate-x-1/2"
      />

      {/* `drop-shadow` suit le canal alpha, donc le contour épouse la vraie
          silhouette du modèle — pas la boîte du canevas. Deux passes : une
          serrée qui souligne l'arête, une large qui pose le halo. */}
      <canvas
        ref={canvasRef}
        className="relative transition-[filter] duration-200 [filter:drop-shadow(0_0_2px_rgba(190,200,255,0.55))_drop-shadow(0_0_14px_rgba(110,95,230,0.5))] peer-hover:[filter:drop-shadow(0_0_3px_rgba(215,222,255,0.8))_drop-shadow(0_0_18px_rgba(140,125,255,0.65))]"
      />
    </div>
  )
}
