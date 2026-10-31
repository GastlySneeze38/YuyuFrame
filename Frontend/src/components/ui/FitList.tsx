import { useLayoutEffect, useRef, useState, type ReactNode } from 'react'

export interface FitItem {
  key: string
  node: ReactNode
  /** Le texte de l'élément, pour la bulle du « +N ». */
  label: string
}

/**
 * Une rangée d'éléments qui passe à la ligne (pastilles, boutons) et qui
 * n'en montre **que ce qui tient dans son cadre** : le reste est résumé par
 * « +N », dont la bulle donne la liste. Rien ne déborde, rien ne défile.
 *
 * Le cadre doit avoir une hauteur donnée par la mise en page (`flex-1
 * min-h-0` dans une colonne, par exemple) et non par son contenu, sinon il
 * grandirait pour tout montrer.
 *
 * Mesure en deux temps, avant que l'écran soit dessiné (`useLayoutEffect`) :
 * tout est posé, on compte ce qui tombe dans la hauteur, puis on garde ce
 * nombre moins un pour laisser sa place au « +N ». Un changement de taille
 * du cadre relance la mesure.
 */
export function FitList({ items, renderMore, className = '' }: { items: FitItem[]; renderMore: (count: number, title: string) => ReactNode; className?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const [count, setCount] = useState(items.length)
  const [measuring, setMeasuring] = useState(true)
  const signature = items.map((i) => i.key).join('|')

  // Nouveaux éléments : tout remesurer.
  useLayoutEffect(() => {
    setCount(items.length)
    setMeasuring(true)
  }, [signature])

  useLayoutEffect(() => {
    if (!measuring) return
    const box = ref.current
    if (!box) return
    const height = box.clientHeight
    const children = Array.from(box.children) as HTMLElement[]
    let fits = 0
    for (const child of children) {
      if (child.offsetTop + child.offsetHeight <= height + 1) fits++
      else break
    }
    setCount(fits >= items.length ? items.length : Math.max(0, fits - 1))
    setMeasuring(false)
  }, [measuring, signature])

  // Le cadre change de taille (fenêtre redimensionnée) : remesurer.
  useLayoutEffect(() => {
    const box = ref.current
    if (!box) return
    let last = `${box.clientWidth}x${box.clientHeight}`
    const observer = new ResizeObserver(() => {
      const now = `${box.clientWidth}x${box.clientHeight}`
      if (now === last) return
      last = now
      setCount(items.length)
      setMeasuring(true)
    })
    observer.observe(box)
    return () => observer.disconnect()
  }, [signature])

  const shown = measuring ? items : items.slice(0, count)
  const rest = measuring ? [] : items.slice(count)

  return (
    <div ref={ref} className={`relative flex min-h-0 flex-wrap content-start gap-1.5 overflow-hidden ${className}`}>
      {shown.map((i) => (
        <span key={i.key} className="flex">
          {i.node}
        </span>
      ))}
      {rest.length > 0 && renderMore(rest.length, rest.map((i) => i.label).join(', '))}
    </div>
  )
}
