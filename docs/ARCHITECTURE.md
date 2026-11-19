# Arquitetura

O Larp Launcher segue **MVVM + Repository + Clean Architecture simplificada**, com **Jetpack Compose** na UI e **Hilt** para injeção de dependências.

## Camadas

```
┌──────────────────────────────────────────────────────────────┐
│  UI (Compose, feature/*)                                       │
│  └─ Screens são stateless; consomem StateFlow do ViewModel    │
├──────────────────────────────────────────────────────────────┤
│  ViewModel (feature/<x>/<X>ViewModel.kt)                      │
│  └─ @HiltViewModel, StateFlow<UiState>, actions               │
│  └─ Faz mapping domain ↔ UI; não conhece Retrofit nem Room    │
├──────────────────────────────────────────────────────────────┤
│  Repository (data/repository/*)                               │
│  └─ AuthRepository, ModsRepository, RoomsRepository,         │
│     AnnouncementsRepository, BroadcastRepository,             │
│     VoiceRepository, GameEnvRepository                        │
│  └─ Orquestra Remote + Local; converte DTO → Domain           │
├──────────────────────────────────────────────────────────────┤
│  Data Layer                                                    │
│  ├─ remote: LarpApi (Retrofit), LarpRealtimeClient (WebSocket) │
│  └─ local: LarpDatabase (Room), AuthPrefs (encrypted),        │
│            SettingsPrefs (DataStore), ModCacheManager (FS)     │
├──────────────────────────────────────────────────────────────┤
│  Patcher (patcher/*)                                           │
│  └─ ModPackage (decrypt RGM1), ModSync (sync), ModRuntime     │
│     (env setup), GameLauncher (Flow<PatchProgress>)           │
│     HashDoInterop, InteropPronto                              │
└──────────────────────────────────────────────────────────────┘
```

## Fluxo de dados (exemplo: instalar mod do servidor)

1. **UI**: `ModsScreen` chama `ModsViewModel.install(mod)`.
2. **ViewModel** chama `modsRepository.requestTicket(mod.id)` → `downloadTicket` → `installMod` e atualiza `InstallState`.
3. **Repository** chama `LarpApi.getModTicket(id)` (Retrofit) e depois baixa via `OkHttpClient`.
4. **Repository** chama `ModPackage.extract(...)` para descriptografar e descompactar em `BepInEx/plugins/`.
5. **Repository** grava metadados em `installed_mods` (Room).

## Fluxo de dados (exemplo: criar sala)

1. **UI**: `RoomsScreen` → bottom sheet → `RoomsViewModel.createRoom(...)`.
2. **ViewModel** chama `roomsRepository.createRoom(...)`.
3. **Repository** chama `LarpApi.createRoom(CreateRoomRequest)`.
4. **API** retorna `RoomDto` → mapeado para `Room` (domain).
5. **ViewModel** atualiza `RoomsUiState.rooms`.

## Fluxo de tempo real (WebSocket)

1. `LarpRealtimeClient.connect(baseUrl, token)` abre um WebSocket em `wss://.../realtime?token=...`.
2. Cada evento recebido é desserializado (`RoomEventDto`, `BroadcastDto`, `AnnouncementDto`, `VoiceOffer/Answer/ICE`, `Presence`).
3. As ViewModels que precisam de tempo real coletam o Flow e atualizam o state.

> *Nota:* A conexão WebSocket ainda não está plugada em todas as ViewModels — está pronta para uso, mas o binding explícito fica a cargo da próxima iteração (ver `VoiceViewModel` para um exemplo de uso).

## Injeção de dependências (Hilt)

```
@Module @InstallIn(SingletonComponent::class)
├── AppModule          (AuthPrefs, SettingsPrefs, ModCacheManager, Room DB, DAOs)
├── NetworkModule      (Json, OkHttpClient, LarpApi, "apiUrl" qualifier)
└── RepositoryModule   (Auth/Mods/Rooms/Announcements/Broadcast/Voice/GameEnv +
                        GameLauncher, ModSync, ModRuntime, InteropPronto, HashDoInterop)
```

