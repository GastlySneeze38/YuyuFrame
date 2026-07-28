import { useEffect, useState } from 'react'
import { getName, getVersion, getTauriVersion } from '@tauri-apps/api/app'
import { open } from '@tauri-apps/plugin-shell'
import { PageHeader } from '@/components/ui/PageHeader'
import { useT } from '@/i18n'

const FORGE_PATREON_URL = 'https://www.patreon.com/LexManos'

interface AppInfo {
  name: string
  version: string
  tauriVersion: string
}

const BETA_TESTERS: string[] = [
  'HYROKY', 'Wiliking', 'SucreNormal', 'SarodayNest', 'Pumba', 'MedicalNew', 'Lpz2903', 'Smiouw',
]

export default function Information() {
  const t = useT()
  const FEATURES = [
    t('information.feature1'),
    t('information.feature2'),
    t('information.feature3'),
    t('information.feature4'),
  ]
  const [info, setInfo] = useState<AppInfo | null>(null)

  useEffect(() => {
    Promise.all([getName(), getVersion(), getTauriVersion()])
      .then(([name, version, tauriVersion]) => setInfo({ name, version, tauriVersion }))
      .catch(() => setInfo({ name: 'YuyuFrame', version: '—', tauriVersion: '—' }))
  }, [])

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D] text-white">

      <PageHeader>
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">{t('information.title')}</h1>
      </PageHeader>

      {/* Content */}
      <div className="flex-1 overflow-y-auto px-6 py-6">
        <div className="mx-auto flex max-w-2xl flex-col gap-6">

          {/* Branding */}
          <div
            className="flex flex-col items-center gap-3 rounded-2xl px-6 py-8 text-center bg-[rgba(75,63,207,0.08)] border border-[rgba(75,63,207,0.2)]"
          >
            <div
              className="flex items-center justify-center rounded-2xl font-black text-white w-[72px] h-[72px] bg-[rgba(75,63,207,0.25)] text-[32px] font-mono shadow-[0_0_40px_rgba(75,63,207,0.3)]"
            >
              Y
            </div>
            <div>
              <h2 className="font-black text-white text-[28px] tracking-[-0.02em] [text-shadow:0_0_32px_rgba(75,63,207,0.5)]">
                YuyuFrame
              </h2>
              <p className="text-[13px] text-white/40 mt-1">
                {t('home.brandTagline')}
              </p>
            </div>
            {info && (
              <div className="flex items-center gap-2 mt-1">
                <span
                  className="text-[12px] font-bold text-[rgba(120,110,230,0.9)] bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.35)] rounded-lg px-[10px] py-[3px]"
                >
                  v{info.version}
                </span>
              </div>
            )}
          </div>

          {/* Version détails */}
          {info && (
            <Section title={t('information.version')}>
              <InfoRow label={t('information.application')} value={`${info.name} v${info.version}`} />
              <InfoRow label={t('information.tauri')} value={`v${info.tauriVersion}`} />
              <InfoRow label={t('information.platform')} value="Windows" />
            </Section>
          )}

          {/* Fonctionnalités */}
          <Section title={t('information.features')}>
            <div className="flex flex-col gap-2">
              {FEATURES.map((f) => (
                <div
                  key={f}
                  className="flex items-center gap-3 rounded-xl px-4 py-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]"
                >
                  <div className="w-[5px] h-[5px] rounded-full bg-[rgba(75,63,207,0.8)] flex-shrink-0" />
                  <span className="text-[13px] text-white/60">{f}</span>
                </div>
              ))}
            </div>
          </Section>

          {/* Beta testeurs */}
          <Section title={t('information.betaTesters')}>
            {BETA_TESTERS.length > 0 ? (
              <div className="flex flex-wrap gap-2">
                {BETA_TESTERS.map((name) => (
                  <span
                    key={name}
                    className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[12px] font-semibold text-[rgba(120,110,230,0.9)] bg-[rgba(75,63,207,0.12)] border border-[rgba(75,63,207,0.3)]"
                  >
                    <svg viewBox="0 0 24 24" fill="currentColor" width={11} height={11}>
                      <path d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
                    </svg>
                    {name}
                  </span>
                ))}
              </div>
            ) : (
              <div className="rounded-xl px-4 py-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]">
                <p className="text-[12px] text-white/30">{t('information.listComingSoon')}</p>
              </div>
            )}
          </Section>

          {/* Auteur */}
          <Section title={t('information.developer')}>
            <InfoRow label={t('information.author')} value="Ghasty" />
            <InfoRow label={t('information.licence')} value={t('information.openSource')} />
            <InfoRow label={t('information.repository')} value="github.com/Ghasty/YuyuFrame" dim />
          </Section>

          {/* Minecraft Forge — mis en évidence avec la palette violette
              standard de l'app (même accent que la carte Branding en haut de
              page), pour rester cohérent tout en se détachant des sections
              neutres environnantes. */}
          <Section title={t('information.forgeTitle')}>
            <div className="flex flex-col gap-3 rounded-xl px-4 py-3.5 bg-[rgba(75,63,207,0.1)] border border-[rgba(75,63,207,0.3)]">
              <div className="flex items-start gap-3">
                <div className="flex h-9 w-9 flex-shrink-0 items-center justify-center rounded-xl bg-[rgba(75,63,207,0.2)]">
                  <svg viewBox="0 0 24 24" fill="#818cf8" width={16} height={16}>
                    <path d="M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z" />
                  </svg>
                </div>
                <p className="text-[12px] text-white/65 leading-[1.6]">
                  {t('information.forgeText')}
                </p>
              </div>
              <button
                onClick={() => open(FORGE_PATREON_URL)}
                className="flex h-9 items-center justify-center rounded-lg text-[12px] font-bold text-white bg-[#4B3FCF] transition-colors hover:bg-[#6155e8]"
              >
                {t('information.forgeLink')}
              </button>
            </div>
          </Section>

          {/* Mentions légales */}
          <Section title={t('information.legalNotices')}>
            <p className="text-[12px] text-white/30 leading-[1.7]">
              {t('information.legalText')}
            </p>
            <p className="text-[11px] text-white/18 mt-2">
              {t('home.copyright')}
            </p>
          </Section>

        </div>
      </div>
    </div>
  )
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-[11px] font-bold text-white/30 tracking-[0.1em] uppercase">
        {title}
      </h3>
      {children}
    </div>
  )
}

function InfoRow({ label, value, dim }: { label: string; value: string; dim?: boolean }) {
  return (
    <div
      className="flex items-center justify-between rounded-xl px-4 py-3 bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.06)]"
    >
      <span className="text-[13px] text-white/40">{label}</span>
      <span className={`text-[13px] font-semibold ${dim ? 'text-white/25' : 'text-white/75'}`}>{value}</span>
    </div>
  )
}
