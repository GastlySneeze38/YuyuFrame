import {
  CrouchAnimation,
  FlyingAnimation,
  IdleAnimation,
  RunningAnimation,
  WalkingAnimation,
  WaveAnimation,
} from 'skinview3d'
import type { PlayerAnimation, SkinViewer } from 'skinview3d'

/**
 * Positions du personnage, partagées par l'écran Skins et l'éditeur.
 *
 * ── Pourquoi ici ──────────────────────────────────────────────────────────
 * Les deux écrans montrent le même personnage et proposent les mêmes poses.
 * Les écrire deux fois aurait garanti qu'elles finissent par diverger — c'est
 * le même raisonnement que pour la caméra du catalogue.
 *
 * ── Ce que la bibliothèque donne ──────────────────────────────────────────
 * `skinview3d` fournit les six, y compris l'accroupissement et le salut : rien
 * n'a eu à être écrit à la main. Une seule substitution par rapport à ce que
 * l'interface listait : **« assis » devient « accroupi »**, parce qu'un joueur
 * ne s'assoit pas dans Minecraft — s'accroupir est la vraie pose, et celle que
 * la bibliothèque sait rendre.
 *
 * ── Jouée ou tenue ────────────────────────────────────────────────────────
 * L'aperçu de l'écran Skins joue l'animation ; l'éditeur la **tient**, pour
 * qu'on puisse peindre sur un modèle immobile tout en voyant le skin dans la
 * pose. Tenir ne se fait pas avec `paused` — une animation en pause n'est
 * jamais appliquée, donc la pose n'apparaîtrait pas — mais avec une vitesse
 * quasi nulle, qui laisse `animate()` poser les membres sans avancer.
 *
 * Pas zéro pile : `CrouchAnimation` divise par sa vitesse en interne, et
 * l'infini qui en sortirait contaminerait des rotations. Un millionième
 * n'avance pas d'un pixel en une heure et reste un nombre.
 */
export const POSES = ['standing', 'walking', 'running', 'crouching', 'waving', 'flying'] as const

export type PoseId = (typeof POSES)[number]

interface Pose {
  create: () => PlayerAnimation
  /** Vitesse de lecture ; `0` = pose qui ne se joue jamais, même en aperçu. */
  speed: number
  /** Instant à figer quand la pose est tenue. */
  hold: number
}

const POSE_SPECS: Record<PoseId, Pose> = {
  // Respiration : la pose « neutre », déjà celle du repos à progression nulle.
  standing: { create: () => new IdleAnimation(), speed: 1, hold: 0 },
  // `t = progression × 8`, amplitude maximale à `t = π/2`.
  walking: { create: () => new WalkingAnimation(), speed: 0.55, hold: Math.PI / 16 },
  // `t = progression × 15 + π/2`. On fige à `t = 3π/4` : foulée large, et le
  // saut de la course repasse par sa hauteur neutre, sinon le personnage
  // resterait figé en l'air.
  running: { create: () => new RunningAnimation(), speed: 0.85, hold: Math.PI / 60 },
  // Accroupi plein à `floor(progression × 8) === 1`. Vitesse nulle : un
  // accroupissement qui se rejoue en boucle serait un tic, pas une pose.
  crouching: { create: () => new CrouchAnimation(), speed: 0, hold: 0.15 },
  // `t = progression × π`, bras le plus haut à `t = π/2`.
  waving: { create: () => new WaveAnimation('right'), speed: 1.3, hold: 0.5 },
  // Le redressement s'achève à progression 0,5 ; au-delà la pose ne bouge plus.
  flying: { create: () => new FlyingAnimation(), speed: 1, hold: 1 },
}

/** Assez lent pour être immobile, assez non nul pour ne pas diviser par zéro. */
const HOLD_SPEED = 1e-6

