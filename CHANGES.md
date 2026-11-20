# Correções aplicadas (mods não carregavam)

Diagnóstico: o `libfusion.so` carregava mas abortava porque os diretórios
`filesDir/dotnet/` e `filesDir/BepInEx/core/` ficavam vazios. A versão
anterior funcionava porque só extraía endereços IL2CPP via `larpdevkit.so`
e não chamava `Boot.load("fusion")`.

## Arquivos alterados

### 1. `app/src/main/assets/dotnet-arm64.zip`  (NOVO, ~24 MB)
Copiado de `BepInEx.Android.Launcher-main/app/src/main/assets/dotnet.zip`
e renomeado. Contém as DLLs do .NET 6 (System.Private.CoreLib.dll, etc).

### 2. `app/src/main/assets/BepInEx-arm64.zip`  (NOVO, ~3 MB)
Copiado de `BepInEx.Android.Launcher-main/app/src/main/assets/BepInEx.Android.zip`
e renomeado. Contém `core/BepInEx.Core.dll`, `core/0Harmony.dll`,
`core/MonoMod.*.dll`, `core/Il2CppInterop.*.dll`, etc.

### 3. `app/src/main/java/com/larp/launcher/patcher/RuntimeDownloader.kt`

- `hasRuntime()` agora retorna `hasDotnet() && hasBepinex()` (antes só
  verificava `hasDotnet()`, mascarando a falta do BepInEx).
- `ensureRuntime()` reescrito: se faltar o BepInEx e a URL estiver vazia,
  lança IOException com mensagem clara em vez de seguir silenciosamente.

### 4. `app/src/main/java/com/larp/launcher/patcher/ModRuntime.kt`

- `prepare()` agora aborta (retorna `false`) quando `bepinexSource()` é
  `null`, em vez de logar "BepInEx ausente — prosseguindo" e seguir.
- Adicionada verificação pós-extração: se `BepInEx.Core.dll` não aparecer
  em `filesDir/BepInEx/core/`, retorna `false` (zip corrompido).

### 5. `app/src/main/assets/larp-env.json`

- `runtimeDotnetUrl` zerada (não precisa mais — dotnet vem bundled).
- `runtimeBepinexUrl` continua vazia (BepInEx vem bundled).

### 6. `app/src/main/java/com/larp/launcher/core/LarpEnvProvider.kt`

- `DEFAULT_RUNTIME_DOTNET_URL` agora é `""` (consistente com larp-env.json).

### 7. `app/src/main/jniLibs/README.txt`

- Seção nova documentando os assets do runtime de mods.

## Como testar

```bash
cd LarpLauncher-fixed
./gradlew :app:assembleStableDebug
# instala o APK no celular e abre o jogo via LaunchActivity
```

Depois de abrir o jogo com mods, o Larp Dev Menu → Mods deveria mostrar:

```
Loader: etapa=2 Loaded fusion      (ou mensagem de chainloader pronto)
dotnet: ok
libcoreclr: ok
BepInEx core: ok
Plugins em disco: N
Carregados pelo BepInEx: N
Erros: 0
```

Se ainda aparecer "BepInEx core: ausente", force a re-extração limpando
o cache: Ajustes → Runtime → "Apagar runtime baixado" (ou apague
manualmente `/data/data/com.larp.launcher.debug/files/dotnet` e
`/data/data/com.larp.launcher.debug/files/BepInEx`).
