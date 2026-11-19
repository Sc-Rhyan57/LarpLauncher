Coloque aqui as bibliotecas nativas (.so) do runtime de mods do Larp Launcher.

Estrutura esperada:

  app/src/main/jniLibs/
  ├── arm64-v8a/
  │   ├── libfusion.so          (bootstrap BepInEx + CoreCLR + Dobby)
  │   ├── libcoreclr.so        (.NET CoreCLR)
  │   ├── libclrjit.so          (.NET JIT)
  │   ├── libcrypto.so          (OpenSSL)
  │   ├── libssl.so             (OpenSSL)
  │   ├── libdobby.so           (inline hooking)
  │   ├── libSystem.Globalization.Native.so
  │   ├── libSystem.IO.Compression.Native.so
  │   ├── libSystem.Native.so
  │   ├── libSystem.Security.Cryptography.Native.OpenSsl.so
  │   ├── libregisaudio.so      (opcional — hook de áudio)
  │   └── libfrida-gadget.so    (opcional — só se LARP_BUNDLE_FRIDA_GADGET=true)
  ├── armeabi-v7a/              (opcional)
  └── x86_64/                   (opcional — emulador)

Como obter as libs:

1. **BepInEx + CoreCLR + Dobby**: build do projeto `larp-backend` (a ser publicado) ou extraído
   de uma APK de referência do Regis Launcher. As libs são ~22 MB no total (arm64-v8a).

2. **libfrida-gadget.so**: baixe de https://github.com/frida/frida/releases
   (`frida-gadget-XX.X.X-android-arm64.so.xz`), descompacte e renomeie para `libfrida-gadget.so`.

3. **libfusion.so**: bootstrap nativo que chama `coreclr_initialize` + BepInEx + Dobby.
   Source em `larp-backend/native/fusion/` (a ser publicado).

Sem estas libs, o Larp Launcher abre o jogo via GameActivity mas sem injeção de mods.
A aba "Frida Mods" só funciona se libfrida-gadget.so estiver presente.

Veja docs/ENVS.md para configurar LARP_BUNDLE_FRIDA_GADGET e docs/ARCHITECTURE.md
para entender o fluxo de injeção.
