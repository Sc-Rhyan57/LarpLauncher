# Larp Launcher

Open-source game launcher e patcher para mods de jogos Unity/IL2CPP no Android. Reconstrução em Kotlin com Jetpack Compose e Material Expressive (Material You), inspirado no projeto ReVanced Manager e com sistema de salas/broadcast/mural/voz estilo Regis Launcher.

[![Build](https://github.com/Sc-Rhyan57/LarpLauncher/actions/workflows/build.yml/badge.svg)](https://github.com/<owner>/larp-launcher/actions)
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
| Mods do servidor | Pronto | Catálogo, download, validação SHA-256, descriptografia AES-CBC |
| Meus mods (DLL local) | Pronto | Upload de DLL + capa + nome/descrição/autor |
| Salas de jogo | Pronto | Criar/entrar/sair, lista em tempo real (WebSocket) |
| Mural de avisos | Pronto | CRUD admin + push em tempo real |
| Broadcast ADM | Pronto | Mensagens instantâneas com severidade Info/Warn/Critical |
| Chat de voz (WebRTC) | Scaffolding | Cloudflare Calls + ICE servers |
| Malum (tutorial) | Pronto | 6 passos + aviso de uso responsável |
| Ajustes | Pronto | Tema dinâmico, dark mode, URL da API |
| Patcher (ModSync/ModRuntime) | Portado | Portado do Java original do Regis Launcher |

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

```bash
git clone https://github.com/<owner>/larp-launcher.git
cd larp-launcher
./gradlew :app:assembleStableDebug
```

APKs saem em `app/build/outputs/apk/<flavor>/debug/`.

### Flavors

| Flavor | Sufixo do applicationId | Uso |
|--------|-------------------------|-----|
| `stable` | — | Versão estável para produção |
| `preview` | `.preview` | Beta testes |
| `canary` | `.canary` | Builds experimentais |

## Configuração

### 1. URL do backend

Por padrão o app lê o URL em `app/src/main/assets/servidor.txt`. Substitua pelo URL do seu Worker Cloudflare:

```
https://larp-api.seu-dominio.workers.dev
```

Ou edite `BuildConfig.API_BASE_URL` em `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "API_BASE_URL", "\"https://larp-api.seu-dominio.workers.dev\"")
```

### 2. Discord OAuth

Veja [Discord OAuth](#discord-oauth).

### 3. Permissões

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

Há duas origens de mods:

1. **Mods do servidor** (`feature/mods/browser/`) — vindos do backend Cloudflare, validados com SHA-256 + AES-CBC (formato RGM1). Veja `patcher/ModPackage.kt` e `patcher/ModSync.kt`.
2. **Meus mods** (`feature/mods/mine/`) — DLLs locais enviadas pelo usuário, com nome/descrição/autor/versão e capa personalizáveis. Ficam em `filesDir/mods/local/`.

Os dois tipos são aplicados ao diretório `BepInEx/plugins/` antes de abrir o jogo. O runtime .NET é extraído do APK (asset `dotnet-arm64.zip`).

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
