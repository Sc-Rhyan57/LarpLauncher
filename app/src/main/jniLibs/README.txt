Bibliotecas nativas (.so) do runtime de mods do Larp Launcher.

ESTAS LIBS JÁ ESTÃO INCLUIDAS NO PROJETO (extraídas do APK original do Regis Launcher):

  app/src/main/jniLibs/arm64-v8a/
  ├── libfusion.so                                   13 KB   bootstrap (carrega CoreCLR + BepInEx + Dobby)
  ├── libcoreclr.so                                 6.2 MB   .NET CoreCLR runtime
  ├── libclrjit.so                                  3.1 MB   .NET JIT compiler
  ├── libcrypto.so                                  4.2 MB   OpenSSL crypto
  ├── libssl.so                                     1.0 MB   OpenSSL TLS
  ├── libdobby.so                                   245 KB   inline hooking
  ├── libregisaudio.so                               10 KB   hook de áudio (opcional)
  ├── libSystem.Globalization.Native.so              72 KB
  ├── libSystem.IO.Compression.Native.so            759 KB
  ├── libSystem.Native.so                           104 KB
  └── libSystem.Security.Cryptography.Native.OpenSsl.so 218 KB

LIBS BAIXADAS AUTOMATICAMENTE PELO GRADLE (task downloadFridaGadget):

  app/src/main/jniLibs/arm64-v8a/
  └── libfrida-gadget.so                            ~50 MB   Frida gadget (necessário para o caminho Frida/MalumMenu)

  O download roda automaticamente antes de qualquer assemble*/bundle*.
  Versão configurável em gradle.properties (larpFridaVersion=17.2.1).
  Pré-requisito: xz-utils instalado (sudo apt install xz-utils).

ABI SUPORTADA: arm64-v8a apenas (Already 95%+ dos dispositivos Android).

ASSETS DO RUNTIME DE MODS (necessários para BepInEx funcionar):

  app/src/main/assets/
  ├── dotnet-arm64.zip                                ~24 MB   DLLs do .NET 6 (System.Private.CoreLib.dll, etc)
  └── BepInEx-arm64.zip                                ~3 MB   BepInEx core (BepInEx.Core.dll, Harmony, MonoMod, etc)

  Estes zips vêm do projeto BepInEx.Android.Launcher-main (dotnet.zip e BepInEx.Android.zip,
  renomeados). Sem eles o libfusion.so carrega mas aborta com "BepInEx core: ausente".

  O ModRuntime.kt extrai:
    dotnet-arm64.zip   -> filesDir/dotnet/
    BepInEx-arm64.zip  -> filesDir/BepInEx/   (preserva core/, plugins/, config/)

URLS ALTERNATIVAS (download em runtime, opcional):
  Se preferir não empacotar os zips, configure em Ajustes → Runtime ou edite
  app/src/main/assets/larp-env.json:
    "runtimeDotnetUrl":   "https://seu-host/dotnet-arm64.zip"
    "runtimeBepinexUrl":  "https://seu-host/BepInEx-arm64.zip"
  Lembre-se de usar zips com a MESMA estrutura interna dos empacotados.

Se quiser adicionar outras ABIs (armeabi-v7a, x86_64):
  1. Obtenha as .so equivalentes para a ABI desejada.
  2. Coloque em app/src/main/jniLibs/<abi>/.
  3. Adicione a ABI em abiFilters no app/build.gradle.kts.
  4. Edite a task downloadFridaGadget para incluir a ABI.
