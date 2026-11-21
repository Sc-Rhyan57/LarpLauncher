# Correções aplicadas (mods não carregavam)

Diagnóstico inicial: o `libfusion.so` carregava mas abortava porque os
diretórios `filesDir/dotnet/` e `filesDir/BepInEx/core/` ficavam vazios.
A versão anterior funcionava porque só extraía endereços IL2CPP via
`larpdevkit.so` e não chamava `Boot.load("fusion")`.

Após os primeiros fixes (assets bundled + abort em falta de BepInEx),
`dotnet: ok` e `BepInEx core: ok` passaram a aparecer, mas `libcoreclr:
Error: não encontrada` persistia e o BepInEx nunca iniciava o chainloader
(`LogOutput.log` não era criado).

## Causa raiz da segunda rodada de fixes

O `libfusion.so` (decompilado) faz `dlopen("<dir>/libcoreclr.so")` com
PATH ABSOLUTO procurando nos diretórios listados em `REGIS_LIB_DIRS`.
Com `useLegacyPackaging = false` (default AGP 3.6+) + `extractNativeLibs`
ausente no manifest, as `.so` ficam COMPACTADAS dentro do APK e NÃO são
extraídas para `nativeLibraryDir` em disco. Resultado:

- `NativeLibsInstaller.listBundledNativeLibs(launcherLibDir)` retornava
  lista vazia → `filesDir/native/` ficava vazio
- `dlopen("/data/app/.../lib/arm64/libcoreclr.so", ...)` falhava
  (arquivo não existe em disco)
- `libfusion.so` logava "dlopen libcoreclr.so: ..." e abortava
  silenciosamente sem chamar `Boot.status(-1, ...)`
- BepInEx chainloader nunca rodava → `LogOutput.log` nunca criado

## Arquivos alterados

### Assets (correção inicial)

- `app/src/main/assets/dotnet-arm64.zip`  (NOVO, ~24 MB) — DLLs do .NET 6
- `app/src/main/assets/BepInEx-arm64.zip`  (NOVO, ~3 MB) — BepInEx core

### Código de controle (correção inicial)

- `app/src/main/java/com/larp/launcher/patcher/RuntimeDownloader.kt`
  - `hasRuntime()` agora retorna `hasDotnet() && hasBepinex()`
  - `ensureRuntime()` lança IOException claro quando falta BepInEx

- `app/src/main/java/com/larp/launcher/patcher/ModRuntime.kt`
  - `prepare()` aborta (`return false`) quando `bepinexSource()` é null
  - Verificação pós-extração: se `BepInEx.Core.dll` não aparecer, aborta

- `app/src/main/assets/larp-env.json` e
  `app/src/main/java/com/larp/launcher/core/LarpEnvProvider.kt`
  - Defaults das URLs zerados (runtime vem bundled)

### Fix crítico da segunda rodada (libcoreclr)

- `app/build.gradle.kts`
  - `useLegacyPackaging = true` (era `false`) — extrai as `.so` para
    `nativeLibraryDir` em disco no install time. **ESTE É O FIX PRINCIPAL.**

- `app/src/main/AndroidManifest.xml`
  - `android:extractNativeLibs="true"` explícito no `<application>`
    (redundante com `useLegacyPackaging=true`, mas garante em todos os
    flavor/build type)

- `app/src/main/java/com/larp/launcher/boot/Boot.kt`
  - Pré-carrega TODAS as libs nativas na ordem de dependência antes do
    `libfusion.so`: libcrypto, libssl, libdobby, libSystem.Native,
    libSystem.Globalization.Native, libSystem.IO.Compression.Native,
    libSystem.Security.Cryptography.Native.OpenSsl, libclrjit, libcoreclr
  - Mesma abordagem do `main.cpp` do BepInEx Android Launcher original
  - Não aborta se uma lib faltar (loga warning e segue — o libfusion.so
    decide se consegue prosseguir)

### Fix de diagnóstico (overlay)

- `app/src/main/java/com/larp/launcher/feature/devkit/DevKitOverlayService.kt`
  - Check `libcoreclr` agora tenta 3 estratégias em sequência:
    1. `File(dotnet, "libcoreclr.so")` (caso zip trouxesse)
    2. `File(nativeLibraryDir, "libcoreclr.so")` (caso extraída)
    3. `classLoader.findLibrary("coreclr")` (caso mapeada do APK)
  - Mostra de onde veio: `ok (dotnet)` / `ok (nativelib)` / `ok (<path>)`

