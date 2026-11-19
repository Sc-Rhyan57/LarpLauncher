# Larp Launcher

Open-source game launcher e patcher para mods de jogos Unity/IL2CPP no Android. Reconstrução em Kotlin com Jetpack Compose e Material Expressive (Material You), inspirado no projeto ReVanced Manager e com sistema de salas/broadcast/mural/voz estilo Regis Launcher.

[![Build](https://github.com/<owner>/larp-launcher/actions/workflows/build.yml/badge.svg)](https://github.com/<owner>/larp-launcher/actions)
[![License: GPLv3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-green.svg)](https://developer.android.com/about/versions/oreo)

## Sumário

- [O que é](#o-que-é)
- [Funcionalidades](#funcionalidades)
- [Stack técnica](#stack-técnica)
- [Roadmap](#roadmap)
- [Build](#build)
- [Configuração](#configuração)
- [Discord OAuth](#discord-oauth)
- [Backend Cloudflare](#backend-cloudflare)
- [Arquitetura](#arquitetura)
- [Sistema de mods](#sistema-de-mods)
- [Malum (tutorial)](#malum-tutorial)
- [Licença](#licença)
- [Agradecimentos](#agradecimentos)

## O que é

O **Larp Launcher** é um aplicativo Android que:

1. Faz **login via Discord** (OAuth2 PKCE).
2. Sincroniza **mods oficiais** hospedados em backend Cloudflare Workers.
3. Permite ao usuário fazer **upload de DLLs locais** e personalizar nome, descrição e capa (armazenados em cache local).
4. **Prepara o runtime** (BepInEx + .NET) e aplica os mods antes de abrir o jogo base.
5. Oferece **salas de jogo** (com criador/ADM, lista de jogadores, mods por sala).
6. **Mural de avisos** persistente (somente ADM publica) + **broadcast** em tempo real.
7. **Chat de voz** via WebRTC (Cloudflare Calls) dentro das salas.
8. **Malum** — tutorial interno que ensina a rodar os mods.

## Funcionalidades

| Módulo | Status | Descrição |
|--------|--------|-----------|
| Login Discord OAuth2 + PKCE | Pronto | Token armazenado em EncryptedSharedPreferences |
| Injeção in-process (GameActivity) | Pronto | Jogo roda dentro do processo do launcher; libfusion.so bootstrapa BepInEx + CoreCLR + Dobby |
| Mods do servidor (BepInEx RGM1) | Pronto | Catálogo, download, validação SHA-256, descriptografia AES-CBC |
| Meus mods (DLL local) | Pronto | Upload de DLL + capa + nome/descrição/autor |
| Frida Mods (MalumMenu-style) | Pronto | Upload de agent.js + libfrida-gadget.so bundled |
| Salas de jogo | Pronto | Criar/entrar/sair, lista em tempo real (WebSocket) |
| Mural de avisos | Pronto | CRUD admin + push em tempo real |
| Broadcast ADM | Pronto | Mensagens instantâneas com severidade Info/Warn/Critical |
| Chat de voz (WebRTC) | Scaffolding | Cloudflare Calls + ICE servers |
| Malum (tutorial) | Pronto | 6 passos + aviso de uso responsável |
| Ajustes | Pronto | Tema dinâmico, dark mode, URL da API |
| Patcher (ModSync/ModRuntime) | Portado | Portado do Java original do Regis Launcher |
| CI/CD | Pronto | workflow_dispatch com flavor + buildType + release automático |

## Stack técnica

- **Kotlin 2.0.21** + Coroutines 1.9
- **Jetpack Compose** (BOM 2024.12) + Material 3 (Material You dinâmico)
- **Hilt** 2.52 (DI)
- **Retrofit 2.11** + OkHttp 4.12 + kotlinx.serialization
- **Room** 2.6 (DB local para mods locais e instalados)
- **DataStore** (preferências) + **EncryptedSharedPreferences** (auth)
- **Coil** (imagens) + **Media3** (playback opcional)
- **Accompanist Permissions**
- **AndroidX Browser / Custom Tabs** (login Discord)
- **Min SDK 26** (Android 8.0+), **Target SDK 35**

## Roadmap

- [ ] Integração completa com ReVanced Patcher para APKs genéricos
- [ ] Backup/restore de perfil de mods via Google Drive
- [ ] Suporte a múltiplos jogos base
- [ ] i18n (en, es, fr)
- [ ] Verificação de integridade do jogo base
- [ ] WebRTC mesh completo com Cloudflare Calls

## Build

### Pré-requisitos

- JDK 17 (Temurin)
- Android SDK API 35 (compileSdk) e NDK 27.2.12479018
- (opcional) `libfrida-gadget.so` em `app/src/main/jniLibs/arm64-v8a/` se `LARP_BUNDLE_FRIDA_GADGET=true`
- (opcional) `libfusion.so` + `libcoreclr.so` em `app/src/main/jniLibs/arm64-v8a/` para injeção BepInEx

### Local

```bash
git clone https://github.com/<owner>/larp-launcher.git
cd larp-launcher
./setup.sh
./gradlew :app:assembleStableDebug
```

APKs saem em `app/build/outputs/apk/<flavor>/<buildType>/`.

### CI (GitHub Actions)

O workflow é **apenas `workflow_dispatch`** (manual). Não dispara em push/PR/tags automaticamente.

Para buildar via CI:

1. Vá em **Actions → Build → Run workflow**
2. Escolha:
   - `buildType` — `debug` ou `release`
   - `flavor` — `stable`, `preview` ou `canary`
   - `createRelease` — `true` (só em `release`) para publicar um release no GitHub

O job baixa `libfrida-gadget.so` automaticamente do GitHub Releases da Frida (versão `larpFridaVersion`), descompacta com `xz-utils` (instalado no runner) e inclui no APK.

### Flavors

| Flavor | Sufixo do applicationId | Uso |
|--------|-------------------------|-----|
| `stable` | — | Versão estável para produção |
| `preview` | `.preview` | Beta testes |
| `canary` | `.canary` | Builds experimentais |

## Configuração

O Larp Launcher tem **dois tipos** de configuração:

### 1. ENVs de RUNTIME (dentro do app)

Todas as ENVs são configuradas **dentro do app** em **Ajustes → Servidor/Discord/Voz** e salvas em `filesDir/larp-env.json`. Não precisa rebuildar o APK para mudar. Os defaults vêm embutidos em `app/src/main/assets/larp-env.json`.

Campos disponíveis:

| Campo | Default | Descrição |
|-------|---------|-----------|
| `apiUrl` | `https://larp-api.workers.dev` | URL HTTP do Worker Cloudflare |
| `wsUrl` | `wss://larp-api.workers.dev` | URL WebSocket (deriva de `apiUrl` se vazio) |
| `discordClientId` | (vazio) | Client ID da aplicação Discord |
| `discordRedirectUri` | `com.larp.launcher://oauth` | Deep link OAuth |
| `discordScope` | `identify email guilds` | Escopos OAuth |
| `webrtcIceServers` | `stun:stun.larp.workers.dev:3478` | ICE servers (vírgula-separados) |
| `turnUrl`/`turnUser`/`turnCredential` | (vazio) | TURN server |
| `gamePackage` | `com.innersloth.spacemafia` | Package name do jogo |
| `sentryDsn`/`fcmSenderId` | (vazio) | Telemetria opcional |

Veja [`docs/ENVS.md`](docs/ENVS.md) para detalhes.

### 2. Build-time (uma só)

| Property | Default | Descrição |
|----------|---------|-----------|
| `larpFridaVersion` | `17.2.1` | Versão do Frida gadget a baixar automaticamente do GitHub Releases |

### 3. Frida gadget (download automático)

O `libfrida-gadget.so` é baixado automaticamente pela task Gradle `downloadFridaGadget` que roda antes de qualquer `assemble*` / `bundle*`. Baixa de `https://github.com/frida/frida/releases` (versão `larpFridaVersion`) e coloca em `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/`.

Pré-requisito: `xz-utils` instalado (Ubuntu: `sudo apt install xz-utils`). Em CI, o workflow já instala.

### 4. Discord OAuth

Veja [Discord OAuth](#discord-oauth).

### 5. Permissões

O app pede permissão de microfone (chat de voz) e acesso a imagens/docs (upload de DLL). Para o login Discord, usamos Custom Tabs — não há SDK oficial do Discord embutido.

## Discord OAuth

O Larp Launcher implementa OAuth2 PKCE contra o Discord via seu backend Cloudflare. Fluxo:

1. O app gera um `verifier` aleatório e calcula `challenge = SHA256(verifier)` (base64url).
2. O app abre `GET /auth/login?challenge=<challenge>` no backend.
3. O backend retorna o URL de autorização do Discord (`/auth/discord?state=<challenge>`).
4. O app abre o URL no Custom Tab; o usuário autoriza no Discord.
5. Discord redireciona para o backend; o backend repassa um `grant` para o app via deep link `com.larp.launcher://oauth?grant=<token>`.
6. O app chama `POST /auth/token` com `{grant, verifier}` e recebe `{token, expiresAt, user}`.
7. Token armazenado em EncryptedSharedPreferences (`larp_auth`).

Para configurar no Discord:

1. Crie uma aplicação em <https://discord.com/developers/applications>.
2. Adicione redirect URI: `<seu-backend-url>/auth/discord/callback`.
3. Defina `DISCORD_CLIENT_ID` e `DISCORD_REDIRECT_URI` no `build.gradle.kts`.

Veja `docs/API.md` para o contrato completo do backend.

## Backend Cloudflare

Veja [`docs/API.md`](docs/API.md) para o contrato completo da API que o app espera.

Stack sugerida:

- **Cloudflare Workers** (lógica de auth, salas, mods)
- **KV** (sessões, presença)
- **Queues** (eventos de broadcast, mensagens assíncronas)
- **Durable Objects** (salas em tempo real, voice signaling) — opcional

Template do Worker será publicado separadamente em `larp-backend` (a fazer).

## Arquitetura

Veja [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

Resumo:

```
┌─────────────────────────────────────────────────────────┐
│  Compose UI (feature/*)                                  │
├─────────────────────────────────────────────────────────┤
│  ViewModel (MVVM, StateFlow)                             │
├─────────────────────────────────────────────────────────┤
│  Repository (data/repository/*)                          │
├─────────────────────────┬───────────────────────────────┤
│ Remote (Retrofit + WS)  │ Local (Room + DataStore +     │
│                         │ EncryptedPrefs + CacheManager)│
├─────────────────────────┴───────────────────────────────┤
│  Patcher (ModPackage, ModSync, ModRuntime, GameLauncher)│
└─────────────────────────────────────────────────────────┘
```

## Sistema de mods

Há **três** caminhos de injeção de mods, todos opcionais e complementares:

### 1. BepInEx (RGM1) — mods do servidor

Mods oficiais hospedados no backend Cloudflare, validados com SHA-256 + AES-CBC (formato RGM1). O usuário escolhe na aba **Mods**, o launcher baixa + descriptografa + extrai para `BepInEx/plugins/`. Veja `patcher/ModPackage.kt` e `patcher/ModSync.kt`.

### 2. DLLs locais — Meus mods

DLLs .NET (BepInEx plugins) enviadas pelo usuário na aba **Meus Mods**, com nome/descrição/autor/versão e capa personalizáveis. Ficam em `filesDir/mods/local/` e são copiadas para `BepInEx/plugins/` antes de abrir o jogo.

### 3. Frida gadget — estilo MalumMenu (NOVO)

Injeção via **libfrida-gadget.so** + **agent.js** (compilado por `frida-compile`, igual ao MalumMenu). O usuário sobe um `agent.js` na aba **Frida Mods**, e o launcher instala como `libfrida-gadget.script.so` ao lado do gadget. Ao abrir o jogo, o gadget carrega o agente que usa `frida-il2cpp-bridge` para hookar o IL2CPP.

Para ativar:
1. Baixe `libfrida-gadget.so` de <https://github.com/frida/frida/releases> (arm64) e coloque em `app/src/main/jniLibs/arm64-v8a/`.
2. Configure `LARP_BUNDLE_FRIDA_GADGET=true` (veja `docs/ENVS.md`).
3. Build + instale.
4. Abra **Frida Mods** no app, suba seu `agent.js`, toque em "Injetar e jogar".

## Injeção (como funciona)

O Larp Launcher não abre o jogo num processo separado. Em vez disso, **roda o jogo dentro do processo do launcher** via `GameActivity`:

1. `GameEnv` cria um `PathClassLoader` apontando para o APK do jogo + um `larp-boot.dex` + a lib search path que inclui as `.so` bundled do launcher (`libfusion.so`, `libcoreclr.so`, etc.).
2. `GameContext` é um `ContextWrapper` que sobrescreve `getClassLoader()`, `getAssets()`, `getResources()`, `getApplicationInfo()`, `getPackageName()` para apontar ao `GameEnv`.
3. `GameActivity` carrega `com.unity3d.player.UnityPlayer` via reflection e instancia com `this` como Context (que é o `GameContext`).
4. Antes de chamar o Unity, `ModRuntime.prepare()` extrai `dotnet-arm64.zip` + `BepInEx-arm64.zip` e seta env vars (`FUSION_BEPINEX_PATH`, `FUSION_GAME_BINARY`, `LARP_DOTNET_DIR`, etc.).
5. `Boot.load("fusion")` chama `System.loadLibrary("fusion")` → `libfusion.so` bootstrapa CoreCLR + BepInEx + Dobby → hook em `libil2cpp.so`.
6. O jogo (rodando no processo do launcher) carrega `libil2cpp.so` e os hooks do BepInEx interceptam chamadas IL2CPP.

Se `libfrida-gadget.so` estiver bundled, o caminho Frida também carrega o gadget + agent.js em vez do BepInEx.

Sem `libfusion.so` + `libcoreclr.so` em `jniLibs/`, o jogo abre mas sem mods (apenas o caminho Frida funcionaria, se a gadget estiver bundled). Veja `app/src/main/jniLibs/README.txt` e `docs/ENVS.md`.

## Malum (tutorial)

A aba **Malum** é APENAS um guia — não executa nada. Mostra 6 passos de como preparar e rodar os mods, com um aviso final: _"Use apenas mods autorizados pelo ADM do servidor"_.

## Licença

GPL-3.0-or-later. Veja [LICENSE](LICENSE).

## Agradecimentos

- **ReVanced Team** — pelo ReVanced Manager, inspiração original.
- **Regis Launcher** — pela lógica de ModPackage/ModSync/ModRuntime que foi portada.
- **BepInEx Team** — pelo runtime de mods usado no Android.
- **Google Material Design** — pelo Material Expressive.
- **Cloudflare** — pela stack de backend (Workers + KV + Queues + Calls).
