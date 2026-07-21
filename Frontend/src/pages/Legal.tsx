import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import termsMd from '@/assets/legal/TERMS.md?raw'
import privacyMd from '@/assets/legal/PRIVACY.md?raw'
import licenseTxt from '@/assets/legal/LICENSE.txt?raw'
import { PageHeader } from '@/components/ui/PageHeader'

type TabId = 'conditions' | 'confidentialite' | 'licence'

const TABS: { id: TabId; label: string }[] = [
  { id: 'conditions', label: "Conditions d'utilisation" },
  { id: 'confidentialite', label: 'Confidentialité' },
  { id: 'licence', label: 'Licence' },
]

// Contenu chargé DEPUIS les fichiers du dépôt public (src/assets/legal/,
// copiés de TERMS.md/PRIVACY.md/LICENSE à la racine de YuyuFrame-v2) — cette
// page ne fait qu'afficher, jamais de texte légal réécrit ici. Resynchroniser
// ces 3 fichiers manuellement si les originaux changent sur le dépôt public
// (pas de fetch réseau au runtime — un texte légal doit rester lisible hors
// ligne et figé à la version de l'app installée, pas dépendre d'un appel
// réseau qui pourrait échouer ou afficher une version différente de celle
// couverte par cette build).

export default function Legal() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = (searchParams.get('tab') as TabId) || 'conditions'
  const [tab, setTab] = useState<TabId>(TABS.some((t) => t.id === initialTab) ? initialTab : 'conditions')

  function selectTab(id: TabId) {
    setTab(id)
    setSearchParams({ tab: id }, { replace: true })
  }

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D] text-white">

      <PageHeader backTo={-1}>
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">Informations légales</h1>
      </PageHeader>

      {/* Tabs */}
      <div
        className="flex flex-shrink-0 items-center gap-1 px-6 pt-4 border-b border-[rgba(255,255,255,0.06)]"
      >
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => selectTab(t.id)}
            className={`px-4 pb-3 transition-colors duration-150 text-[13px] font-semibold border-b-2 ${tab === t.id ? 'text-white border-[#7c6ae8]' : 'text-white/40 border-transparent'}`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-6">
        <div className="mx-auto flex max-w-2xl flex-col gap-5">
          {tab === 'conditions' && <Markdown source={termsMd} />}
          {tab === 'confidentialite' && <Markdown source={privacyMd} />}
          {tab === 'licence' && <PlainText source={licenseTxt} />}
        </div>
      </div>
    </div>
  )
}

/**
 * Rendu markdown MINIMAL fait main (pas de dépendance ajoutée pour 2
 * fichiers courts) — couvre juste ce que TERMS.md/PRIVACY.md utilisent
 * réellement : titres # ## ###, listes à puces "- ", et paragraphes séparés
 * par une ligne vide. Gère aussi le **gras** inline (utilisé abondamment
 * dans PRIVACY.md pour les amorces de paragraphe, ex: "**Account data.**
 * When you...") — pas d'italique/liens, non utilisés dans ces documents.
 */
function Markdown({ source }: { source: string }) {
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
            <ul key={i} className="text-[12.5px] leading-[1.7] text-white/50 pl-[18px] list-disc flex flex-col gap-1">
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

/** Texte préformaté (LICENSE) — une licence se lit toujours telle quelle, jamais reformatée (convention universelle, voir GitHub/gnu.org). */
function PlainText({ source }: { source: string }) {
  return (
    <pre className="font-[ui-monospace,SFMono-Regular,Menlo,monospace] text-[11px] leading-[1.6] text-white/50 whitespace-pre-wrap [word-break:break-word]">
      {source}
    </pre>
  )
}

const h2Classes = 'text-[20px] font-extrabold text-white mt-1'
const h3Classes = 'text-[14px] font-bold text-white/85 mt-1.5'
const h4Classes = 'text-[13px] font-bold text-white/70'
const metaClasses = 'text-[11px] text-white/25'
const pClasses = 'text-[12.5px] leading-[1.7] text-white/50'
