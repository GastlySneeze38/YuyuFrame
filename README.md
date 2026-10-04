# YuyuFrame

Open source Minecraft PvP client for Windows. A launcher with an in-game agent: 25 built-in modules (HUD, keystrokes, zoom, freelook, 1.7 animations…) with no mods to install, and a modernized 1.8.9 running on Java 25 and LWJGL 3.

- Website: https://yuyuframe.eu
- Download: [latest release](https://github.com/GastlySneeze38/YuyuFrame/releases/latest)
- Support: [Discord](https://discord.gg/mX8A6mnssy)

> Unofficial launcher, not affiliated with Mojang or Microsoft.

## Features

**In-game modules** — injected by the agent, no mod loader required. Availability depends on the Minecraft version.

| Family | Modules |
|--------|---------|
| HUD | FPS, ping, coordinates, keystrokes and CPS, armor durability, potion effects, saturation, world time |
| Combat | 1.7 animations, custom crosshair, hurt cam, low health tint |
| Camera | zoom, freelook, FOV |
| Visibility | fullbright, no fog, no darkness, no pumpkin overlay |
| Comfort | toggle sprint / sneak, macros, improved chat, Mumble Link |

Supported versions for the agent: **1.8.9** (vanilla, on Java 25 and LWJGL 3), **1.21.11**, and **26.1 to 26.3**.

**Launcher**

- Isolated instances: Vanilla, Fabric, Forge, NeoForge and Quilt
- Mods, modpacks and resource packs from Modrinth and CurseForge
- Modrinth resource packs installable and applied from inside the game, without restarting Minecraft
- Import of instances from other launchers
- Multiple Microsoft accounts
- Java profiles (Temurin, OpenJ9, GraalVM), reusable across instances
- Game console, play time statistics
- Available in 8 languages: English, French, Spanish, German, Italian, Portuguese (Brazil), Polish, Russian

**Security**

- Sign-in through Microsoft's official page: your password is never typed into the launcher
- Session tokens masked in the console and logs
- Signed updates; every release is built by GitHub Actions from this repository

## Repository layout

```
Backend/         Tauri 2 + Rust — launching, Microsoft auth, instances, local SQLite database
Frontend/        React + TypeScript — interface (Vite, Tailwind, Zustand)
Launcher-Agent/  In-game Java agent (Sponge Mixin), one mixin folder per Minecraft version
  content-core/  Rust JNI library — Modrinth search and downloads from the game
```

The agent jar, its libraries and `content_core.dll` are bundled into the installer (`Backend/tauri.conf.json`, `bundle.resources`) and deployed by the launcher at startup.

## Building from source

Requirements (Windows):

- [Rust](https://rustup.rs) (stable) and the [Tauri 2 prerequisites](https://v2.tauri.app/start/prerequisites/)
- Node.js 20
- JDK 25 — the agent is compiled with `--release 25`
- Tauri CLI: `cargo install tauri-cli --version "^2"`

The agent and the JNI library must be built **before** the launcher, since the Tauri build embeds them. Always build them through their `build.bat`, never with `javac` or `cargo build` by hand:

```bat
cd Launcher-Agent\content-core
build.bat

cd ..
build.bat
```

Then the launcher:

```bat
cd Frontend
npm ci

cd ..\Backend
cargo tauri dev
```

`cargo tauri build` produces the installer. The release workflow (`.github/workflows/release.yml`) runs the same steps.

## Documentation

- [Privacy policy](PRIVACY.md)
- [Terms of use](TERMS.md)

## License

[GPLv3](LICENSE.txt).

## Code signing

Code signing provided by [SignPath Foundation](https://signpath.org).
