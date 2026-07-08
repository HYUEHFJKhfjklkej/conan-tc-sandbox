# conan-tc-sandbox — TeamCity settings repo (Kotlin DSL)

This repository holds **only the TeamCity configuration** of the `SANDBOX` project,
as Kotlin DSL. It is the *versioned-settings VCS root* for that project — TeamCity reads
`.teamcity/settings.kts` from here and materializes projects / build configs / templates
from it. It contains **no build logic and no source code**.

TeamCity Enterprise **2018.1.3** → DSL API version `"2018.1"`.

---

## Why this repo is separate — what each repository is for

| Repository | Purpose | Who reads it |
|---|---|---|
| **conan-tc-sandbox** *(this repo)* | TeamCity **configuration** of the SANDBOX project as Kotlin DSL (`.teamcity/settings.kts` + `pom.xml`). Describes projects, build configs, templates, parameters. **No build scripts here.** | The TeamCity server (Versioned Settings sync). Two-way: UI edits are committed here; commits here are applied to the project. |
| **conan-recipes** | The **actual project / build logic**: Conan recipes (`gtest/`, `grpc/`, `fmt/`, …), the `legacy_nupkg.py` deployer, `profiles/`, and the build drivers `test-astra/*.sh` / `test-windows/*.bat`. Produces the legacy `.nupkg`. | The build **agents** — every build config checks out conan-recipes and runs a `test-astra/*.sh` driver. Its git remote is the team Bitbucket (branch `develop`). |

Rule of thumb: **conan-tc-sandbox = how builds are wired; conan-recipes = what builds do.**
The DSL here references conan-recipes via the `ConanRecipesVcs` VCS root and calls its
drivers by name (`./test-astra/build_%pkg.name%_nodocker.sh`). It never duplicates them.

```
   conan-tc-sandbox (this repo)                 conan-recipes (build repo)
   .teamcity/settings.kts  ──describes──►  TeamCity SANDBOX project
        │                                          │  each build config
        │  "run ./test-astra/build_gtest…"         │  checks out ▼
        └──────────────────────────────────►  test-astra/*.sh  (real build)
```

---

## What's in here

```
.teamcity/
  settings.kts   # the DSL: templates + conanPackage()/grpcLine() factories + the package list
  pom.xml        # Maven descriptor so IntelliJ can resolve the DSL API (TC regenerates it)
.gitignore
README.md
```

`settings.kts` in one glance:
- **Templates** `ConanBuildLinux` / `ConanBuildWindows` / `PublishToProGet` — the shared shape every leaf inherits.
- **Factories** `conanPackage(pkg)` and `grpcLine(line, version)` — one call builds a
  package's whole subtree, in the **same shape as the real FMT_CONAN / GRPC_CONAN /
  GTEST_CONAN** projects:
  ```
  <PKG>_CONAN
    ├─ PUBLISH <PKG> TO CONAN PROGET
    ├─ Linux      → <PKG> BUILD Conan x86_64
    ├─ Linux ARM  → <PKG> BUILD Conan arm / arm64
    └─ Windows    → <PKG> BUILD Conan Windows x64
  ```
- **The list** at the bottom of `project { }` — the only thing you edit day to day:
  ```kotlin
  val packages = listOf(
      ConanPkg("gtest", "1.17.0"),
      ConanPkg("fmt",   "11.2.0"),
  )
  ```
  Add a package = one line. Bump a version = one string. grpc is driver-pinned, so its
  version is display-only (see the comment on `grpcLine`).

---

## First-time setup (on the TeamCity server side)

1. Create this repo on the team Bitbucket (e.g. `conan-tc-sandbox`) and push (see below).
2. In TeamCity: `Administration → SANDBOX → Versioned Settings`:
   - Synchronization **enabled**; **Project settings VCS root** → point at this repo.
   - When build starts: **always use current settings** (safe: UI stays source of truth).
   - **Store secure values outside of VCS**: ✅ (keeps the ProGet key out of git).
   - Settings format: **Kotlin**; **Generate portable DSL scripts**: ✅.
   - **Apply**.
3. On Apply, TeamCity generates `.teamcity/settings.kts` + `pom.xml` for the *current*
   (empty) SANDBOX and commits them here. Then **replace `settings.kts` with the version
   in this repo** and push — TeamCity applies it and materializes the configs.
4. Fill the placeholders below.

### Placeholders to fill (`<FILL: …>` in the files)
- `ConanRecipesVcs.url` / `authMethod` in `settings.kts` — git URL + auth of **conan-recipes**
  on your Bitbucket (copy from an existing VCS root of the `*_CONAN` projects).
- `ProGet.ApiKey` — added in the UI; with "store secrets outside VCS" TC replaces it with a
  `credentialsJSON:` token automatically.
- `pom.xml` repository URL / `<your-teamcity-host>` — TC overwrites this on apply.

---

## Caveats

- **Sandbox, not production.** Builds run on shared agents. Keep the ProGet publish in
  `DRY_RUN` or aim the push at `nuget-sandbox`, not the `conan` feed, until validated.
- **This file is authored on macOS with no TeamCity DSL jars**, so `settings.kts` is not
  compiled locally. TeamCity validates it on apply and shows errors on the Versioned
  Settings page. The one spot most likely to need a tweak on exact 2018.1: the docker
  build-step wrapper props (`dockerImage`/`dockerImagePlatform`/`dockerPull`) — if they
  don't resolve, set them once in the UI on the `ConanBuildLinux` template step.
- **Promoting to the real `*_CONAN` projects** (turning on versioned settings there) is a
  team-lead decision, not something to flip from the sandbox.

## Push this repo

```bash
cd conan-tc-sandbox
git remote add origin <FILL: ssh URL of the new Bitbucket repo>
git push -u origin master
```
