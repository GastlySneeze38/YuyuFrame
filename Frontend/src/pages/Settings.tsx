import { useStore } from '@/stores/useStore'
import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { Toggle } from '@/components/ui/Toggle'

export default function Settings() {
  const {
    brightness, setBrightness, defaultRam, setDefaultRam, closeOnLaunch, setCloseOnLaunch,
    instanceSyncMode, setInstanceSyncMode, avoidBetaDependencies, setAvoidBetaDependencies,
    syncGameSettings, setSyncGameSettings, showConsole, setShowConsole,
  } = useStore()

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#09090D]">

      <PageHeader>
        <PageHeaderSeparator />
        <div>
          <h1 className="font-black text-white text-[16px] tracking-[-0.01em] leading-[1.2]">
            Paramètres
          </h1>
          <p className="text-[10px] text-white/28 mt-px">
            Configuration de YuyuFrame
          </p>
        </div>
      </PageHeader>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-8">
        <div className="mx-auto flex max-w-2xl flex-col gap-4">

          {/* Launcher */}
          <SCard
            title="Launcher"
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M8 5v14l11-7z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* RAM par défaut */}
              <div className="flex flex-col gap-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-white">RAM par défaut</p>
                    <p className="text-[11px] text-white/35 mt-0.5">
                      Valeur pré-sélectionnée à la création d'une instance
                    </p>
                  </div>
                  <span className="text-sm font-bold text-[#7b72e9]">
                    {defaultRam >= 1024 ? `${(defaultRam / 1024).toFixed(defaultRam % 1024 === 0 ? 0 : 1)} Go` : `${defaultRam} Mo`}
                  </span>
                </div>
                <input
                  type="range"
                  min={1024} max={16384} step={512}
                  value={defaultRam}
                  onChange={(e) => setDefaultRam(Number(e.target.value))}
                  className="w-full accent-[#4B3FCF]"
                />
                <div className="flex justify-between text-[10px] text-white/25">
                  <span>1 Go</span><span>4 Go</span><span>8 Go</span><span>12 Go</span><span>16 Go</span>
                </div>
              </div>

              <div className="h-px bg-white/6" />

              {/* Fermer au lancement */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">Masquer au lancement</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    Cache le launcher pendant que le jeu tourne
                  </p>
                </div>
                <Toggle checked={closeOnLaunch} onChange={() => setCloseOnLaunch(!closeOnLaunch)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Console au lancement */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">Lancer avec la console</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    Ouvre la fenêtre de logs du jeu à chaque lancement — le jeu se lance normalement même désactivé
                  </p>
                </div>
                <Toggle checked={showConsole} onChange={() => setShowConsole(!showConsole)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Dépendances beta */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">Éviter les dépendances beta</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    N'installe jamais automatiquement une version beta/alpha/RC d'un mod requis (ex: Sodium) — évite les incompatibilités avec les mods qui ne les supportent pas encore
                  </p>
                </div>
                <Toggle checked={avoidBetaDependencies} onChange={() => setAvoidBetaDependencies(!avoidBetaDependencies)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Sync paramètres Minecraft */}
              <div className="flex items-center justify-between">
                <div>
                  <p className="text-sm font-medium text-white">Synchroniser les paramètres Minecraft</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    Applique automatiquement ton options.txt (touches, vidéo…) à chaque nouvelle instance — exporte-le depuis l'instance de ton choix via le bouton ··· dans la liste
                  </p>
                </div>
                <Toggle checked={syncGameSettings} onChange={() => setSyncGameSettings(!syncGameSettings)} />
              </div>

              <div className="h-px bg-white/6" />

              {/* Sync instances au démarrage */}
              <div className="flex flex-col gap-3">
                <div>
                  <p className="text-sm font-medium text-white">Sync instances au démarrage</p>
                  <p className="text-[11px] text-white/35 mt-0.5">
                    Que faire si des dossiers d'instances ne correspondent pas à la DB
                  </p>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  {([
                    { value: 'db_wins', label: 'Supprimer', desc: 'Efface les dossiers sans entrée en DB' },
                    { value: 'disk_wins', label: 'Importer', desc: 'Ajoute en DB les dossiers détectés' },
                  ] as const).map(({ value, label, desc }) => {
                    const active = instanceSyncMode === value
                    return (
                      <button
                        key={value}
                        onClick={() => setInstanceSyncMode(value)}
                        className={`flex flex-col gap-1 rounded-xl p-3 text-left transition-all duration-150 ${active ? 'bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.55)]' : 'bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]'}`}
                      >
                        <span className="font-semibold text-white text-[12px]">{label}</span>
                        <span className="text-[10px] text-white/35 leading-[1.4]">{desc}</span>
                      </button>
                    )
                  })}
                </div>
              </div>
            </div>
          </SCard>

          {/* Apparence */}
          <SCard
            title="Apparence"
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9c.83 0 1.5-.67 1.5-1.5 0-.39-.15-.74-.39-1.01-.23-.26-.38-.61-.38-.99 0-.83.67-1.5 1.5-1.5H16c2.76 0 5-2.24 5-5 0-4.42-4.03-8-9-8zm-5.5 9c-.83 0-1.5-.67-1.5-1.5S5.67 9 6.5 9 8 9.67 8 10.5 7.33 12 6.5 12zm3-4C8.67 8 8 7.33 8 6.5S8.67 5 9.5 5s1.5.67 1.5 1.5S10.33 8 9.5 8zm5 0c-.83 0-1.5-.67-1.5-1.5S13.67 5 14.5 5s1.5.67 1.5 1.5S15.33 8 14.5 8zm3 4c-.83 0-1.5-.67-1.5-1.5S16.67 9 17.5 9s1.5.67 1.5 1.5-.67 1.5-1.5 1.5z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-6">
              {/* Mode d'affichage */}
              <div className="flex flex-col gap-3">
                <p className="text-sm font-medium text-white">Mode d'affichage</p>
                <div className="grid grid-cols-2 gap-3">
                  {([
                    { id: 'oled', label: 'OLED', desc: 'Luminosité standard', value: 100, icon: '◑' },
                    { id: 'dark', label: 'Dark', desc: 'Luminosité boostée', value: 200, icon: '☀' },
                  ] as const).map(({ id, label, desc, value, icon }) => {
                    const active = brightness === value
                    return (
                      <button
                        key={id}
                        onClick={() => setBrightness(value)}
                        className={`flex flex-col gap-1 rounded-xl p-4 text-left transition-all duration-150 ${active ? 'bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.55)]' : 'bg-[rgba(255,255,255,0.03)] border border-[rgba(255,255,255,0.07)]'}`}
                      >
                        <span className="text-[18px] leading-none">{icon}</span>
                        <span className="font-semibold text-white text-[13px]">{label}</span>
                        <span className="text-[11px] text-white/35">{desc}</span>
                      </button>
                    )
                  })}
                </div>
              </div>

              <div className="h-px bg-white/6" />

              {/* Luminosité */}
              <div className="flex flex-col gap-4">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-sm font-medium text-white">Luminosité</p>
                    <p className="text-[11px] text-white/35 mt-0.5">
                      Ajuste finement la luminosité de l'interface
                    </p>
                  </div>
                  <span className="text-sm font-bold text-[#7b72e9]">
                    {brightness}%
                  </span>
                </div>
                <input
                  type="range"
                  min={40} max={200} step={5}
                  value={brightness}
                  onChange={(e) => setBrightness(Number(e.target.value))}
                  className="w-full accent-[#4B3FCF]"
                />
                <div className="flex justify-between text-[10px] text-white/25">
                  <span>Sombre</span>
                  <span>OLED</span>
                  <span>Dark</span>
                </div>
              </div>
            </div>
          </SCard>

          {/* À propos */}
          <SCard
            title="À propos"
            icon={
              <svg viewBox="0 0 24 24" fill="currentColor" className="h-4 w-4">
                <path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z" />
              </svg>
            }
          >
            <div className="flex flex-col gap-3">
              <IRow label="Launcher" value="YuyuFrame v2.0" />
              <IRow label="Stack" value="Tauri · React · Rust" />
              <IRow label="Auteur" value="Ghasty" />
            </div>
          </SCard>

        </div>
      </div>
    </div>
  )
}

function SCard({ title, icon, children }: { title: string; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div
      className="rounded-2xl p-6 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]"
    >
      <div className="mb-5 flex items-center gap-3">
        <div
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-[rgba(75,63,207,0.2)] text-[#7b72e9]"
        >
          {icon}
        </div>
        <h2 className="font-bold text-white text-[14px] tracking-[0.02em]">
          {title}
        </h2>
      </div>
      {children}
    </div>
  )
}


function IRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[13px] text-white/35">{label}</span>
      <span className="font-medium text-white text-[13px]">{value}</span>
    </div>
  )
}
