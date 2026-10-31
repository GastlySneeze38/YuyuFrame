import type { PassReward } from '@/lib/competitive'
import { useContentAsset } from '@/lib/contentAsset'
import { BANNERS } from '@/lib/profileBanners'
import { CosmeticIcon } from './RewardsView'

/**
 * Une récompense du passe : son image réglée dans le back-office, sinon
 * (pas d'image, image supprimée, hors ligne) l'icône dessinée de sa sorte —
 * l'écran ne montre jamais un trou.
 */
export function RewardImage({ reward, color, tierIndex, size }: { reward: PassReward; color: string; tierIndex: number; size: number }) {
  const uri = useContentAsset(reward.asset)
  if (uri) {
    return <img src={uri} alt="" draggable={false} style={{ width: size, height: size }} className="select-none object-contain" />
  }
  // Une bannière ou une couleur de profil se montre telle qu'elle est.
  if (reward.kind === 'banner' && reward.code && BANNERS[reward.code]) {
    return <span className="block rounded-[18%] border border-white/15" style={{ width: size, height: size * 0.62, background: BANNERS[reward.code] }} />
  }
  if (reward.kind === 'accent' && reward.code) {
    return <span className="block rounded-full border-2 border-white/60" style={{ width: size * 0.62, height: size * 0.62, background: reward.code }} />
  }
  return <CosmeticIcon kind={reward.kind} color={color} tierIndex={tierIndex} size={size} />
}
