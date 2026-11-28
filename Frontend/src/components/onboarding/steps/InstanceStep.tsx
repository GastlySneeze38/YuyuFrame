import { useEffect, useState } from 'react'
import { motion } from 'framer-motion'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { useLoadersFor } from '@/lib/loaderCompat'
import { fallbackLoader, offeredLoaders } from '@/lib/loader'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import { HORIZON_GAME_VERSION, isIosApp, isPhoneApp } from '@/lib/platform'
import { HorizonIosResources } from '@/components/settings/HorizonIosResources'
import type { Loader } from '@/types'
import { Field, Ghost, Item, Primary, Stagger } from '../ui'

const LOADER_NAMES: Record<string, string> = {
  vanilla: 'Vanilla',
  fabric: 'Fabric',
  quilt: 'Quilt',
  forge: 'Forge',
  neoforge: 'NeoForge',
  horizon: 'Horizon',
}

/**
 * La première instance : un nom, une version (la dernière sortie par défaut)
 * et une plateforme parmi celles qui existent pour cette version
 * (`useLoadersFor`, comme la fenêtre de création). La mémoire est celle des
 * paramètres ; tout le reste se règle ensuite dans l'instance.
 */
export function InstanceStep({ onDone, onSkip }: { onDone: () => void; onSkip: () => void }) {
  // Téléphone : le jeu est dans l'appli, et c'est Horizon en 26.3, rien
  // d'autre. Pas de version ni de plateforme à choisir.
  if (isPhoneApp) return <PhoneInstance onDone={onDone} />
  return <DesktopInstance onDone={onDone} onSkip={onSkip} />
}

/**
 * Téléphone (2026-11-28) : un nom, et c'est tout. Sur iPhone, les fichiers
 * du jeu peuvent se télécharger tout de suite, pendant la suite de l'accueil
 * (`lib/horizonResources.ts` : le téléchargement survit au changement
 * d'étape).
 */
