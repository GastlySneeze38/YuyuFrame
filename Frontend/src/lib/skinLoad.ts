import type { SkinViewer } from 'skinview3d'

/**
 * Charger un skin dans un visualiseur, **le dernier demandé l'emportant**.
 *
 * `viewer.loadSkin` ne s'annule pas : deux chargements lancés coup sur coup
 * se terminent dans l'ordre où leurs images arrivent, pas dans celui où on
 * les a demandés. Or les écrans commencent par le repli — l'apparence par
 * défaut servie par un service d'avatars, donc par le réseau — puis passent
 * au skin enregistré, un data URI, qui arrive tout de suite. Le repli, plus
 * lent, finissait après et recouvrait le bon skin : un compte hors ligne se
 * retrouvait en Steve, souvent mais pas toujours.
 *
 * Ici, un chargement qui se termine alors qu'un autre a été demandé depuis
 * relance ce dernier. Les écrans évitent en plus de demander le repli tant
 * qu'ils ne savent pas s'il y a un skin enregistré ; ceci est le filet.
 */

type Image = Parameters<SkinViewer['loadSkin']>[0]
type Options = Parameters<SkinViewer['loadSkin']>[1]

/** `source` nul : retirer le skin (`loadSkin(null)`, immédiat). */
interface Request {
  source: Image | null
  options: Options
}

const latest = new WeakMap<SkinViewer, Request>()

function load(viewer: SkinViewer, { source, options }: Request): Promise<void> {
  if (source === null) {
    viewer.loadSkin(null)
    return Promise.resolve()
  }
  return Promise.resolve(viewer.loadSkin(source, options))
}

export function loadLatestSkin(viewer: SkinViewer, source: Image | null, options?: Options): Promise<void> {
  const request: Request = { source, options }
  latest.set(viewer, request)
  return load(viewer, request).then(async () => {
    const wanted = latest.get(viewer)
    if (wanted && wanted !== request) await load(viewer, wanted)
  })
}
