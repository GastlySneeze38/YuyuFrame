import type { PassReward } from '@/lib/competitive'
import { useContentAsset } from '@/lib/contentAsset'
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
  return <CosmeticIcon kind={reward.kind} color={color} tierIndex={tierIndex} size={size} />
}
