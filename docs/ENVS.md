# Variáveis de ambiente do Larp Launcher

O Larp Launcher tem **dois tipos** de configuração:

## 1. ENVs de RUNTIME (lidas pelo app, NÃO precisam estar em build)

Estas são configuradas **dentro do app** em **Ajustes → Servidor/Discord/Voz** e salvas em `filesDir/larp-env.json`. Os defaults vêm embutidos em `app/src/main/assets/larp-env.json`.

Para gerar um APK com defaults diferentes (ex.: apontando para outro Worker), basta editar `app/src/main/assets/larp-env.json` antes de buildar — **nenhuma ENV de sistema** precisa ser setada.

| Campo | Default | Descrição |
|-------|---------|-----------|
| `apiUrl` | `https://larp-api.workers.dev` | URL HTTP do seu Worker Cloudflare. Sem barra final. |
| `wsUrl` | `wss://larp-api.workers.dev` | URL WebSocket. Se vazio, deriva de `apiUrl` (http→ws). |
| `discordClientId` | (vazio) | Client ID da aplicação Discord. **Obrigatório** para login. |
| `discordRedirectUri` | `com.larp.launcher://oauth` | Deep link de retorno do OAuth. |
| `discordScope` | `identify email guilds` | Escopos OAuth do Discord. |
| `webrtcIceServers` | `stun:stun.larp.workers.dev:3478` | Lista de ICE servers (separados por vírgula). |
| `turnUrl` | (vazio) | URL do TURN server (`turn:turn.larp.workers.dev:3478`). |
| `turnUser` | (vazio) | Usuário TURN. |
| `turnCredential` | (vazio) | Senha TURN. |
| `gamePackage` | `com.innersloth.spacemafia` | Package name do jogo alvo. |
| `fridaGadgetBundled` | `true` | (auto) `true` se `libfrida-gadget.so` foi bundled no build. |
| `sentryDsn` | (vazio) | DSN do Sentry. Vazio = telemetria off. |
| `fcmSenderId` | (vazio) | Sender ID do Firebase Cloud Messaging para push. |

### Como o app resolve cada ENV em runtime

1. `filesDir/larp-env.json` (salvo pelo SettingsScreen) — **maior precedência**
2. `assets/larp-env.json` (defaults embutidos no APK)
3. Hardcoded defaults na classe `LarpEnv`

O usuário pode mudar tudo em Ajustes → Servidor/Discord/Voz → Salvar ENVs. Não precisa rebuildar o APK.

### Discord Client Secret

`DISCORD_CLIENT_SECRET` **não** vai no APK — fica só no Worker Cloudflare como `secrets.DISCORD_CLIENT_SECRET` no `wrangler.toml`.

## 2. ENVs de BUILD (apenas uma)

| ENV/Property | Onde | Default | Descrição |
|--------------|------|---------|-----------|
| `larpFridaVersion` | `gradle.properties` | `17.2.1` | Versão do Frida gadget a baixar do GitHub Releases. |
| `LARP_VERSION_CODE` | ENV/CI | `1` | Version code. CI usa `github.run_number`. |
| `LARP_VERSION_NAME` | ENV/CI | `0.1.0` | Version name human-readable. |
| `LARP_GIT_SHA` | ENV/CI | `local` | SHA do commit. CI usa `github.sha`. |

## Frida gadget (download automático)

O `libfrida-gadget.so` é baixado automaticamente pelo Gradle task `downloadFridaGadget` que roda antes de `assemble*` / `bundle*`. Ele baixa a versão configurada em `larpFridaVersion` do GitHub Releases da Frida e coloca em:

```
app/src/main/jniLibs/
├── arm64-v8a/libfrida-gadget.so      (~50 MB)
├── armeabi-v7a/libfrida-gadget.so    (~40 MB, falha graceful se falhar)
└── x86_64/libfrida-gadget.so         (~50 MB)
```

Para mudar a versão, edite `gradle.properties`:

```properties
larpFridaVersion=17.2.1
```

Para forçar re-download, delete `app/src/main/jniLibs/*/libfrida-gadget.so` e rode `./gradlew downloadFridaGadget` ou `./gradlew assembleStableDebug`.

**Pré-requisito:** `xz-utils` instalado (Ubuntu: `apt install xz-utils`).

Em CI (GitHub Actions), o workflow já tem `xz-utils` instalado via `android-actions/setup-android@v3`.

## Mods do Regis Launcher (DLL, BepInEx, RGM1) — NÃO REMOVIDOS

O Larp Launcher mantém **todos** os caminhos de mods do Regis Launcher originais:

1. **Mods do servidor (BepInEx RGM1)** — `feature/mods/browser/` + `patcher/ModPackage.kt` + `patcher/ModSync.kt`
   - Catálogo, download com SHA-256, descriptografia AES-CBC, extração para `BepInEx/plugins/`.

2. **Meus Mods (DLL local)** — `feature/mods/mine/` + `ModsRepository.addLocalMod(...)`
   - Upload de `.dll` (BepInEx plugin), com nome/descrição/autor/versão/capa.
   - Salvo em `filesDir/mods/local/` e copiado para `BepInEx/plugins/` antes de abrir o jogo.

3. **Frida Mods (MalumMenu-style)** — `feature/frida/` + `patcher/frida/FridaInjector.kt`
   - Upload de `agent.js`, instala como `libfrida-gadget.script.so`.

Os três coexistem. O fluxo de lançamento:

- BepInEx path → `GameLauncher.launchWithMods(modIds)` ou `launchLocalMods(dllPaths)` → `GameActivity` carrega `libfusion.so` + `libcoreclr.so` que bootstrapam BepInEx + Dobby.
- Frida path → `GameLauncher.launchFridaAgent(agentJsPath)` → `GameActivity` carrega `libfrida-gadget.so` que roda o agent.js.

Ambos usam a mesma `GameActivity` (injeção in-process, não abre o jogo num processo separado).

## Native runtime (BepInEx + CoreCLR)

Estes não são ENVs — são **assets** e **jniLibs**:

```
app/src/main/assets/
├── dotnet-arm64.zip          # .NET runtime (libcoreclr.so, libclrjit.so, System.*.dll)
├── BepInEx-arm64.zip         # BepInEx core + plugins
├── larp-boot.dex             # (opcional) Boot class extra
├── larp-env.json             # defaults das ENVs de runtime
├── frida-gadget.config.json  # template do gadget config
└── servidor.txt              # fallback (legacy)

app/src/main/jniLibs/arm64-v8a/  (download automático)
├── libfrida-gadget.so          (Frida)
└── (adicione manualmente:)
    ├── libfusion.so            (bootstrap BepInEx)
    ├── libcoreclr.so           (.NET)
    ├── libclrjit.so            (.NET JIT)
    ├── libcrypto.so, libssl.so (OpenSSL)
    ├── libdobby.so             (inline hooks)
    └── libSystem.*.so          (.NET System libs)
```

Sem `libfusion.so` + `libcoreclr.so` o caminho BepInEx não funciona — o jogo abre no processo do launcher mas sem mods (apenas Frida funcionaria, se a gadget estiver bundled).

Veja `app/src/main/jniLibs/README.txt` para onde obter as libs.
