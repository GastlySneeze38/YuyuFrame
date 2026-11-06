import { useCallback, useState } from 'react'

/**
 * Image servie par un site distant — icônes et bannières Modrinth et
 * CurseForge, têtes mc-heads (2026-11-06, audit des chargements réseau).
 *
 * - `loading="lazy"` : une image hors de l'écran n'est demandée que quand on
 *   s'en approche (une page de résultats en demandait vingt d'un coup).
 *   `eager` pour ce qui est visible d'emblée (en-tête d'une fiche).
 * - `decoding="async"` : le décodage ne bloque pas l'affichage du reste.
 * - Fondu à l'arrivée, sauf si l'image était déjà en cache (elle apparaît
 *   alors tout de suite, sans clignoter).
 * - Une adresse en échec n'est pas redemandée de la session, et l'image
 *   disparaît au lieu d'afficher l'icône cassée du navigateur : le cadre du
 *   parent (fond, icône de repli) reste visible.
 *
 * Le cache HTTP de WebView2 garde ces images d'un lancement à l'autre, selon
 * les en-têtes du site : rien à faire de plus ici.
 */
const failed = new Set<string>()

export function RemoteImage({ src, alt = '', className = '', eager = false, draggable = false }: {
  src: string | null | undefined
  alt?: string
  className?: string
  eager?: boolean
  draggable?: boolean
}) {
  const [, setBroken] = useState(false)
  // `complete` au montage = déjà en cache : pas de fondu.
  const ref = useCallback((img: HTMLImageElement | null) => {
    if (img?.complete && img.naturalWidth > 0) img.dataset.loaded = ''
  }, [])

  if (!src || failed.has(src)) return null
  return (
    <img
      ref={ref}
      src={src}
      alt={alt}
      loading={eager ? 'eager' : 'lazy'}
      decoding="async"
      draggable={draggable}
      onLoad={(e) => {
        e.currentTarget.dataset.loaded = ''
      }}
      onError={() => {
        failed.add(src)
        setBroken(true)
      }}
      className={`img-fade ${className}`}
    />
  )
}