### Fix do catalog extractor

- `app/src/main/java/com/larp/launcher/feature/devkit/DevKitCatalogExtractor.kt`
  - `INITIAL_DELAY_MS` reduzido de 30s para 5s (espera menos pra começar)
  - `FORCE_AFTER_MS` reduzido de 90s para 60s
  - Mantém `GIVE_UP_MS = 300s` (5 min) como teto

## Rodada 3: UX + ferramentas para criar mods

### Mod de teste BepInEx (entregue separadamente)

- `LarpTestMod.dll` (~9.5 KB, netstandard2.0) — drop em `filesDir/BepInEx/plugins/`
- GUID `com.larp.testmod`, hooks `PlayerControl.Awake` via Harmony (resolução de tipo em runtime via `AccessTools.TypeByName`, sem referência a Assembly-CSharp.dll)
- Cria `filesDir/BepInEx/config/com.larp.testmod.cfg` em runtime
- Loga `LarpTestMod carregado!` no LogOutput.log (visível no Larp Dev Menu → Mods → "Carregados pelo BepInEx")

### Botões de exportação no Dev Kit

- **NOVO** `app/src/main/java/com/larp/launcher/feature/devkit/DevKitExporter.kt`
  - `exportStructureAsZip()`: empacota `catalog.tsv` + `catalog.meta` + `catalog.status` + `LEIA-ME.txt` em um ZIP no cache do app e abre sheet de compartilhamento
  - `exportAssemblyCSharp(gameEnv)`: extrai `assets/bin/Data/Managed/Assembly-CSharp.dll` do APK do jogo Among Us e abre sheet de compartilhamento
  - Usa FileProvider (`${applicationId}.fileprovider`) para compartilhar com segurança
- `app/src/main/java/com/larp/launcher/feature/devkit/DevKitViewModel.kt`
  - Adicionados `exportStructureAsZip()` e `exportAssemblyCSharp()` que disparam intents de share
  - Novo `DevKitEvent.ShareReady(intent, displayName, sizeBytes)` e `DevKitEvent.Toast(message)`
  - Novo campo `isExporting` no estado
- `app/src/main/java/com/larp/launcher/feature/devkit/DevKitScreen.kt`
  - Nova seção "Ferramentas do desenvolvedor" entre "Catálogo do jogo" e a lista de entradas
  - Botão preenchido "Baixar estrutura como ZIP" (ícone FolderZip)
  - Botão contornado "Baixar Assembly-CSharp.dll" (ícone Download)
  - Spinner visível quando `isExporting = true`
- `app/src/main/java/com/larp/launcher/core/di/AppModule.kt`
  - `provideDevKitExporter` injetável em todo o app
- `app/src/main/res/xml/file_provider_paths.xml`
  - Adicionado `<files-path name="devkit" path="devkit/" />`
  - Adicionado `<cache-path name="cache_root" path="." />` (permite compartilhar qualquer coisa do cache)

### Reorganização da home

- `app/src/main/java/com/larp/launcher/feature/launcher/LauncherScreen.kt`
  - Removidos da grid de quick access: Malum, Console, Frida Mods, Runtime
  - Removido o item "licença open source"
  - Lista final: Mods, Salas, Mural, Meus Mods, Dev Kit, Ajustes
  - Imports limpos (BugReport mantido, MenuBook/Memory/Code/Terminal removidos)

### Aba "Modo Desenvolvedor" em Settings

- `app/src/main/java/com/larp/launcher/data/local/prefs/SettingsPrefs.kt`
  - Novo `devMode: Flow<Boolean>` + `setDevMode(enabled)` via DataStore
- `app/src/main/java/com/larp/launcher/feature/settings/SettingsViewModel.kt`
  - `SettingsUiState.devMode` adicionado
  - `combine()` agora junta 4 flows (dynamicColor, darkMode, devMode, env)
  - `setDevMode(enabled)` exposto
- `app/src/main/java/com/larp/launcher/feature/settings/SettingsScreen.kt`
  - Nova seção "Modo Desenvolvedor" (ícone BugReport) no final
  - Toggle switch: ativa/desativa Console, Frida Mods, Runtime, Malum
  - Quando `devMode = true`, revela 4 `DevToolRow` (Console, Frida, Runtime, Malum) com setas de navegação
  - Seção "Sobre" simplificada (removida menção a GPLv3 e "Reconstrução open-source")
  - Removido o botão "Ver licenças open source"
  - Assinatura ganhou `onDevConsole`, `onDevFrida`, `onDevRuntime`, `onDevMalum` callbacks (com defaults vazios)
