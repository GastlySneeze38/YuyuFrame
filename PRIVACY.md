# Privacy Policy

**Last updated: October 2026**

## 1. Who we are

YuyuFrame is a Minecraft launcher developed by Ghasty. The source code of the launcher is public: https://github.com/GastlySneeze38/YuyuFrame. This policy describes what the launcher and the YuyuFrame servers do with your data. You are asked to accept it when you create a YuyuFrame account.

A YuyuFrame account is optional: the launcher installs and starts Minecraft without one. An account is needed for a subscription, cloud sync, support tickets, crash reports and reviews.

## 2. Data we collect

**Account data.** When you create a YuyuFrame account we collect a **username**, an **e-mail address** and a **password**. The e-mail address is required and must be confirmed with a code we send to it. The password is stored only as an Argon2id hash, never in plain text. We do not ask for your real name, date of birth or postal address. We also record the date on which you accepted this policy.

**Password check.** When you choose a password, we check that it does not appear in known data breaches, using the Have I Been Pwned service. Only the first five characters of a SHA-1 hash of the password are sent; neither the password nor its full hash ever leaves our server.

**Two-step sign-in.** Signing in requires a second step: a code sent to your e-mail address, or a code from an authenticator app if you set one up. The authenticator secret is stored encrypted, and your backup codes are stored as hashes. Our support staff cannot read either.

**Devices and sessions.** For each device you sign in from, we store the computer name, the operating system, the launcher version, an installation identifier created by the launcher, the IP address and the dates of first and last use. You can see these devices and sign them out from the account screen of the launcher.

**Device fingerprint.** The launcher sends a fingerprint of your computer: a salted SHA-256 hash of an identifier provided by the operating system. The raw identifier never leaves your computer. The fingerprint has one purpose: making a ban apply to the person rather than to one account. If an account is banned, its fingerprints and its e-mail address are kept on a block list for as long as the ban lasts.

**Security e-mails.** We e-mail you when something important happens on your account: sign-in from a new device, password changed or reset, e-mail address changed, authenticator app enabled, backup codes regenerated.

**Minecraft and Microsoft accounts.** When you sign in with Microsoft to play, the access and refresh tokens are exchanged directly between your computer and Microsoft, and stay **on your computer only**. They are never sent to YuyuFrame servers. If you are signed in to YuyuFrame, the launcher sends us the Minecraft username and UUID of the accounts you added, so that support can find your account from your Minecraft name.

**Subscription and payment.** Paid plans are billed through Stripe or Lemon Squeezy. You enter your payment details on their hosted page; YuyuFrame servers never receive your card number. We receive a signed notification saying which plan was bought, for which account, and the amount, and we keep it in a billing journal.

**Cloud sync (paid plans, opt-in).** If you use cloud sync, the instance data you choose to send (configuration files, the save folders you select, and the list of installed mods) is uploaded to our servers so it can be restored on another computer. We do not inspect its contents beyond what is needed to run the feature.

**Usage statistics.** The launcher sends anonymous usage events (for example: launcher started, instance created, content installed, game launched) to PostHog, hosted in the European Union. They carry the installation identifier, the launcher version and the operating system, not your username or your e-mail address. You can turn them off at any time in the launcher settings.

**Crash reports.** Nothing is sent automatically. When Minecraft crashes, the launcher builds a report on your computer and shows it to you; it is sent only if you click the button. A report contains the error and the end of the game log, the list of installed mods, the Minecraft, mod loader and Java versions, the memory settings and Java flags, and a description of your machine (operating system, processor, graphics card, amount of memory). Sign-in tokens, e-mail addresses and paths containing your user folder are masked before the report is stored. The report is attached to your account so you can follow its status.

**Support tickets.** A ticket contains what you write and, if you choose to attach it, a diagnostic of your installation. Our team answers from a private channel on Discord: your messages are copied there, visible to the team only.

**Reviews.** If you leave a review from the launcher, your rating, your text and your username are published on the YuyuFrame website. You can edit or delete your review at any time.

**Language detection.** At the very first start, the launcher makes one request to Cloudflare to guess your country and pick a language. The result is not stored and not sent to us.

**What stays on your computer.** Your instances, worlds and settings, your Microsoft tokens, and the links you create to share an instance, options or a skin. Share links contain the data themselves and do not go through our servers.

## 3. How we use your data

Your data is used to sign you in and protect your account, to run the features you use (subscription, cloud sync, support, crash reports, reviews), to enforce bans, and to understand how the launcher is used so we can improve it. We do not use your data for advertising and we do not sell it.

## 4. Where your data is stored and how it is protected

Account data, and cloud sync data if you use it, are stored on a server operated by the YuyuFrame developer. Connections use HTTPS. Passwords are hashed with Argon2id and a unique salt.

The databases are backed up every night. Backups are **encrypted before they leave our server** and stored with Backblaze B2; Backblaze cannot read them. They are kept for a limited period (35 days at most) and cannot be altered or deleted during that period. The contents of cloud sync are not included in these backups.

Access by our team goes through a private administration panel that is not reachable from the Internet, requires two-step sign-in, and records every action in an audit log.

## 5. Your rights (GDPR)

You have the right to:

- **Access** the data we hold about you, and receive a copy of it
- **Correct** inaccurate data (you can change your e-mail address and password from the launcher)
- **Delete** your account and the data attached to it
- **Object** to a processing of your data, or ask us to restrict it

To exercise these rights, open a support ticket from the launcher (Support screen) or contact us on the YuyuFrame Discord server. Please do not post personal data in a public GitHub issue. You also have the right to lodge a complaint with your data protection authority.

## 6. Data retention

- **Account data**: as long as your account exists.
- **Cloud sync data**: until you delete the synced instance or your account.
- **Sessions and devices**: until you sign the device out; a device unused for 60 days no longer appears in your list.
- **Crash reports and support tickets**: until you delete them or your account. When an account is deleted, its crash reports are detached from it: the technical cause is kept, without any link to you.
- **Billing journal**: kept after account deletion, because accounting rules require it.
- **Ban block list**: for as long as the ban lasts.
- **Backups**: a deleted account can remain in encrypted backups until they expire, 35 days at most.

## 7. Third parties

We rely on the following providers, each strictly for the feature it supports:

- **Stripe** and **Lemon Squeezy**: payment (they receive your billing details directly)
- **Microsoft** and **Mojang**: Minecraft sign-in and skins, directly from your computer
- **Modrinth** and **CurseForge**: search and download of mods, modpacks, resource packs and shaders
- **Ely.by**: the skin catalogue
- **PostHog** (European Union): anonymous usage statistics, which you can turn off
- **Backblaze B2**: storage of encrypted backups
- **An e-mail delivery provider**: sending confirmation codes and security e-mails to your address
- **Discord**: the private channel where our team answers support tickets
- **Cloudflare**: the one-time country lookup at first start
- **Have I Been Pwned**: the breached-password check described above

Downloading the game, Java and mod loaders also contacts the servers of their publishers (Mojang, Adoptium, Fabric, Forge, NeoForge, Quilt). Like any server you connect to, they see your IP address. These providers process data under their own privacy policies.

## 8. Changes to this policy

We may update this policy. The date at the top shows the latest version, and the current text is always available in the launcher (Legal page, linked from the home screen and from the account creation form) and in the public repository.
