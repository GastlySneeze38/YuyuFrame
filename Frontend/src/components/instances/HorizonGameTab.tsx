import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import { HORIZON_ASPECTS, type HorizonEngineSettings } from '@/types/horizon'
import { Toggle } from '@/components/ui/Toggle'
import { TouchRange } from '@/mobile/ui/TouchRange'
import { showError } from '@/stores/useErrorToast'
import { useT } from '@/i18n'
import { Row, Segmented, Separator } from './settingsParts'

/** Les cadences proposées ; 260 = illimité pour le jeu (`maxFps`). */
const FPS = [30, 60, 90, 120, 260] as const

/**
 * Les réglages du jeu d'une instance Horizon dans l'appli Android, écrits dans
 * les fichiers que le moteur lit (`horizon-engine-api`, `settings`). Le jeu les
 * prend au lancement suivant ; il réécrit ces fichiers en quittant, donc rien
 * ne s'écrit pendant qu'il tourne (le Rust refuse, et le dit).
 *
 * Une valeur non réglée (`null`) affiche le défaut du moteur sans l'écrire :
 * il dépend de la puissance du téléphone.
 */
export function HorizonGameTab({ instance }: { instance: Instance }) {
  const t = useT()
  const [settings, setSettings] = useState<HorizonEngineSettings | null>(null)
  // Les curseurs n'écrivent qu'une fois lâchés : à chaque cran, ce serait une
  // écriture de fichier par image du geste.
  const [scale, setScale] = useState(100)
  const [sensitivity, setSensitivity] = useState(1)
  const timers = useRef<Record<string, number>>({})

  useEffect(() => {
    let live = true
    api.horizonEngine
      .settings(instance.id)
      .then((s) => {
        if (!live) return
        setSettings(s)
        setScale(s.resolution_scale ?? 100)
        setSensitivity(s.gyro_sensitivity ?? 1)
      })
      .catch(showError)
    return () => { live = false }
  }, [instance.id])

  useEffect(() => () => Object.values(timers.current).forEach((id) => window.clearTimeout(id)), [])

  const save = async (change: Partial<HorizonEngineSettings>) => {
    // Affiché tout de suite ; la réponse du Rust (valeurs bornées, relues)
    // remplace ensuite ce qu'on a supposé.
    setSettings((s) => (s ? { ...s, ...change } : s))
    try {
      setSettings(await api.horizonEngine.setSettings(instance.id, change))
    } catch (e) {
      showError(e)
      api.horizonEngine.settings(instance.id).then(setSettings).catch(() => {})
    }
  }

  /** Enregistre après une courte pause du curseur. */
  const later = (key: string, change: Partial<HorizonEngineSettings>) => {
    window.clearTimeout(timers.current[key])
    timers.current[key] = window.setTimeout(() => void save(change), 450)
  }

  if (!settings) {
    return <p className="text-[13px] text-txt-muted">{t('common.loading')}</p>
  }

  return (
    <div className="flex flex-col gap-5">
      <p className="text-[12.5px] leading-relaxed text-txt-muted">{t('horizonEngine.intro')}</p>

      <Row
        title={`${t('horizonEngine.resolution')} · ${scale} %`}
        desc={t('horizonEngine.resolutionDesc')}
        action={
          <TouchRange
            min={25}
            max={200}
            step={5}
            value={scale}
            label={t('horizonEngine.resolution')}
            onChange={(v) => { setScale(v); later('scale', { resolution_scale: v }) }}
          />
        }
      />

      <Row
        title={t('horizonEngine.fps')}
        desc={t('horizonEngine.fpsDesc')}
        action={
          <Segmented
            value={settings.max_fps}
            onChange={(v) => void save({ max_fps: v })}
            options={FPS.map((v) => ({ value: v as number, label: v === 260 ? t('horizonEngine.unlimited') : String(v) }))}
          />
        }
      />

      <Row
        title={t('horizonEngine.battery')}
        desc={t('horizonEngine.batteryDesc')}
        action={<Toggle checked={settings.battery_mode ?? false} onChange={() => void save({ battery_mode: !(settings.battery_mode ?? false) })} />}
      />

      <Separator />

      <Row
        title={t('horizonEngine.orientation')}
        desc={t('horizonEngine.orientationDesc')}
        action={
          <Segmented
            value={settings.orientation ?? 'landscape'}
            onChange={(v) => void save({ orientation: v })}
            options={[
              { value: 'landscape' as const, label: t('horizonEngine.landscape') },
              { value: 'portrait' as const, label: t('horizonEngine.portrait') },
              { value: 'both' as const, label: t('horizonEngine.both') },
            ]}
          />
        }
      />

      <Row
        title={t('horizonEngine.rotation')}
        desc={t('horizonEngine.rotationDesc')}
        action={
          <Segmented
            value={settings.rotation ?? 'automatic'}
            onChange={(v) => void save({ rotation: v })}
            options={[
              { value: 'automatic' as const, label: t('horizonEngine.rotationAuto') },
              { value: 'inverted_automatic' as const, label: t('horizonEngine.rotationInverted') },
              { value: 'fixed' as const, label: t('horizonEngine.rotationFixed') },
              { value: 'fixed_reversed' as const, label: t('horizonEngine.rotationFixedReversed') },
            ]}
          />
        }
      />

      <Row
        title={t('horizonEngine.aspect')}
        desc={t('horizonEngine.aspectDesc')}
        action={
          <select
            value={settings.aspect ?? 0}
            onChange={(e) => void save({ aspect: Number(e.target.value) })}
            className="h-10 w-full rounded-xl border border-line bg-black/40 px-3 text-[14px] text-txt-primary outline-none focus:border-accent/60"
          >
            {HORIZON_ASPECTS.map((name, i) => (
              <option key={name} value={i}>{i === 0 ? t('horizonEngine.aspectNative') : name}</option>
            ))}
          </select>
        }
      />

      <Row
        title={t('horizonEngine.cutout')}
        desc={t('horizonEngine.cutoutDesc')}
        action={<Toggle checked={settings.cutout === 'avoid'} onChange={() => void save({ cutout: settings.cutout === 'avoid' ? 'full' : 'avoid' })} />}
      />

      <Separator />

      <Row
        title={t('horizonEngine.gyroscope')}
        desc={t('horizonEngine.gyroscopeDesc')}
        action={<Toggle checked={settings.gyroscope ?? false} onChange={() => void save({ gyroscope: !(settings.gyroscope ?? false) })} />}
      />

      <div className={`transition-opacity ${settings.gyroscope ? '' : 'pointer-events-none opacity-40'}`}>
        <Row
          title={`${t('horizonEngine.sensitivity')} · ${sensitivity.toFixed(1)}`}
          desc={t('horizonEngine.sensitivityDesc')}
          action={
            <TouchRange
              min={0.1}
              max={3}
              step={0.1}
              value={sensitivity}
              label={t('horizonEngine.sensitivity')}
              onChange={(v) => { setSensitivity(v); later('gyro', { gyro_sensitivity: v }) }}
            />
          }
        />
      </div>
    </div>
  )
}