## Theme (Material Expressive)

- `core/design/theme/Color.kt` — paleta PT-BR (vermelho-rosado como seed).
- `core/design/theme/Type.kt` — Typography SansSerif.
- `core/design/theme/Theme.kt` — `LarpTheme(darkTheme, dynamicColor)`.
  - `dynamicColor = true` em Android 12+ usa cores do wallpaper (Material You).
  - `dynamicColor = false` usa a paleta estática (`LightColors` / `DarkColors`).

## Convenções

- **Sem comentários no código**. Justificativas de design vão na doc ou no commit message.
- **PT-BR** em strings literais (UI). DTOs em inglês (acompanham o backend).
- **StateFlow** + `data class UiState` em cada ViewModel.
- **Hilt @HiltViewModel** + `@Inject constructor(...)`.
- **Compose** collect: `hiltViewModel()` + `collectAsStateWithLifecycle()`.
- **Erros** propagados como sealed class ou campo `error: String?` em UiState.

## Build flavors

| Flavor | ApplicationId | Uso |
|--------|---------------|-----|
| `stable` | `com.larp.launcher` | Produção |
| `preview` | `com.larp.launcher.preview` | Beta |
| `canary` | `com.larp.launcher.canary` | Experimental |

Cada flavor tem `applicationIdSuffix` + `versionNameSuffix` próprios. Todos compartilham o mesmo código Kotlin (`src/main/`).

## Game patching (injeção in-process)

O Larp Launcher **não** abre o jogo num processo separado. Em vez disso, **roda o jogo dentro do processo do launcher** via `GameActivity`. Isso é o que permite a injeção de mods.

### Camadas

```
┌──────────────────────────────────────────────────────────────┐
│  Launcher process                                            │
│  ├── MainActivity (UI Compose)                              │
│  ├── GameActivity  (Activity que vira o jogo)                │
│  │   ├── attachBaseContext → GameEnv.init(...)               │
│  │   ├── onCreate → startUnity + loadMods                    │
│  │   └── setContentView(UnityPlayer.view)                    │
│  ├── GameEnv                                                 │
│  │   ├── PathClassLoader(game APK + larp-boot.dex)           │
│  │   ├── lib search path = launcher jniLibs + game libs      │
│  │   ├── ApplicationInfo clonado com dataDir do launcher     │
│  │   └── NativeLibsInstaller.ensureInstalled(filesDir/native)│
│  └── GameContext (ContextWrapper que delega tudo ao GameEnv)│
├──────────────────────────────────────────────────────────────┤
│  Native layer (jniLibs/arm64-v8a/)                           │
│  ├── libfusion.so   → bootstrap (load CoreCLR + BepInEx)    │
│  ├── libcoreclr.so  → .NET runtime                          │
│  ├── libclrjit.so   → JIT                                   │
│  ├── libdobby.so    → inline hooks                          │
│  ├── libcrypto/libssl → OpenSSL                             │
│  └── libfrida-gadget.so (opcional, Frida path)              │
└──────────────────────────────────────────────────────────────┘
```

### Fluxo de lançamento (BepInEx path)

1. `GameLauncher.launchWithMods(modIds, privateBepInEx, gamePackage)` retorna `Flow<PatchProgress>`.
2. `ModSync.run(modIds)` baixa + descriptografa + extrai os pacotes RGM1 para `BepInEx/plugins/`.
3. `NativeLibsInstaller.ensureInstalled(context)` copia as `.so` bundled do APK para `filesDir/native/` (caso seja split APK).
4. `GameLauncher.startGame(env, privateBepInEx)` chama `GameEnvFactory.reset()` e abre `GameActivity` via Intent.
5. `GameActivity.attachBaseContext` chama `GameEnv.init(context, gamePackage)` que:
   - Cria `PathClassLoader` com `classpath = game sourceDir + splits + larp-boot.dex` (se existir) e `libSearchPath = nativeDir + launcher nativeLibraryDir + game nativeLibraryDir`.
   - Clona o `ApplicationInfo` do jogo apontando `dataDir`, `uid`, `processName` para o do launcher e `nativeLibraryDir` para o `filesDir/native`.
