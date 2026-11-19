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

Se quiser adicionar outras ABIs (armeabi-v7a, x86_64):
  1. Obtenha as .so equivalentes para a ABI desejada.
  2. Coloque em app/src/main/jniLibs/<abi>/.
  3. Adicione a ABI em abiFilters no app/build.gradle.kts.
  4. Edite a task downloadFridaGadget para incluir a ABI.
