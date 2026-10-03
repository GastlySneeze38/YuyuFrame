import type { Instance } from '@/types'

/**
 * L'icône d'une instance, partout pareille.
 *
 * Elle était écrite en dur (un 🧱) dans la carte de la liste ; maintenant
 * qu'elle se choisit, elle a deux états et plusieurs tailles, donc un seul
 * composant — sinon le repli par défaut finirait par différer d'un écran à
 * l'autre, et c'est précisément le repli qu'on voit le plus souvent.
 *
 * `icon` est une data URI complète (voir `instances/icon.rs`), donc
 * rien à charger : elle arrive avec la liste des instances.
 */
export function InstanceIcon({
  instance,
  size,
  className = '',
}: {
  instance: Pick<Instance, 'icon'>
  size: number
  className?: string
}) {
  return (
    <span
      style={{ width: size, height: size, fontSize: Math.round(size * 0.42) }}
      className={`flex shrink-0 items-center justify-center overflow-hidden rounded-xl bg-surface-2 ${className}`}
    >
      {instance.icon ? (
        // `object-cover` : une icône n'est pas toujours carrée, et la déformer
        // serait pire que la rogner.
        <img src={instance.icon} alt="" className="h-full w-full object-cover" draggable={false} />
      ) : (
        '🧱'
      )}
    </span>
  )
}
