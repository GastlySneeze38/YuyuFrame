import type { PassReward } from '@/lib/competitive'
import { BANNERS } from '@/lib/profileBanners'
import { CosmeticIcon } from './RewardsView'

/**
 * Une récompense du passe, dessinée telle qu'elle est (le passe n'a pas
 * d'images, `migrations/0033`) : la bannière, la pastille de la couleur, le
 * titre écrit sur sa plaque. Une sorte inconnue (trou dans le passe, serveur
 * d'avant) retombe sur l'icône de la sorte : l'écran ne montre jamais un trou.
 */
export function RewardImage({ reward, color, tierIndex, size }: { reward: PassReward; color: string; tierIndex: number; size: number }) {
  if (reward.kind === 'banner' && reward.code && BANNERS[reward.code]) {
    return <span className="block rounded-[18%] border border-white/15" style={{ width: size, height: size * 0.62, background: BANNERS[reward.code] }} />
  }
  if (reward.kind === 'accent' && reward.code) {
    return (
      <span
        className="block rounded-full border-2 border-white/60"
        style={{ width: size * 0.62, height: size * 0.62, background: reward.code, boxShadow: `0 0 ${Math.round(size * 0.2)}px ${reward.code}66` }}
      />
    )
  }
  if (reward.kind === 'title' && reward.name) {
    return <TitlePlate text={reward.name} color={color} size={size} />
  }
  return <CosmeticIcon kind={reward.kind} color={color} tierIndex={tierIndex} size={size} />
}

/** Un titre : son texte sur une plaque, à la couleur du passe. Même dessin que
 *  l'aperçu du back-office (`PassComposer.tsx`, `TitleArt`). */
function TitlePlate({ text, color, size }: { text: string; color: string; size: number }) {
  return (
    <span
      className="flex items-center justify-center rounded-[14%] border text-center font-black uppercase leading-tight"
      style={{
        width: size,
        height: size * 0.62,
        padding: size * 0.06,
        fontSize: Math.max(7, size * 0.12),
        borderColor: `rgb(${color} / 0.7)`,
        background: `linear-gradient(160deg, rgb(${color} / 0.35), rgb(${color} / 0.08))`,
        color: `rgb(${color})`,
      }}
    >
      <span className="line-clamp-2 break-words">{text}</span>
    </span>
  )
}
