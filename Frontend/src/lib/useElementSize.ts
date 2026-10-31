import { useCallback, useLayoutEffect, useState } from 'react'

/**
 * La taille d'un élément, suivie à chaque redimensionnement. Pour les écrans
 * dont les morceaux se dimensionnent d'après la place qu'ils ont VRAIMENT
 * (une image dont la taille est un nombre, une frise qui doit tenir sur une
 * ligne) — là où une classe CSS ne suffit pas.
 *
 * Le `ref` rendu est une fonction : l'élément peut n'apparaître qu'après le
 * premier rendu (données chargées ensuite), il est alors mesuré à ce moment.
 * `{ width: 0, height: 0 }` avant la première mesure.
 */
export function useElementSize<T extends HTMLElement>() {
  const [element, setElement] = useState<T | null>(null)
  const ref = useCallback((el: T | null) => setElement(el), [])
  const [size, setSize] = useState({ width: 0, height: 0 })
  useLayoutEffect(() => {
    if (!element) return
    const measure = () => {
      const width = element.clientWidth
      const height = element.clientHeight
      setSize((s) => (s.width === width && s.height === height ? s : { width, height }))
    }
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [element])
  return [ref, size] as const
}

/** `value` borné à [min, max]. */
export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}