/**
 * Réglages d'articulations, en radians, comptés **depuis la pose**.
 *
 * ── Le piège, payé une fois ───────────────────────────────────────────────
 * La première version faisait `rotation += décalage` à chaque image, en
 * supposant que l'animation réassigne toutes les rotations. Elle ne réassigne
 * que celles qu'elle pilote : `IdleAnimation` ne touche ni la tête, ni le
 * buste, ni les jambes. Rien ne remettait donc la valeur de base, et le
 * décalage s'additionnait soixante fois par seconde — le personnage tournait
 * sans fin.
 *
 * La pose de l'éditeur étant **tenue**, donc immobile, sa sortie est
 * constante : on la relève une fois, à la première image, et on écrit ensuite
 * `base + décalage`. Exact, et sans accumulation possible.
 *
 * ── Amplitudes ────────────────────────────────────────────────────────────
 * Elles suivent le jeu plutôt qu'un confort d'interface : voir `JOINT_LIMITS`.
 */
export interface Joints {
  /** Tête : pivot gauche/droite. */
  headTurn: number
  /** Tête : inclinaison haut/bas. */
  headTilt: number
  /** Buste : pivot gauche/droite. */
  bodyTurn: number
  /** Jambes : écart, une en avant et l'autre en arrière. */
  legsSpread: number
}

export const NEUTRAL_JOINTS: Joints = { headTurn: 0, headTilt: 0, bodyTurn: 0, legsSpread: 0 }

/**
 * Amplitudes en **degrés**, de part et d'autre de zéro, calées sur ce que le
 * jeu permet et non sur ce qui tiendrait dans un curseur.
 *
 * - `headTilt` : ±90, la limite exacte du regard vers le haut et le bas.
 * - `headTurn` : ±50, l'écart que la tête peut prendre sur le buste — au-delà
 *   le corps suit, donc on ne verrait jamais plus en jeu.
 * - `bodyTurn` : ±180, le corps se tourne librement, rien ne le borne.
 * - `legsSpread` : ±80, l'amplitude de la foulée en course.
 */
export const JOINT_LIMITS: Record<keyof Joints, number> = {
  headTurn: 50,
  headTilt: 90,
  bodyTurn: 180,
  legsSpread: 80,
}

/**
 * Pose le personnage.
 *
 * `held` fige la pose au lieu de la jouer. L'ordre compte : le `setter`
 * d'animation du viewer remet la progression à zéro, donc l'instant à figer se
 * pose **après** l'affectation.
 *
 * `joints` est lu à chaque image plutôt que capturé : les réglages changent
 * pendant qu'on tire un curseur, et on ne va pas reconstruire l'animation à
 * chaque pixel de déplacement. `addAnimation` passe après `animate`, donc on
 * voit la pose déjà posée — c'est ce qui permet d'en relever la base.
 *
 * Les articulations supposent une pose **tenue** : c'est son immobilité qui
 * rend sa sortie constante, donc relevable une seule fois.
 */
export function applyPose(
  viewer: SkinViewer,
  id: PoseId,
  held: boolean,
  joints?: () => Joints,
): void {
  const spec = POSE_SPECS[id]
  const animation = spec.create()

  viewer.animation = animation

  const still = held || spec.speed === 0
  animation.progress = still ? spec.hold : 0
  animation.speed = still ? HOLD_SPEED : spec.speed

  if (!joints) return

  // Relevée à la première image, quand la pose vient d'être appliquée et que
  // les décalages n'ont encore rien touché.
  let base: { headY: number; headX: number; bodyY: number; legL: number; legR: number } | null = null

  animation.addAnimation((player) => {
    const { head, body, leftLeg, rightLeg } = player.skin
    if (base === null) {
      base = {
        headY: head.rotation.y,
        headX: head.rotation.x,
        bodyY: body.rotation.y,
        legL: leftLeg.rotation.x,
        legR: rightLeg.rotation.x,
      }
    }

    const { headTurn, headTilt, bodyTurn, legsSpread } = joints()
    head.rotation.y = base.headY + headTurn
    head.rotation.x = base.headX + headTilt
    body.rotation.y = base.bodyY + bodyTurn
    leftLeg.rotation.x = base.legL + legsSpread
    rightLeg.rotation.x = base.legR - legsSpread
  })
}
