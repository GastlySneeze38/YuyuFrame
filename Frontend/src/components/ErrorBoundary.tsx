import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'

/**
 * Filet sous les écrans.
 *
 * Sans elle, une exception levée pendant un rendu démonte tout l'arbre React
 * et laisse une fenêtre entièrement vide, sans le moindre indice : c'est
 * exactement ce qu'on voyait en changeant vite de page. Ici l'erreur est
 * montrée, recopiable, et l'écran repart sans redémarrer le launcher — une
 * partie en cours n'a pas à payer pour un bug d'interface.
 *
 * `resetKey` (le chemin de la page) remet le filet à zéro à la navigation
 * suivante : une erreur sur un écran n'en condamne pas les autres.
 */
export class ErrorBoundary extends Component<
  { children: ReactNode; resetKey?: string },
  { error: Error | null }
> {
  state: { error: Error | null } = { error: null }

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // La console du launcher garde la pile complète : le message affiché
    // ci-dessous ne sert qu'à ne pas laisser la personne devant du noir.
    console.error('Erreur de rendu', error, info.componentStack)
  }

  componentDidUpdate(prev: { children: ReactNode; resetKey?: string }) {
    if (this.state.error && prev.resetKey !== this.props.resetKey) {
      this.setState({ error: null })
    }
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div className="flex h-full w-full flex-col items-center justify-center gap-3 px-8 text-center">
        <p className="text-[15px] font-semibold text-txt-primary">Cet écran n'a pas pu s'afficher</p>
        <p className="max-w-lg select-text break-words font-mono text-[11px] leading-relaxed text-txt-muted">
          {error.message}
        </p>
        <button
          onClick={() => this.setState({ error: null })}
          className="mt-1 h-9 rounded-xl border border-line bg-surface-2 px-4 text-[13px] font-semibold text-txt-primary transition-colors duration-150 hover:bg-surface-3"
        >
          Réessayer
        </button>
      </div>
    )
  }
}