6. `GameActivity.onCreate` chama `startUnity(env)`:
   - Carrega `com.unity3d.player.UnityPlayer` via reflection no `env.classLoader`.
   - Cria um `Proxy` de `IUnityPlayerLifecycleEvents` para receber `onUnityPlayerUnloaded`.
   - Instancia `UnityPlayer(this, lifecycleEvents)` e faz `setContentView(playerView)`.
7. `loadMods(env)` chama `ModRuntime.prepare(env, privateMods)`:
   - Extrai `dotnet-arm64.zip` e `BepInEx-arm64.zip` dos assets (se a versão mudou).
   - Copia `global-metadata.dat` do APK do jogo.
   - Detecta versão Unity em `libunity.so`.
   - Seta env vars via `Os.setenv`: `FUSION_BEPINEX_PATH`, `FUSION_GAME_BINARY`, `BEPINEX_GAME_ASSEMBLY_PATH`, `FUSION_GAME_DATA_DIR`, `FUSION_UNITY_VERSION`, `LARP_DOTNET_DIR`, `LARP_LIB_DIRS`, `HOME`, `TMPDIR`, etc.
8. `Boot.load("fusion")` chama `System.loadLibrary("coreclr")` + `System.loadLibrary("fusion")`:
   - `libfusion.so` lê as env vars e inicializa o CoreCLR + BepInEx + Dobby.
   - BepInEx carrega os plugins de `BepInEx/plugins/`.
   - Dobby faz inline hooks em `libil2cpp.so`.
9. O Unity (carregado no processo do launcher) chama o `libil2cpp.so` do jogo, e os hooks do BepInEx interceptam.

### Fluxo de lançamento (Frida path)

Igual ao BepInEx path, mas:

- `FridaInjector.installAgent(agentJs, gadgetConfig)` copia o `agent.js` para `libfrida-gadget.script.so` ao lado do `libfrida-gadget.so` bundled.
- `GameLauncher.launchFridaAgent(agentJsPath)` chama `ModRuntime.prepare(env, privateBepInEx=false)` para setar env vars mínimas.
- `Boot.load("fusion")` é **omitido**; em vez disso, o `libfrida-gadget.so` é carregado automaticamente pelo `libil2cpp.so` do jogo (porque o `libfrida-gadget.so` está no `libSearchPath`).
- O gadget lê `libfrida-gadget.config.so` (JSON) e carrega `libfrida-gadget.script.so` (o `agent.js`).
- O agente usa `frida-il2cpp-bridge` para hookar o IL2CPP.

### Por que in-process?

Porque as `.so` do launcher (`libfusion.so`, `libcoreclr.so`, etc.) precisam estar carregadas no mesmo processo que o `libil2cpp.so`. Se abríssemos o jogo num processo separado (`getLaunchIntentForPackage`), o jogo carregaria seu próprio `libil2cpp.so` sem acesso às nossas libs — sem injeção.

O trade-off é que o jogo roda com a `uid` do launcher (não a do jogo), então o `dataDir` aponta para o do launcher. Isso é tratado pelo `GameEnv` que clona o `ApplicationInfo` apontando `dataDir` para o do launcher.

### Sem `libfusion.so`?

Se `app/src/main/jniLibs/arm64-v8a/libfusion.so` não existir, o jogo abre no processo do launcher mas **sem mods** (apenas o runtime Unity puro). O `ModRuntime.prepare()` detecta isso (`assets.contains("dotnet-arm64.zip")` retorna false) e retorna `false`. A `GameActivity` loga "Mods desativados nesta sessão" e segue só com o Unity.

### Sem `libfrida-gadget.so`?

A aba **Frida Mods** mostra um aviso "libfrida-gadget.so não bundled" e desabilita o botão "Injetar e jogar". O caminho BepInEx continua funcionando normalmente.

