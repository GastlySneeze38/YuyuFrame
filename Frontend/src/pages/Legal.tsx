import { useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import termsMd from '@/assets/legal/TERMS.md?raw'
import privacyMd from '@/assets/legal/PRIVACY.md?raw'
import licenseTxt from '@/assets/legal/LICENSE.txt?raw'
import { PageHeader } from '@/components/ui/PageHeader'
import { LegalMarkdown } from '@/components/legal/LegalMarkdown'
import { useT } from '@/i18n'

type TabId = 'conditions' | 'confidentialite' | 'licence'

// Contenu chargé DEPUIS les fichiers du dépôt public (src/assets/legal/,
// copiés de TERMS.md/PRIVACY.md/LICENSE à la racine de YuyuFrame-v2) — cette
// page ne fait qu'afficher, jamais de texte légal réécrit ici. Resynchroniser
// ces 3 fichiers manuellement si les originaux changent sur le dépôt public
// (pas de fetch réseau au runtime — un texte légal doit rester lisible hors
// ligne et figé à la version de l'app installée, pas dépendre d'un appel
// réseau qui pourrait échouer ou afficher une version différente de celle
// couverte par cette build).

export default function Legal() {
  const t = useT()
  const TABS: { id: TabId; label: string }[] = [
    { id: 'conditions', label: t('legal.termsTab') },
    { id: 'confidentialite', label: t('legal.privacyTab') },
    { id: 'licence', label: t('legal.licenceTab') },
  ]
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
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">{t('legal.title')}</h1>
      </PageHeader>

      {/* Tabs */}
      <div
        className="flex flex-shrink-0 items-center gap-1 px-6 pt-4 border-b border-[rgba(255,255,255,0.06)]"
      >
        {TABS.map((tb) => (
          <button
            key={tb.id}
            onClick={() => selectTab(tb.id)}
            className={`px-4 pb-3 transition-colors duration-150 text-[13px] font-semibold border-b-2 ${tab === tb.id ? 'text-white border-[#7c6ae8]' : 'text-white/40 border-transparent'}`}
          >
            {tb.label}
          </button>
        ))}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-6">
        <div className="mx-auto flex max-w-2xl flex-col gap-5">
          {tab === 'conditions' && <LegalMarkdown source={termsMd} />}
          {tab === 'confidentialite' && <LegalMarkdown source={privacyMd} />}
          {tab === 'licence' && <PlainText source={licenseTxt} />}
        </div>
      </div>
    </div>
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

