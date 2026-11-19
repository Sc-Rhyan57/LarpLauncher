# Larp Launcher — Contributing

Obrigado por querer contribuir com o **Larp Launcher**! Este é um projeto open-source (GPLv3) que visa oferecer uma base moderna, segura e organizada para mods de jogos baseados em Unity/IL2CPP no Android.

## Antes de começar

1. Abra uma **issue** descrevendo o que pretende mudar (bug, feature, refatoração) antes de abrir um PR.
2. Toda contribuição deve respeitar a licença **GPLv3**. Ao enviar um PR, você concorda em licenciar o código sob a mesma licença.

## Setup do ambiente

- **JDK 17** (Temurin recomendado)
- **Android SDK** API 35 (compileSdk) e API 26 (minSdk)
- **Android NDK** 27.2.12479018 (para compilação de libs nativas, se necessário)
- **Gradle 8.7+** (o wrapper `gradlew` já vem na raiz)

```bash
git clone https://github.com/<seu-usuario>/larp-launcher.git
cd larp-launcher
./gradlew :app:assembleStableDebug
```

## Padrões de código

- **Kotlin**: use as regras padrão do Kotlin (ktlint configurável futuramente).
- **Sem comentários no código**: documente no commit/PR, não no arquivo. Justificativas de design vão na documentação em `docs/`.
- **Arquitetura**: MVVM + Jetpack Compose + Hilt. Veja `docs/ARCHITECTURE.md`.
- **Recursos**: strings em PT-BR (mantenha `app/src/main/res/values/strings.xml`).
- **Build flavors**: `stable`, `preview`, `canary`. Use apropriada para testar (`canary` para coisas experimentais).

## Estrutura do projeto

```
app/
├── src/main/java/com/larp/launcher/
│   ├── core/         (DI, design system, util)
│   ├── data/         (remote, local, repository)
│   ├── domain/       (model, usecase)
│   ├── feature/      (cada tela: auth, launcher, mods, rooms, etc.)
│   ├── patcher/      (ModPackage, ModSync, ModRuntime, GameLauncher)
│   └── diagnostics/ (Diario)
├── src/main/res/
└── src/main/assets/ (servidor.txt — URL do backend)
```

## Tipos de contribuição

- **Bug fix**: abra issue + PR apontando para `main`.
- **Feature nova**: abra issue de proposta primeiro, depois PR para `develop`.
- **i18n**: copie `values/strings.xml` para `values-<lang>/strings.xml`.
- **Backend**: o app fala com Cloudflare Workers. Veja `docs/API.md` para o contrato.

## Antes de commitar

```bash
./gradlew :app:lintStableDebug
./gradlew :app:testStableDebugUnitTest
./gradlew :app:assembleStableDebug
```

## Licença

Ao contribuir, você concorda que seu código seja licenciado sob **GPLv3**, conforme o arquivo `LICENSE`.
