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

## Game patching (detalhe técnico)

1. **ModSync.run(modIds)**:
   - Para cada id: pede ticket (`POST /mods/{id}/ticket`), baixa `.rgm` (AES-CBC), valida SHA-256, salva em `filesDir/pacotes/`.
   - Remove pacotes antigos não mais na lista.
   - Apaga `BepInEx/plugins/` e extrai cada pacote para lá.
   - Chama `InteropPronto.preparar()` para baixar pre-built do interop (se houver) ou gera no celular.

2. **ModRuntime.prepare(env, privateBepInEx)**:
   - Extrai `dotnet-arm64.zip` e `BepInEx-arm64.zip` dos assets do APK (se a versão mudou).
   - Copia `global-metadata.dat` do APK do jogo base para `game_data/Managed/Metadata/`.
   - Detecta versão Unity em `libunity.so` (regex `20XX.X.X[abfp]X`).
   - Seta variáveis de ambiente (`Os.setenv`) que o `libfusion.so`/`libregisaudio.so` esperam.

3. **GameLauncher.launchWithMods(...)** retorna `Flow<PatchProgress>` (Idle → Downloading → Decrypting → Preparing → Ready ou Failed).

4. **GameLauncher.startGame(env)**: usa `packageManager.getLaunchIntentForPackage(env.packageName)` para abrir o jogo. A injeção do runtime de mods ocorre por causa das env-vars setadas em `ModRuntime.prepare()`.
