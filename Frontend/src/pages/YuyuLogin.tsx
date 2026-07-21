import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '@/api/client'
import { useStore } from '@/stores/useStore'
import { showError } from '@/stores/useErrorToast'
import type { Account } from '@/types'

type Mode = 'checking' | 'login' | 'register' | 'error'

export default function YuyuLogin() {
  const navigate = useNavigate()
  const { setYuyuSession, setAccounts, setUser } = useStore()

  const [mode, setMode] = useState<Mode>('checking')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    api.yuyu.status()
      .then((s) => setMode(s.has_account ? 'login' : 'register'))
      .catch(() => setMode('error'))
  }, [])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()

    if (mode === 'register' && password !== confirm) {
      showError('Les mots de passe ne correspondent pas.')
      return
    }
    if (password.length < 4) {
      showError('Le mot de passe doit faire au moins 4 caractères.')
      return
    }

    setLoading(true)
    try {
      const resp = mode === 'register'
        ? await api.yuyu.register(username, password)
        : await api.yuyu.login(username, password)

      // Store token in-memory (NOT in localStorage)
      setYuyuSession(
        resp.token,
        resp.username,
        (resp.plan ?? 'free') as import('@/stores/useStore').YuyuPlan,
        resp.plan_expires_at ?? null,
      )

      // Populate MC accounts from backend response
      const accs: Account[] = resp.accounts.map((a) => ({
        username: a.mc_username,
        uuid: a.mc_uuid,
      }))
      setAccounts(accs)

      // Set active account if one exists
      const active = resp.accounts.find((a) => a.is_active)
      if (active) setUser(active.mc_username, active.mc_uuid)

      navigate('/home', { replace: true })
    } catch (err) {
      showError(err)
    } finally {
      setLoading(false)
    }
  }

  if (mode === 'checking') {
    return (
      <div className="flex h-full items-center justify-center bg-[#09090D]">
        <div
          className="h-8 w-8 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.1)] border-t-[#4B3FCF]"
        />
      </div>
    )
  }

  if (mode === 'error') {
    return (
      <div className="flex h-full items-center justify-center bg-[#09090D]">
        <div className="flex flex-col items-center gap-4">
          <p className="text-[rgba(255,100,100,0.8)] text-[13px]">
            Impossible de contacter le backend.
          </p>
          <button
            onClick={() => { setMode('checking'); api.yuyu.status().then((s) => setMode(s.has_account ? 'login' : 'register')).catch(() => setMode('error')) }}
            className="rounded-xl px-4 py-2 text-sm text-white transition-all bg-[rgba(75,63,207,0.2)] border border-[rgba(75,63,207,0.4)]"
          >
            Réessayer
          </button>
        </div>
      </div>
    )
  }

  const isRegister = mode === 'register'

  return (
    <div className="flex h-full flex-col items-center justify-center overflow-hidden bg-[#09090D]">

      {/* Background glow */}
      <div
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_at_50%_60%,rgba(75,63,207,0.07)_0%,transparent_65%)]"
      />

      <div className="relative z-10 flex w-full max-w-sm flex-col gap-6 px-6">

        {/* Branding */}
        <div className="flex flex-col items-center gap-2">
          <div className="flex items-center gap-2.5">
            <div className="h-5 w-5 rounded-md bg-[#4B3FCF] shadow-[0_0_20px_rgba(75,63,207,0.5)]" />
            <span className="font-black text-white text-[24px] tracking-[-0.02em]">
              YuyuFrame
            </span>
          </div>
          <p className="text-[11px] text-[rgba(255,255,255,0.3)] text-center">
            {isRegister
              ? 'Crée ton compte lanceur pour protéger tes sessions'
              : 'Entre ton mot de passe pour accéder au lanceur'}
          </p>
        </div>

        {/* Card */}
        <div
          className="flex flex-col gap-5 rounded-2xl p-6 bg-[rgba(255,255,255,0.025)] border border-[rgba(255,255,255,0.07)]"
        >
          <div>
            <h2 className="font-bold text-white text-[15px]">
              {isRegister ? 'Créer un compte' : 'Connexion'}
            </h2>
            <p className="text-[11px] text-[rgba(255,255,255,0.3)] mt-0.5">
              {isRegister
                ? 'Mot de passe chiffré avec Argon2 + salt'
                : 'Compte protégé par Argon2'}
            </p>
          </div>

          <form onSubmit={handleSubmit} className="flex flex-col gap-3">
            <YuyuInput
              label="Nom d'utilisateur"
              type="text"
              value={username}
              onChange={setUsername}
              placeholder="ex: Gastly"
              autoFocus
            />
            <YuyuInput
              label="Mot de passe"
              type="password"
              value={password}
              onChange={setPassword}
              placeholder="••••••••"
            />
            {isRegister && (
              <YuyuInput
                label="Confirmer le mot de passe"
                type="password"
                value={confirm}
                onChange={setConfirm}
                placeholder="••••••••"
              />
            )}

            <button
              type="submit"
              disabled={loading || !username || !password}
              className="mt-1 w-full rounded-xl py-3 font-bold text-white transition-all duration-150 active:scale-95 text-[14px] bg-[#4B3FCF] shadow-[0_4px_24px_rgba(75,63,207,0.38)] cursor-pointer enabled:hover:bg-[#6155e8] disabled:bg-[rgba(40,38,65,0.7)] disabled:shadow-none disabled:cursor-not-allowed"
            >
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <span
                    className="h-4 w-4 animate-spin-slow rounded-full border-2 border-[rgba(255,255,255,0.2)] border-t-white"
                  />
                  {isRegister ? 'Création...' : 'Connexion...'}
                </span>
              ) : (
                isRegister ? 'Créer le compte' : 'Se connecter'
              )}
            </button>
          </form>
        </div>

        {/* Toggle login / register */}
        <div className="flex items-center justify-center gap-1.5">
          <span className="text-[11px] text-[rgba(255,255,255,0.3)]">
            {isRegister ? 'Déjà un compte ?' : 'Pas encore de compte ?'}
          </span>
          <button
            type="button"
            onClick={() => { setMode(isRegister ? 'login' : 'register'); setConfirm('') }}
            className="text-[11px] text-[#7B6EE8] bg-transparent border-0 cursor-pointer p-0"
          >
            {isRegister ? 'Se connecter' : 'Créer un compte'}
          </button>
        </div>

        {/* Lock icon + security note */}
        <div className="flex items-center justify-center gap-2">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" className="w-[13px] h-[13px] text-[rgba(255,255,255,0.18)]">
            <rect x="3" y="11" width="18" height="11" rx="2" />
            <path d="M7 11V7a5 5 0 0 1 10 0v4" />
          </svg>
          <span className="text-[10px] text-[rgba(255,255,255,0.18)]">
            Sessions Minecraft stockées localement, chiffrées en base SQLite
          </span>
        </div>
      </div>
    </div>
  )
}

function YuyuInput({
  label, type, value, onChange, placeholder, autoFocus,
}: {
  label: string
  type: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  autoFocus?: boolean
}) {
  return (
    <div>
      <label
        className="mb-1.5 block text-[10px] text-[rgba(255,255,255,0.4)] font-semibold tracking-[0.08em] uppercase"
      >
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoFocus={autoFocus}
        className="w-full rounded-xl px-4 py-3 text-sm text-white outline-none transition-all duration-150 bg-[rgba(0,0,0,0.4)] border border-[rgba(255,255,255,0.08)] focus:border-[rgba(75,63,207,0.55)]"
      />
    </div>
  )
}
