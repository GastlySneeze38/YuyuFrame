/**
 * Miniature du launcher pour les cartes de thème (Apparence).
 *
 * Elle n'a aucune couleur à elle : tout passe par les jetons (`bg-bg-primary`,
 * `bg-bg-card`, `bg-accent`…). Posée dans un parent `data-theme="…"`, elle
 * montre donc les vraies couleurs de ce thème, avec l'accent choisi — une
 * seule source, rien à recopier quand un thème change.
 */
export function ThemePreview() {
  return (
    <span aria-hidden className="flex h-[72px] w-full gap-1.5 bg-bg-primary p-2">
      {/* Barre latérale */}
      <span className="flex w-5 flex-col gap-1 rounded-md bg-bg-secondary p-1">
        <span className="h-1.5 rounded-sm bg-accent" />
        <span className="h-1.5 rounded-sm bg-surface-3" />
        <span className="h-1.5 rounded-sm bg-surface-3" />
      </span>
      {/* Contenu : deux cartes et un bouton */}
      <span className="flex flex-1 flex-col gap-1.5">
        <span className="flex flex-1 gap-1.5">
          <span className="flex-1 rounded-md bg-bg-card" />
          <span className="flex-1 rounded-md bg-bg-raised" />
        </span>
        <span className="h-2.5 w-1/2 rounded-sm bg-accent" />
      </span>
    </span>
  )
}