- `app/src/main/java/com/larp/launcher/feature/navigation/LarpNavHost.kt`
  - `composable(Routes.SETTINGS)` agora passa os 4 callbacks de navegação para as telas dev

### Overlay modernizado (estilo kawaii com cores do app)

- `app/src/main/java/com/larp/launcher/feature/devkit/DevKitOverlayService.kt`
  - Reescrito (478 → 696 linhas)
  - Paleta Larp como constantes: `#181213`, `#1F1719`, `#7E5260`, `#FFD9DD`, `#F4B3BB`, `#3B6FE0`, `#B3261E`, etc
  - **Bubble colapsado** (`BubbleView` inner class): 48dp oval com gradiente `LarpPrimary → LarpPrimaryContainer` + coração desenhado via Canvas/Path/Paint
  - **Painel expandido** (320x360dp):
    - Fundo gradiente top-bottom `#181213 → #1F1719` (GradientDrawable)
    - Borda 1dp `#F4B3BB` (glow rosa)
    - Cantos 24dp arredondados
    - Elevation 8dp (sombra)
  - **Header**: status dot 8dp (verde/amarelo/vermelho baseado em `Boot.stage`), título "Larp" arrastável, "—" pill para colapsar
  - **Barra de stats (NOVO, sempre visível)**: 3 pills lado a lado
    - FPS (azul `#3B6FE0`) — calculado via `Choreographer.FrameCallback` (1s postDelayed loop)
    - PING (rosa `#7E5260`, placeholder "--")
    - SAIR (vermelho `#B3261E`) — chama `quitGame()`
  - **Tabs estilo pill**: Mods/Logs/Dev/Ações; selecionado = filled LarpPrimary, não-selecionado = transparente + texto OnSurfaceVariantDark; 16dp radius; 4dp gap
  - **Body ScrollView**: fundo LarpSurface com 12dp radius
  - **Footer**: divisor LarpPrimaryDark 1dp + "Larp Dev Menu v0.2" 10sp
  - Helpers novos: `roundRectDrawable`, `ovalDrawable`, `pill(bgColor, text, textColor, cornerDp, sizeSp)`, `statusDotColor`, `addSpace`, `updateFpsPill`, `updateStatusDot`
  - Tudo o mais mantido: modsLines/logsLines/devLines/describeCoreclr, DragHandler, @Inject fields, companion object com ACTION_*/KEY_*/setEnabled/isDevKitEnabled

## Como testar

```bash
cd LarpLauncher-fixed
./gradlew :app:assembleStableDebug
# instala o APK no celular
```

IMPORTANTE: como mudamos `extractNativeLibs` e `useLegacyPackaging`, é
preciso **desinstalar a versão anterior** antes de instalar a nova
(o Android não re-extrai as .so num upgrade se a flag mudou):

```bash
adb uninstall com.larp.launcher.debug
adb install app/build/outputs/apk/stable/debug/app-stable-debug.apk
```

Depois de abrir o jogo com mods, o Larp Dev Menu → Mods deveria mostrar:

```
Loader: etapa=2 Loaded fusion      (ou msg de chainloader pronto)
dotnet: ok
libcoreclr: ok (nativelib)
BepInEx core: ok
Plugins em disco: N
Carregados pelo BepInEx: N
Erros: 0
```

E na aba Dev:
```
Catálogo: XXXXXX entradas extraídas
```

## Troubleshooting

Se ainda aparecer "libcoreclr: Error: não encontrada" depois do rebuild:
- Confirme que desinstalou a versão antiga antes de instalar a nova
- `adb shell run-as com.larp.launcher.debug ls -la /data/data/com.larp.launcher.debug/files/native/`
  deve listar `libcoreclr.so`, `libclrjit.so`, etc
- `adb shell run-as com.larp.launcher.debug ls -la $(adb shell pm path com.larp.launcher.debug | head -1 | sed 's/package://')/lib/arm64/`
  deve listar as .so extraídas (ou em `lib/arm64/` do APK dir)

Se aparecer "BepInEx core: ausente", force a re-extração limpando o cache:
- Ajustes → Runtime → "Apagar runtime baixado"
- Ou `adb shell run-as com.larp.launcher.debug rm -rf files/dotnet files/BepInEx`

