import { useEffect, useState } from 'react'
import { getName, getVersion, getTauriVersion } from '@tauri-apps/api/app'
import { PageHeader } from '@/components/ui/PageHeader'

interface AppInfo {
  name: string
  version: string
  tauriVersion: string
}

const FEATURES = [
  'Lancer Minecraft avec plusieurs instances indépendantes',
  'Installer et gérer des mods via Modrinth ou en .jar',
  'Authentification Microsoft & gestion de comptes',
  'Téléchargement automatique de Minecraft et des loaders',
]

const BETA_TESTERS: string[] = [
  'Hyroky', 'Wiliking', 'SucreNormal', 'SarodayNest', 'Pumba', 'MedicalNew', 'Lpz2903', 'Smiouw',
]

export default function Information() {
  const [info, setInfo] = useState<AppInfo | null>(null)

  useEffect(() => {
    Promise.all([getName(), getVersion(), getTauriVersion()])
      .then(([name, version, tauriVersion]) => setInfo({ name, version, tauriVersion }))
      .catch(() => setInfo({ name: 'YuyuFrame', version: '—', tauriVersion: '—' }))
  }, [])

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D] text-white">

      <PageHeader>
        <h1 className="font-black text-white text-[18px] tracking-[-0.01em]">Informations</h1>
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
                Le launcher Minecraft open-source
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
            <Section title="Version">
              <InfoRow label="Application" value={`${info.name} v${info.version}`} />
              <InfoRow label="Tauri" value={`v${info.tauriVersion}`} />
              <InfoRow label="Plateforme" value="Windows" />
            </Section>
          )}

          {/* Fonctionnalités */}
          <Section title="Fonctionnalités">
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
          <Section title="Beta testeurs">
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
                <p className="text-[12px] text-white/30">Liste à venir</p>
              </div>
            )}
          </Section>

          {/* Auteur */}
          <Section title="Développeur">
            <InfoRow label="Auteur" value="Ghasty" />
            <InfoRow label="Licence" value="Open-source" />
            <InfoRow label="Dépôt" value="github.com/Ghasty/YuyuFrame" dim />
          </Section>

          {/* Mentions légales */}
          <Section title="Mentions légales">
            <p className="text-[12px] text-white/30 leading-[1.7]">
              YuyuFrame est un launcher non-officiel et n'est pas affilié à Mojang Studios ou Microsoft.
              Minecraft est une marque déposée de Microsoft Corporation.
            </p>
            <p className="text-[11px] text-white/18 mt-2">
              © 2025 YuyuFrame — Tous droits réservés
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