function PhoneInstance({ onDone }: { onDone: () => void }) {
  const t = useT()
  const addInstance = useStore((s) => s.addInstance)
  const setSelectedInstanceId = useStore((s) => s.setSelectedInstanceId)
  const defaultRam = useStore((s) => s.defaultRam)
  const [name, setName] = useState(t('onboarding.instance.defaultName'))
  const [busy, setBusy] = useState(false)

  const create = async () => {
    setBusy(true)
    try {
      const inst = await api.instances.create(name.trim() || t('onboarding.instance.defaultName'), HORIZON_GAME_VERSION, 'horizon', defaultRam)
      addInstance(inst)
      setSelectedInstanceId(inst.id)
      onDone()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Stagger className="flex flex-col gap-4">
      <Item>
        <Field label={t('onboarding.instance.name')} value={name} onChange={setName} maxLength={48} onEnter={() => void create()} />
      </Item>
      <Item className="flex items-center gap-3 rounded-2xl border border-[rgb(var(--accent)/0.35)] bg-[rgb(var(--accent)/0.1)] p-3.5">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-[rgb(var(--accent)/0.25)] text-[15px] font-black text-[rgb(var(--accent-text))]">
          {HORIZON_GAME_VERSION}
        </span>
        <span className="min-w-0">
          <span className="block text-[15px] font-bold text-white">Minecraft {HORIZON_GAME_VERSION} · Horizon</span>
          <span className="block text-[13px] leading-snug text-white/60">{t('onboarding.instance.phoneHint')}</span>
        </span>
      </Item>
      {isIosApp && (
        <Item>
          <HorizonIosResources />
        </Item>
      )}
      <Item>
        <Primary onClick={() => void create()} loading={busy}>
          {busy ? t('onboarding.instance.creating') : t('onboarding.instance.create')}
        </Primary>
      </Item>
    </Stagger>
  )
}

function DesktopInstance({ onDone, onSkip }: { onDone: () => void; onSkip: () => void }) {
  const t = useT()
  const defaultRam = useStore((s) => s.defaultRam)
  const addInstance = useStore((s) => s.addInstance)
  const setSelectedInstanceId = useStore((s) => s.setSelectedInstanceId)
  const [versions, setVersions] = useState<string[] | null>(null)
  const [failed, setFailed] = useState(false)
  const [name, setName] = useState(t('onboarding.instance.defaultName'))
  const [version, setVersion] = useState('')
  const [loader, setLoader] = useState<Loader>('vanilla')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.versions.list()
      .then((all) => {
        const releases = all.filter((v) => v.version_type === 'release').map((v) => v.id)
        setVersions(releases)
        setVersion(releases[0] ?? '')
      })
      .catch(() => setFailed(true))
  }, [])

  const available = useLoadersFor(version)
  // Une plateforme qui n'existe pas pour la version choisie se retire.
  useEffect(() => {
    if (available && !available.includes(loader)) setLoader(fallbackLoader(available))
  }, [available, loader])

  const create = async () => {
    setBusy(true)
    try {
      const inst = await api.instances.create(name.trim() || t('onboarding.instance.defaultName'), version, loader, defaultRam)
      addInstance(inst)
      setSelectedInstanceId(inst.id)
      onDone()
    } catch (e) {
      showError(e)
    } finally {
      setBusy(false)
    }
  }

  if (failed) {
    return (
      <Stagger className="flex flex-col items-center gap-3 py-4 text-center">
        <Item><p className="text-[13px] text-white/65">{t('onboarding.instance.loadError')}</p></Item>
        <Item><Ghost onClick={onSkip}>{t('onboarding.later')}</Ghost></Item>
      </Stagger>
    )
  }
  if (!versions) {
    return (
      <div className="flex h-40 items-center justify-center">
        <span className="h-7 w-7 animate-spin rounded-full border-2 border-white/10 border-t-[rgb(var(--accent))]" />
      </div>
    )
  }

  const recent = versions.slice(0, 4)
  const loaders = offeredLoaders(available)

  return (
    <Stagger className="flex flex-col gap-4">
      <Item>
        <Field label={t('onboarding.instance.name')} value={name} onChange={setName} maxLength={48} />
      </Item>

      <Item>
        <span className="mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-white/45 max-sm:text-[12px]">{t('instancesPage.versionMc')}</span>
        {/* Les quatre dernières sorties en un geste ; le reste dans la liste. */}
        <div className="grid grid-cols-4 gap-1.5 max-sm:grid-cols-2">
          {recent.map((v, i) => (
            <Choice key={v} selected={version === v} onClick={() => setVersion(v)} group="version">
              <span>{v}</span>
              {i === 0 && <span className="text-[9.5px] font-semibold uppercase tracking-[0.08em] text-[rgb(var(--accent-text))]">{t('onboarding.instance.latest')}</span>}
            </Choice>
          ))}
        </div>
        <div className="relative mt-1.5">
          <select
            value={version}
            onChange={(e) => setVersion(e.target.value)}
            className="h-10 w-full appearance-none rounded-xl border border-white/10 bg-black/35 px-3 pr-8 text-[13px] text-white/80 outline-none focus:border-[rgb(var(--accent)/0.7)] max-sm:h-12 max-sm:text-[16px]"
          >
            {versions.map((v) => (
              <option key={v} value={v} className="bg-[rgb(var(--bg-card))]">{v}</option>
            ))}
          </select>
          <svg viewBox="0 0 10 6" fill="white" width={10} height={6} className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 opacity-40"><path d="M0 0l5 6 5-6z" /></svg>
        </div>
      </Item>

      <Item>
        <span className="mb-1.5 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-white/45 max-sm:text-[12px]">{t('onboarding.instance.platform')}</span>
        <div className="flex flex-wrap gap-1.5">
          {loaders.map((l) => (
            <Choice key={l} selected={loader === l} onClick={() => setLoader(l)} group="loader" compact>
              {LOADER_NAMES[l] ?? l}
            </Choice>
          ))}
        </div>
        <p className="mt-2 text-[11.5px] leading-relaxed text-white/45 max-sm:text-[13px]">
          {loader === 'vanilla' ? t('onboarding.instance.vanillaHint') : t('onboarding.instance.modsHint')}
        </p>
      </Item>

      <Item>
        <Primary onClick={() => void create()} disabled={!version} loading={busy}>
          {busy ? t('onboarding.instance.creating') : t('onboarding.instance.create')}
        </Primary>
      </Item>
    </Stagger>
  )
}

/** Une case à choisir ; la sélection glisse de l'une à l'autre. */
function Choice({ selected, onClick, children, group, compact }: {
  selected: boolean
  onClick: () => void
  children: React.ReactNode
  group: string
  compact?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`relative flex flex-col items-center justify-center rounded-xl border text-[13px] font-semibold transition-[color,border-color,transform] duration-150 active:scale-[0.97] max-sm:text-[15px] ${
        compact ? 'h-10 px-4' : 'h-14 gap-0.5'
      } ${selected ? 'border-transparent text-white' : 'border-white/10 bg-black/30 text-white/55 hover:text-white/85'}`}
    >
      {selected && (
        <motion.span
          layoutId={`ob-choice-${group}`}
          className="absolute inset-0 rounded-xl border border-[rgb(var(--accent)/0.8)] bg-[rgb(var(--accent)/0.28)]"
          transition={{ type: 'spring', duration: 0.4, bounce: 0.15 }}
        />
      )}
      <span className="relative flex flex-col items-center gap-0.5">{children}</span>
    </button>
  )
}
