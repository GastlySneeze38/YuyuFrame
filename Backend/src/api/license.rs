// Licence signée Ed25519 : la couche qui protège le joueur (voir
// docs/product/README-billing-integrity.md §5, couche 4). Le serveur la
// délivre à chaque connexion ; on la vérifie ICI, hors ligne, avec la clé
// publique embarquée. Serveur en panne, base indisponible ou pas d'Internet :
// le joueur garde ce qu'il a payé jusqu'à `exp`, puis 7 jours de grâce.
//
// Algorithme de référence : Server/LauncherAPI/src/billing/license.rs (`verify`).
// Format : base64url(JSON) « . » base64url(signature des octets du JSON).

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ed25519_dalek::{Verifier, VerifyingKey};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Clés publiques acceptées, en base64. Plusieurs pendant une rotation : la
/// nouvelle en premier, l'ancienne gardée le temps que tout le monde se
/// reconnecte. `kid` désigne celle qui a signé.
const PUBLIC_KEYS: &[&str] = &[
    // Production, générée le 2026-09-19.
    "uKov09/do8hhZrFnhZjPi0/jqpP9UG9wi/6lMr/MOGk=",
];

/// Après `exp`, le launcher laisse encore une semaine avant de repasser en
/// gratuit — le temps de retrouver du réseau ou que le serveur revienne.
pub const GRACE_DAYS: i64 = 7;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct LicensePayload {
    pub v: u8,
    pub kid: String,
    pub sub: i64,
    pub plan: String,
    /// Fin du droit payé, None = sans fin.
    pub plan_ends_at: Option<i64>,
    pub iat: i64,
    /// Au-delà : grâce, puis retour au gratuit.
    pub exp: i64,
}

/// État du plan déduit d'une licence, sans réseau.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LicenseState {
    /// Licence à jour.
    Valid,
    /// Périmée mais dans les 7 jours de grâce : le plan tient encore, avec un
    /// bandeau d'information.
    Grace,
    /// Trop vieille, ou plan terminé : gratuit.
    Expired,
}

fn key_id(public: &VerifyingKey) -> String {
    let digest = Sha256::digest(public.as_bytes());
    digest.iter().take(4).map(|b| format!("{b:02x}")).collect()
}

fn accepted_keys() -> Vec<VerifyingKey> {
    PUBLIC_KEYS
        .iter()
        .filter_map(|k| {
            let bytes: [u8; 32] = base64::engine::general_purpose::STANDARD.decode(k).ok()?.try_into().ok()?;
            VerifyingKey::from_bytes(&bytes).ok()
        })
        .collect()
}

/// Vérifie la signature. Ne regarde aucune date : c'est le rôle de `state`.
pub fn verify(license: &str) -> Option<LicensePayload> {
    verify_with(license, &accepted_keys())
}

fn verify_with(license: &str, keys: &[VerifyingKey]) -> Option<LicensePayload> {
    let (payload_b64, sig_b64) = license.split_once('.')?;
    let payload = URL_SAFE_NO_PAD.decode(payload_b64).ok()?;
    let sig: [u8; 64] = URL_SAFE_NO_PAD.decode(sig_b64).ok()?.try_into().ok()?;
    let signature = ed25519_dalek::Signature::from_bytes(&sig);
    let parsed: LicensePayload = serde_json::from_slice(&payload).ok()?;
    keys.iter()
        .find(|k| key_id(k) == parsed.kid)
        .filter(|k| k.verify(&payload, &signature).is_ok())
        .map(|_| parsed)
}

/// Plan utilisable hors ligne : le droit vaut jusqu'à la plus proche des deux
/// échéances, la fin du plan payé et la validité de la licence (+ grâce).
pub fn plan_at(payload: &LicensePayload, now: i64) -> (String, LicenseState) {
    let ended = payload.plan_ends_at.is_some_and(|e| e <= now);
    if payload.plan == "free" || ended || now > payload.exp + GRACE_DAYS * 86_400 {
        return ("free".into(), LicenseState::Expired);
    }
    let state = if now <= payload.exp { LicenseState::Valid } else { LicenseState::Grace };
    (payload.plan.clone(), state)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};

    fn issue(key: &SigningKey, payload: &LicensePayload) -> String {
        let json = serde_json::to_vec(payload).unwrap();
        let sig = key.sign(&json);
        format!("{}.{}", URL_SAFE_NO_PAD.encode(&json), URL_SAFE_NO_PAD.encode(sig.to_bytes()))
    }

    fn payload(key: &SigningKey, plan: &str, ends_at: Option<i64>, exp: i64) -> LicensePayload {
        LicensePayload {
            v: 1,
            kid: key_id(&key.verifying_key()),
            sub: 42,
            plan: plan.into(),
            plan_ends_at: ends_at,
            iat: 0,
            exp,
        }
    }

    fn test_key() -> SigningKey {
        SigningKey::from_bytes(&[7u8; 32])
    }

    #[test]
    fn signature_must_match_an_accepted_key() {
        let key = test_key();
        let keys = vec![key.verifying_key()];
        let license = issue(&key, &payload(&key, "premium", Some(1_000), 900));

        assert_eq!(verify_with(&license, &keys).unwrap().plan, "premium");
        // Contenu modifié à la main : refusé.
        let (p, sig) = license.split_once('.').unwrap();
        let forged = String::from_utf8(URL_SAFE_NO_PAD.decode(p).unwrap()).unwrap().replace("premium", "ultimate");
        assert!(verify_with(&format!("{}.{sig}", URL_SAFE_NO_PAD.encode(forged)), &keys).is_none());
        // Signée par quelqu'un d'autre : refusée.
        let other = SigningKey::from_bytes(&[9u8; 32]);
        assert!(verify_with(&license, &[other.verifying_key()]).is_none());
        assert!(verify_with("n'importe quoi", &keys).is_none());
    }

    #[test]
    fn grace_period_then_free() {
        let key = test_key();
        let day = 86_400;
        let p = payload(&key, "premium", Some(100 * day), 30 * day);

        assert_eq!(plan_at(&p, 10 * day), ("premium".into(), LicenseState::Valid));
        // Licence périmée depuis 3 jours : encore bon, avec bandeau.
        assert_eq!(plan_at(&p, 33 * day), ("premium".into(), LicenseState::Grace));
        // Au-delà de 7 jours de grâce : gratuit.
        assert_eq!(plan_at(&p, 38 * day), ("free".into(), LicenseState::Expired));
        // Abonnement terminé avant la licence : gratuit tout de suite.
        let court = payload(&key, "premium", Some(5 * day), 30 * day);
        assert_eq!(plan_at(&court, 6 * day), ("free".into(), LicenseState::Expired));
    }

    #[test]
    fn production_public_key_is_usable() {
        assert_eq!(accepted_keys().len(), PUBLIC_KEYS.len(), "clé publique embarquée illisible");
    }
}
