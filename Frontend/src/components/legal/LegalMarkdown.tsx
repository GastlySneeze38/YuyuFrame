/**
 * Rendu markdown MINIMAL fait main (pas de dépendance ajoutée pour 2
 * fichiers courts) — couvre juste ce que TERMS.md/PRIVACY.md utilisent
 * réellement : titres # ## ###, listes à puces "- ", et paragraphes séparés
 * par une ligne vide. Gère aussi le **gras** inline (utilisé abondamment
 * dans PRIVACY.md pour les amorces de paragraphe, ex: "**Account data.**
 * When you...") — pas d'italique/liens, non utilisés dans ces documents.
 *
 * Partagé par la page Mentions légales et par la fenêtre ouverte depuis le
 * formulaire d'inscription (`PrivacyModal`) : le texte qu'on accepte doit
 * être rendu exactement comme celui qu'on relit ensuite.
 */
export function LegalMarkdown({ source }: { source: string }) {
  const blocks = source.trim().split(/\n\s*\n/)
  return (
    <>
      {blocks.map((block, i) => {
        const lines = block.split('\n')
        const first = lines[0]

        if (first.startsWith('### ')) return <h4 key={i} className={h4Classes}>{inlineBold(first.slice(4))}</h4>
        if (first.startsWith('## ')) return <h3 key={i} className={h3Classes}>{inlineBold(first.slice(3))}</h3>
        if (first.startsWith('# ')) return <h2 key={i} className={h2Classes}>{inlineBold(first.slice(2))}</h2>
        if (first.startsWith('**Dernière mise à jour') || first.startsWith('**Last updated')) {
          return <p key={i} className={metaClasses}>{first.replace(/\*\*/g, '')}</p>
        }
        if (lines.every((l) => l.trim().startsWith('- '))) {
          return (
            <ul key={i} className="text-[12.5px] leading-[1.7] text-white/50 pl-[18px] list-disc flex flex-col gap-1 max-sm:text-[15px] max-sm:leading-[1.6] max-sm:text-white/70">
              {lines.map((l, j) => <li key={j}>{inlineBold(l.trim().slice(2))}</li>)}
            </ul>
          )
        }
        return <p key={i} className={pClasses}>{inlineBold(lines.join(' '))}</p>
      })}
    </>
  )
}

/** Découpe "texte **gras** texte" en fragments, le "**" wrappé en `<strong>` — seul style inline utilisé dans ces documents. */
function inlineBold(text: string): React.ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g)
  return parts.map((part, i) =>
    part.startsWith('**') && part.endsWith('**')
      ? <strong key={i} className="text-white/85 font-bold">{part.slice(2, -2)}</strong>
      : part
  )
}

// Téléphone : texte courant à 15 px et plus contrasté, titres au-dessus,
// mention de date à 13 px — le plancher de lecture.
const h2Classes = 'text-[20px] font-extrabold text-white mt-1 max-sm:text-[22px]'
const h3Classes = 'text-[14px] font-bold text-white/85 mt-1.5 max-sm:text-[17px]'
const h4Classes = 'text-[13px] font-bold text-white/70 max-sm:text-[15px]'
const metaClasses = 'text-[11px] text-white/25 max-sm:text-[13px] max-sm:text-white/50'
const pClasses = 'text-[12.5px] leading-[1.7] text-white/50 max-sm:text-[15px] max-sm:leading-[1.6] max-sm:text-white/70'
