import { useCallback, useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { InstanceServers } from '@/api/client'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { TopoBackdrop } from '@/components/competitive/TopoBackdrop'
import { MyServersView } from '@/components/servers/page/MyServersView'
import { CatalogView } from '@/components/servers/page/CatalogView'
import { AddServerView } from '@/components/servers/page/AddServerView'
import { SERVERS_TINT } from '@/components/servers/page/bits'
import { useStore } from '@/stores/useStore'
import { SNAP } from '@/lib/motion'
import { useT } from '@/i18n'
import { useIsMobile } from '@/mobile/useIsMobile'
import { ScreenHeader } from '@/mobile/ui/ScreenHeader'
import { Segmented } from '@/mobile/ui/kit'

/**
 * Écran Serveurs, ouvert depuis la rangée de serveurs de l'accueil.
 *
 * Trois onglets :
 * - **Mes serveurs** : la liste multijoueur de chaque instance (`servers.dat`,
 *   la même que dans le jeu) — rejoindre, épingler sur l'accueil, modifier ;
 * - **Catalogue** : les serveurs de Modrinth, avec leur modpack quand ils en
 *   ont un ;
 * - **Ajouter** : un serveur à la main, pingué pendant la frappe.
 *
 * `?tab=mine|catalog|add` ouvre un onglet précis (l'accueil sans serveur
 * mène au catalogue). Les onglets déjà ouverts restent montés : changer
 * d'onglet ne relance ni la recherche ni les pings.
 */

const TABS = ['mine', 'catalog', 'add'] as const
type Tab = (typeof TABS)[number]

export default function Servers() {
  const t = useT()
  const [params, setParams] = useSearchParams()
  const asked = params.get('tab') as Tab | null
  const [tab, setTab] = useState<Tab>(asked && TABS.includes(asked) ? asked : 'mine')
  const [visited, setVisited] = useState<Set<Tab>>(() => new Set([tab]))
  const selectedInstanceId = useStore((s) => s.selectedInstanceId)
  const mobile = useIsMobile()

  const [data, setData] = useState<InstanceServers[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [instanceId, setInstanceId] = useState<string | null>(selectedInstanceId)

  const reload = useCallback(() => {
    setLoading(true)
    api.servers
      .all()
      .then(setData)
      .catch(() => setData([]))
      .finally(() => setLoading(false))
  }, [])
  useEffect(reload, [reload])

  const go = (next: Tab) => {
    setTab(next)
    setVisited((v) => (v.has(next) ? v : new Set(v).add(next)))
    setParams(next === 'mine' ? {} : { tab: next }, { replace: true })
  }

  const views = (
    <>
      {visited.has('mine') && (
        <div className={tab === 'mine' ? 'flex min-h-0 min-w-0 flex-1' : 'hidden'}>
          <MyServersView
            data={data}
            loading={loading}
            instanceId={instanceId}
            onPick={setInstanceId}
            onReload={reload}
            onAdd={() => go('add')}
            onCatalog={() => go('catalog')}
          />
        </div>
      )}
      {visited.has('catalog') && (
        <div className={tab === 'catalog' ? 'flex min-h-0 min-w-0 flex-1' : 'hidden'}>
          <CatalogView onAdded={reload} />
        </div>
      )}
      {visited.has('add') && (
        <div className={tab === 'add' ? 'flex min-h-0 min-w-0 flex-1' : 'hidden'}>
          <AddServerView
            instanceId={instanceId}
            onCancel={() => go('mine')}
            onAdded={(id) => {
              setInstanceId(id)
              reload()
              go('mine')
            }}
          />
        </div>
      )}
    </>
  )

  // Téléphone : écran poussé (retour à gauche), les trois onglets en
  // commutateur sous l'en-tête, et chaque onglet en colonne qui défile — voir
  // la branche téléphone de chacun. Pas de fond topographique animé : il
  // tournerait en boucle derrière une liste qu'on fait défiler.
  if (mobile) {
    return (
      <div className="relative flex h-full flex-col overflow-hidden bg-[rgb(var(--bg-primary))] text-txt-primary">
        <ScreenHeader title={t('servers.page.title')} subtitle={t('servers.page.subtitle')} fallback="/home" />
        <div className="shrink-0 px-4 pb-3 pt-3">
          <Segmented value={tab} onChange={go} options={TABS.map((v) => ({ value: v, label: t(`servers.page.tabs.${v}`) }))} />
        </div>
        <div className="flex min-h-0 flex-1 flex-col">{views}</div>
      </div>
    )
  }

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      {/* Le fond topographique couvre tout l'écran, sous le contenu : des
          courbes grises discrètes, assombries vers le centre où se lit le
          contenu. */}
      <div className="pointer-events-none absolute inset-0 opacity-45">
        <TopoBackdrop color={SERVERS_TINT} />
      </div>
      <div className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_center,rgba(10,10,13,0.55),transparent_75%)]" />

      <div className="relative z-10 flex h-full flex-col">
        <PageHeader>
          <PageHeaderSeparator />
          <div className="min-w-0 flex-shrink-0">
            <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('servers.page.title')}</h1>
            <p className="mt-px text-[10px] text-txt-muted">{t('servers.page.subtitle')}</p>
          </div>
          <nav className="flex min-w-0 flex-1 [justify-content:safe_center]">
            <div className="flex shrink-0 rounded-xl bg-white/[0.04] p-1 backdrop-blur-sm">
              {TABS.map((v) => (
                <button
                  key={v}
                  onClick={() => go(v)}
                  className={`relative whitespace-nowrap rounded-lg px-3 py-1.5 text-[12px] font-bold transition-colors lg:px-4 ${
                    tab === v ? 'text-white' : 'text-txt-muted hover:text-txt-secondary'
                  }`}
                >
                  {tab === v && <motion.span layoutId="servers-tab" transition={SNAP} className="absolute inset-0 rounded-lg bg-accent/80" />}
                  <span className="relative">{t(`servers.page.tabs.${v}`)}</span>
                </button>
              ))}
            </div>
          </nav>
        </PageHeader>

        {/* L'écran ne défile pas : ce sont les listes qui défilent dans leur cadre. */}
        <div className="min-h-0 flex-1 overflow-hidden">
          <div className="mx-auto flex h-full w-full min-w-0 max-w-7xl flex-col px-6 py-5">{views}</div>
        </div>
      </div>
    </div>
  )
}
