# Privacy Policy

**Last updated: July 2026**

## 1. Who we are

YuyuFrame is a Minecraft launcher with cloud sync and peer-to-peer play, developed by Ghasty. The source code for the launcher is available at https://github.com/gastlysneeze38/YuyuFrame-v2.

## 2. Data we collect

**Account data.** When you create a YuyuFrame account, we collect a **username** and a **password**, stored only as an Argon2 hash — never in plain text. We do not ask for or store your email address or real name.

**Minecraft/Microsoft session data.** When you sign in with a Microsoft account to launch Minecraft, the resulting access and refresh tokens are exchanged directly between your device and Microsoft's own services, and stored **locally on your machine only**. They are never sent to or stored on YuyuFrame's servers.

**Subscription and payment data.** Paid plans (Premium, Ultimate) are billed through Stripe and/or Lemon Squeezy. You enter your payment details directly on their hosted checkout page — YuyuFrame's servers never receive or store your card number. We only receive a signed webhook confirming which plan you purchased, which we use to update your account's `plan` status.

**Cloud sync data (Premium/Ultimate, opt-in).** If you use the cloud sync feature, the instance data you choose to push — including your `config/` folder and up to the save folders you select (world data such as region files and player data), plus a manifest of installed mods — is uploaded to our servers so it can be restored on another device. This data is stored until you delete the synced instance or your account. We do not access or inspect its contents outside of what's needed to operate the feature.

**Peer-to-peer (P2P) data.** In P2P mode (Premium/Ultimate), game data is exchanged directly between participants' devices and is not processed, relayed, or stored by our servers. As with any direct peer connection, your IP address may be visible to the other participants in the session.

**What we don't collect.** We do not run any analytics, telemetry, or crash-reporting SDK in the launcher, and our application code does not log or store your IP address. (The underlying hosting infrastructure may retain standard connection logs for security purposes, as is typical for any internet service, but this is not something the application itself collects or exposes.)

## 3. How we use your data

Your data is used to authenticate you, operate the features you actively use (subscription billing, cloud sync), and provide support. We do not use your data for advertising or sell it to third parties.

## 4. Where your data is stored

Account data and, if you use cloud sync, your synced instance data are stored on servers operated by the YuyuFrame developer. Access is protected by HTTPS in transit and requires an authenticated, plan-gated request. Passwords are always hashed with Argon2 and a unique salt — never stored in plain text.

## 5. Your rights (RGPD / GDPR)

Under the General Data Protection Regulation (GDPR), you have the right to:

- **Access** the data we hold about you
- **Correct** inaccurate data
- **Delete** your account, including any cloud-synced instance data
- **Object** to the processing of your data

To exercise any of these rights, please open an issue on our GitHub repository: https://github.com/gastlysneeze38/YuyuFrame-2/issues

## 6. Data retention

Account data is retained for as long as your account exists. Cloud-synced instance data is retained until you delete that instance or your account. If you request account deletion, all associated data is permanently removed.

## 7. Third parties and sub-processors

We share data with the following providers, strictly for the purpose of running the feature they support:

- **Stripe** / **Lemon Squeezy** — payment processing (they receive your billing details directly; we only receive a plan/status webhook)
- **Microsoft** — account authentication for launching Minecraft (handled directly between your device and Microsoft, not via our servers)
- **Modrinth** — mod and resource pack search/download

These providers process data under their own privacy policies.

## 8. Changes to this policy

We may update this Privacy Policy from time to time. Changes will be reflected by the "Last updated" date at the top of this document.
