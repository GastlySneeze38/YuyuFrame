import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import termsMd from '@/assets/legal/TERMS.md?raw'
import privacyMd from '@/assets/legal/PRIVACY.md?raw'
import licenseTxt from '@/assets/legal/LICENSE.txt?raw'

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
  const navigate = useNavigate()
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab = (searchParams.get('tab') as TabId) || 'conditions'
  const [tab, setTab] = useState<TabId>(TABS.some((t) => t.id === initialTab) ? initialTab : 'conditions')

  function selectTab(id: TabId) {
    setTab(id)
    setSearchParams({ tab: id }, { replace: true })
  }

  return (
    <div className="flex h-full flex-col overflow-hidden" style={{ background: '#09090D', color: 'white' }}>

      {/* Header */}
      <div
        className="flex flex-shrink-0 items-center gap-3 px-6 py-3"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}
      >
        <button
          onClick={() => navigate(-1)}
          className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150"
          style={{ color: 'rgba(255,255,255,0.35)', background: 'rgba(255,255,255,0.04)' }}
          onMouseEnter={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.7)'; e.currentTarget.style.background = 'rgba(255,255,255,0.08)' }}
          onMouseLeave={(e) => { e.currentTarget.style.color = 'rgba(255,255,255,0.35)'; e.currentTarget.style.background = 'rgba(255,255,255,0.04)' }}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" style={{ width: 15, height: 15 }}>
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </button>
        <h1 className="font-black text-white" style={{ fontSize: 18, letterSpacing: '-0.01em' }}>Informations légales</h1>
      </div>

      {/* Tabs */}
      <div
        className="flex flex-shrink-0 items-center gap-1 px-6 pt-4"
        style={{ borderBottom: '1px solid rgba(255,255,255,0.06)' }}
      >
        {TABS.map((t) => (
          <button
            key={t.id}
            onClick={() => selectTab(t.id)}
            className="px-4 pb-3 transition-colors duration-150"
            style={{
              fontSize: 13,
              fontWeight: 600,
              color: tab === t.id ? 'white' : 'rgba(255,255,255,0.4)',
              borderBottom: tab === t.id ? '2px solid #7c6ae8' : '2px solid transparent',
            }}
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

        if (first.startsWith('### ')) return <h4 key={i} style={h4Style}>{inlineBold(first.slice(4))}</h4>
        if (first.startsWith('## ')) return <h3 key={i} style={h3Style}>{inlineBold(first.slice(3))}</h3>
        if (first.startsWith('# ')) return <h2 key={i} style={h2Style}>{inlineBold(first.slice(2))}</h2>
        if (first.startsWith('**Dernière mise à jour') || first.startsWith('**Last updated')) {
          return <p key={i} style={metaStyle}>{first.replace(/\*\*/g, '')}</p>
        }
        if (lines.every((l) => l.trim().startsWith('- '))) {
          return (
            <ul key={i} style={{ ...pStyle, paddingLeft: 18, listStyle: 'disc', display: 'flex', flexDirection: 'column', gap: 4 }}>
              {lines.map((l, j) => <li key={j}>{inlineBold(l.trim().slice(2))}</li>)}
            </ul>
          )
        }
        return <p key={i} style={pStyle}>{inlineBold(lines.join(' '))}</p>
      })}
    </>
  )
}

/** Découpe "texte **gras** texte" en fragments, le "**" wrappé en `<strong>` — seul style inline utilisé dans ces documents. */
function inlineBold(text: string): React.ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g)
  return parts.map((part, i) =>
    part.startsWith('**') && part.endsWith('**')
      ? <strong key={i} style={{ color: 'rgba(255,255,255,0.85)', fontWeight: 700 }}>{part.slice(2, -2)}</strong>
      : part
  )
}

/** Texte préformaté (LICENSE) — une licence se lit toujours telle quelle, jamais reformatée (convention universelle, voir GitHub/gnu.org). */
function PlainText({ source }: { source: string }) {
  return (
    <pre style={{
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 11,
      lineHeight: 1.6,
      color: 'rgba(255,255,255,0.5)',
      whiteSpace: 'pre-wrap',
      wordBreak: 'break-word',
    }}>
      {source}
    </pre>
  )
}

const h2Style: React.CSSProperties = { fontSize: 20, fontWeight: 800, color: 'white', marginTop: 4 }
const h3Style: React.CSSProperties = { fontSize: 14, fontWeight: 700, color: 'rgba(255,255,255,0.85)', marginTop: 6 }
const h4Style: React.CSSProperties = { fontSize: 13, fontWeight: 700, color: 'rgba(255,255,255,0.7)' }
const metaStyle: React.CSSProperties = { fontSize: 11, color: 'rgba(255,255,255,0.25)' }
const pStyle: React.CSSProperties = { fontSize: 12.5, lineHeight: 1.7, color: 'rgba(255,255,255,0.5)' }
