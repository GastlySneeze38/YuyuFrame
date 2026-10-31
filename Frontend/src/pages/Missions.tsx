import { PageHeader, PageHeaderSeparator } from '@/components/ui/PageHeader'
import { PageGlow } from '@/components/PageGlow'
import { ChallengesView } from '@/components/competitive/Challenges'
import { usePass } from '@/lib/usePass'
import { useT } from '@/i18n'

/**
 * Missions et passe de saison, ouverts depuis la page Outils.
 *
 * Elles étaient un onglet du Compétitif, mais elles ne sont pas propres au
 * PvP : une mission peut demander n'importe quoi au joueur. Missions et passe
 * sont réglés dans le back-office et lus sur `GET /v1/pass` (`lib/usePass.ts`) ;
 * sans saison ni mission, l'écran dit « bientôt ».
 */
export default function Missions() {
  const t = useT()
  // Démonstration (atelier) ou `GET /v1/pass` ; sans rien, « bientôt ».
  const { challenges, pass, season } = usePass()

  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-bg-primary text-txt-primary">
      <PageGlow />

      <PageHeader>
        <PageHeaderSeparator />
        <div className="min-w-0 flex-1">
          <h1 className="text-[16px] font-black leading-[1.2] tracking-[-0.01em]">{t('features.missionsTitle')}</h1>
          <p className="mt-px text-[10px] text-txt-muted">{t('features.missionsText')}</p>
        </div>
      </PageHeader>

      {/* La page ne défile pas : l'écran tient dans la fenêtre. */}
      <div className="min-h-0 flex-1 overflow-hidden">
        <div className="mx-auto h-full w-full max-w-6xl px-6 py-5">
          <ChallengesView challenges={challenges} pass={pass} season={season} />
        </div>
      </div>
    </div>
  )
}
