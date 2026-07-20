import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import type { Instance } from '@/types'
import { loaderColor } from '@/lib/loader'
import { formatRam } from '@/lib/format'
import { MenuItem } from './MenuItem'

export function InstanceCard({
  instance,
  selected,
  onSelect,
  onToggleFavorite,
  onDelete,
  onEdit,
  onDuplicate,
}: {
  instance: Instance
  selected: boolean
  onSelect: () => void
  onToggleFavorite: () => void
  onDelete: () => void
  onEdit: () => void
  onDuplicate: () => void
}) {
  const [hovered, setHovered] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

  // Fermeture du menu au clic en dehors — évite de devoir le refermer manuellement
  // et empêche tout clic accidentel sur une action pendant que le menu se ferme.
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
        setConfirm(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [menuOpen])

  return (
    <div
      onClick={onSelect}
      className="flex flex-col rounded-2xl px-4 py-3.5 cursor-pointer transition-all duration-150"
      style={{
        background: selected ? 'rgba(75,63,207,0.18)' : hovered ? 'rgba(255,255,255,0.06)' : 'rgba(255,255,255,0.04)',
        border: `1px solid ${selected ? 'rgba(75,63,207,0.55)' : 'rgba(255,255,255,0.06)'}`,
        boxShadow: selected ? '0 0 20px rgba(75,63,207,0.18)' : 'none',
        position: 'relative',
        zIndex: menuOpen ? 40 : 'auto',
      }}
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
    >
      {/* Partie haute : icône + nom + infos */}
      <div className="flex items-start gap-3 relative">
        <div
          className="flex items-center justify-center rounded-xl flex-shrink-0"
          style={{ width: 36, height: 36, background: selected ? 'rgba(75,63,207,0.3)' : 'rgba(255,255,255,0.05)', fontSize: 15 }}
        >
          🧱
        </div>

        <div className="flex flex-col flex-1 min-w-0">
          {/* Nom + étoile + menu */}
          <div>
            <p className="font-bold truncate" style={{ fontSize: 13, color: selected ? 'white' : 'rgba(255,255,255,0.85)', paddingRight: 30, width: '100%' }}>
              {instance.name}
            </p>

            <div ref={menuRef} className="absolute flex flex-col items-center gap-0.5 flex-shrink-0" style={{ top: '50%', right: 0, transform: 'translateY(-50%)' }}>
              <button
                onClick={(e) => { e.stopPropagation(); onToggleFavorite() }}
                className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150"
                title={instance.favorite ? 'Retirer des favoris' : 'Ajouter aux favoris'}
                style={{ color: instance.favorite ? '#facc15' : 'rgba(255,255,255,0.18)', background: 'transparent' }}
                onMouseEnter={(e) => { e.currentTarget.style.color = instance.favorite ? '#fde047' : 'rgba(255,255,255,0.5)' }}
                onMouseLeave={(e) => { e.currentTarget.style.color = instance.favorite ? '#facc15' : 'rgba(255,255,255,0.18)' }}
              >
                <svg viewBox="0 0 24 24" fill={instance.favorite ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth={instance.favorite ? 0 : 1.8} width={13} height={13}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M11.48 3.499a.562.562 0 011.04 0l2.125 5.111a.563.563 0 00.475.345l5.518.442c.499.04.701.663.321.988l-4.204 3.602a.563.563 0 00-.182.557l1.285 5.385a.562.562 0 01-.84.61l-4.725-2.885a.563.563 0 00-.586 0L6.982 20.54a.562.562 0 01-.84-.61l1.285-5.386a.562.562 0 00-.182-.557l-4.204-3.602a.563.563 0 01.321-.988l5.518-.442a.563.563 0 00.475-.345L11.48 3.5z" />
                </svg>
              </button>

              <button
                onClick={(e) => { e.stopPropagation(); setMenuOpen((v) => !v); setConfirm(false) }}
                className="flex h-6 w-6 flex-shrink-0 items-center justify-center rounded-lg transition-all duration-150"
                title="Plus d'actions"
                style={{ color: menuOpen ? 'rgba(255,255,255,0.85)' : 'rgba(255,255,255,0.25)', background: menuOpen ? 'rgba(255,255,255,0.1)' : 'transparent' }}
                onMouseEnter={(e) => { if (!menuOpen) e.currentTarget.style.color = 'rgba(255,255,255,0.6)' }}
                onMouseLeave={(e) => { if (!menuOpen) e.currentTarget.style.color = 'rgba(255,255,255,0.25)' }}
              >
                <svg viewBox="0 0 24 24" fill="currentColor" width={14} height={14}>
                  <circle cx="5" cy="12" r="2" /><circle cx="12" cy="12" r="2" /><circle cx="19" cy="12" r="2" />
                </svg>
              </button>

              {menuOpen && (
                <div
                  onClick={(e) => e.stopPropagation()}
                  className="absolute flex flex-col gap-0.5 rounded-xl p-1"
                  style={{
                    top: '100%', right: 0, marginTop: 4, width: 190, zIndex: 30,
                    background: '#1a1a24', border: '1px solid rgba(255,255,255,0.1)',
                    boxShadow: '0 12px 32px rgba(0,0,0,0.5)',
                  }}
                >
                  {!confirm ? (
                    <>
                      <MenuItem
                        onClick={() => { setMenuOpen(false); onEdit() }}
                        label="Modifier l'instance"
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 00-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => { setMenuOpen(false); onDuplicate() }}
                        label="Dupliquer"
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => { setMenuOpen(false); api.instances.exportSettings(instance.id).catch(() => {}) }}
                        label="Exporter mes paramètres"
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" /></svg>}
                      />
                      <MenuItem
                        onClick={() => setConfirm(true)}
                        label="Supprimer définitivement"
                        danger
                        icon={<svg viewBox="0 0 24 24" fill="currentColor" width={13} height={13}><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" /></svg>}
                      />
                    </>
                  ) : (
                    <div className="flex flex-col gap-1.5 p-1">
                      <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.5)' }}>Supprimer définitivement cette instance ?</p>
                      <div className="flex gap-1.5">
                        <button
                          onClick={() => { setMenuOpen(false); onDelete() }}
                          className="flex-1 rounded-lg"
                          style={{ fontSize: 11, fontWeight: 600, color: 'rgb(248,113,113)', background: 'rgba(200,50,50,0.15)', padding: '5px 0' }}
                        >
                          Supprimer
                        </button>
                        <button
                          onClick={() => setConfirm(false)}
                          className="flex-1 rounded-lg"
                          style={{ fontSize: 11, color: 'rgba(255,255,255,0.5)', background: 'rgba(255,255,255,0.06)', padding: '5px 0' }}
                        >
                          Annuler
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Infos */}
          <div className="flex items-center gap-2 mt-0.5">
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.3)' }}>{instance.mc_version}</span>
            <span style={{ fontSize: 10, color: loaderColor(instance.loader), fontWeight: 600 }}>{instance.loader}</span>
            <span style={{ fontSize: 10, color: 'rgba(255,255,255,0.2)' }}>{formatRam(instance.ram_mb)}</span>
          </div>
        </div>
      </div>

      {/* Description (si renseignée) — masquée par défaut, dépliée lentement au survol */}
      {instance.description && (
        <div
          className="overflow-hidden"
          style={{
            maxHeight: hovered ? 60 : 0,
            opacity: hovered ? 1 : 0,
            marginTop: hovered ? 8 : 0,
            marginLeft: 0,
            transition: 'max-height 420ms cubic-bezier(0.16, 1, 0.3, 1), opacity 380ms ease, margin-top 420ms cubic-bezier(0.16, 1, 0.3, 1)',
          }}
        >
          <p style={{ fontSize: 11, color: 'rgba(255,255,255,0.35)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
            {instance.description}
          </p>
        </div>
      )}
    </div>
  )
}
