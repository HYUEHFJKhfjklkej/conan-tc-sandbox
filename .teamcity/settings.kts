import jetbrains.buildServer.configs.kotlin.v2018_1.*
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.ScriptBuildStep
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.finishBuildTrigger
import jetbrains.buildServer.configs.kotlin.v2018_1.vcs.GitVcsRoot

/*
 * TeamCity Kotlin DSL — SANDBOX project (IN-658 Conan package builds).
 * Target server: TeamCity Enterprise 2018.1.3  =>  API version "2018.1".
 *
 * Idea: describe a Conan package ONCE (a Template + a factory function), then
 * drive everything from a data list. Adding a package = one line; bumping a
 * version = one string. Build logic itself stays in conan-recipes/test-astra/*.sh —
 * the DSL only wires configs + parameters.
 *
 * PLACEHOLDERS to fill before first successful apply are marked  <FILL: ...>.
 * This file cannot be compiled on the authoring Mac (no TeamCity DSL jars) — TC
 * validates it on apply and reports errors on the Versioned Settings page.
 */

version = "2018.1"

// ---------------------------------------------------------------------------
// VCS root of the BUILD repo (conan-recipes) — what build configs check out to
// reach test-astra/*.sh. This is NOT the settings repo (conan-tc-sandbox).
// ---------------------------------------------------------------------------
object ConanRecipesVcs : GitVcsRoot({
    id("ConanRecipesVcs")
    name = "conan-recipes (develop)"
    url = "<FILL: git URL of conan-recipes on your Bitbucket>"   // e.g. ssh://git@bitbucket.../conan.git
    branch = "refs/heads/develop"
    // authMethod = ...  <FILL: uploadedKey / password, as your other VCS roots use>
})

// ---------------------------------------------------------------------------
// Data model + factories — "describe everything template-style"
// ---------------------------------------------------------------------------
data class ConanPkg(
    val name: String,
    val version: String,
    val arches: List<String> = listOf("x86_64", "arm", "arm64"),
    val windows: Boolean = true
)

/*
 * Both factories reproduce the SAME tree shape as the real FMT_CONAN / GRPC_CONAN /
 * GTEST_CONAN projects on the screenshot:
 *
 *   <PKG>_CONAN
 *     ├─ PUBLISH <PKG> TO CONAN PROGET        (publish config at package level)
 *     ├─ Linux        └─ <PKG> BUILD Conan x86_64
 *     ├─ Linux ARM    ├─ <PKG> BUILD Conan arm
 *     │               └─ <PKG> BUILD Conan arm64
 *     └─ Windows      └─ <PKG> BUILD Conan Windows x64
 */

/** Standalone single package (gtest, fmt, zlib, …) as a <PKG>_CONAN subtree. */
fun Project.conanPackage(p: ConanPkg) {
    val idBase = p.name.replaceFirstChar { it.uppercase() }.replace("-", "")
    val leaves = mutableListOf<BuildType>()

    fun linuxLeaf(sp: Project, arch: String) = sp.buildType {
        id("${idBase}_Build_${arch.replace("-", "_")}")
        name = "${p.name} BUILD Conan $arch"
        templates(ConanBuildLinux)
        params {
            param("pkg.name", p.name)
            param("pkg.version", p.version)   // human-readable, verbatim
            param("pkg.arch", arch)
        }
    }.also { leaves += it }

    subProject {
        id("${idBase}_CONAN")
        name = "${p.name.uppercase()}_CONAN"

        subProject {
            id("${idBase}_Linux"); name = "Linux"
            p.arches.filter { it == "x86_64" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("${idBase}_LinuxARM"); name = "Linux ARM"
            p.arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (p.windows) subProject {
            id("${idBase}_Windows"); name = "Windows"
            leaves += buildType {
                id("${idBase}_Build_win_x64")
                name = "${p.name} BUILD Conan Windows x64"
                templates(ConanBuildWindows)
                params {
                    param("pkg.name", p.name)
                    param("pkg.version", p.version)
                    param("win.profile", "win-v143-x64")
                    param("win.slot", "win-x64")
                }
            }
        }

        buildType {
            id("${idBase}_Publish")
            name = "PUBLISH ${p.name.uppercase()} TO CONAN PROGET"
            templates(PublishToProGet)
            dependencies { leaves.forEach { b -> artifacts(b) { artifactRules = "*.nupkg => nupkg/" } } }
            triggers { finishBuildTrigger { buildType = leaves.first().id.toString() } }
        }
    }
}

/**
 * grpc line — the exception. Version is NOT a free variable: build_<line>_nodocker.sh
 * pins a 7-package stack and needs grpc/target_info/grpc_<ver>.yml. So `line`
 * selects the driver; `version` is display-only. Overrides the derived driver/output.
 * Same <PKG>_CONAN tree shape as conanPackage().
 */
fun Project.grpcLine(line: String, version: String,
                     arches: List<String> = listOf("x86_64", "arm", "arm64"), windows: Boolean = true) {
    val leaves = mutableListOf<BuildType>()

    fun linuxLeaf(sp: Project, arch: String) = sp.buildType {
        id("Grpc_${line}_Build_${arch.replace("-", "_")}")
        name = "grpc-$line BUILD Conan $arch"
        templates(ConanBuildLinux)
        params {
            param("pkg.name", "grpc")
            param("pkg.version", version)                       // display only
            param("pkg.arch", arch)
            param("pkg.driver", "build_${line}_nodocker.sh")    // override derived
            param("pkg.output", "output-grpc-$line-$arch")      // override derived
        }
    }.also { leaves += it }

    subProject {
        id("Grpc_${line}_CONAN")
        name = "GRPC_${line}_CONAN"

        subProject {
            id("Grpc_${line}_Linux"); name = "Linux"
            arches.filter { it == "x86_64" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("Grpc_${line}_LinuxARM"); name = "Linux ARM"
            arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (windows) subProject {
            id("Grpc_${line}_Windows"); name = "Windows"
            leaves += buildType {
                id("Grpc_${line}_Build_win_x64")
                name = "grpc-$line BUILD Conan Windows x64"
                templates(ConanBuildWindows)
                params {
                    param("pkg.name", "grpc")
                    param("pkg.version", version)
                    param("win.profile", "win-v143-x64")
                    param("win.slot", "win-x64")
                    param("pkg.driver.win", "run_grpc_${line}_win.bat")   // override derived
                    param("pkg.output.win", "output-grpc-$line-win")      // override derived
                }
            }
        }

        buildType {
            id("Grpc_${line}_Publish")
            name = "PUBLISH GRPC_$line TO CONAN PROGET"
            templates(PublishToProGet)
            dependencies { leaves.forEach { b -> artifacts(b) { artifactRules = "*.nupkg => nupkg/" } } }
            triggers { finishBuildTrigger { buildType = leaves.first().id.toString() } }
        }
    }
}

// ---------------------------------------------------------------------------
// Templates — the shared shape every leaf inherits
// ---------------------------------------------------------------------------
object ConanBuildLinux : Template({
    id("ConanBuildLinux")
    name = "Conan Build Linux"
    description = "One Conan package, one arch, built inside grpc-tc-mirror docker image -> legacy .nupkg"

    vcs { root(ConanRecipesVcs) }

    params {
        // human knobs (a leaf overrides these)
        param("pkg.name", "")
        param("pkg.version", "")
        param("pkg.arch", "x86_64")   // x86_64 | arm | arm64
        // derived (leaf leaves as-is)
        param("docker.image", "%REGISTRY%/library/grpc-tc-mirror-%pkg.arch%:0.1.0")
        param("pkg.driver", "build_%pkg.name%_nodocker.sh")
        param("pkg.output", "output-%pkg.name%-%pkg.arch%")
    }

    steps {
        script {
            name = "conan build"
            scriptContent = "ARCH=%pkg.arch% PKG_VERSION=%pkg.version% ./test-astra/%pkg.driver%"
            // "Run step within Docker container" (TeamCity 2018.1 build-step docker wrapper).
            // If these typed props don't resolve on your exact 2018.1 build, set the same
            // via the UI on the template step once — the rest of the DSL is unaffected.
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
    }

    artifactRules = "%pkg.output%/*.nupkg => %pkg.arch%"

    requirements {
        exists("docker.server.version")
    }
})

object ConanBuildWindows : Template({
    id("ConanBuildWindows")
    name = "Conan Build Windows"
    description = "One Conan package on a native MSVC agent (no docker) -> legacy .nupkg. NOT yet legacy-byte-validated."

    vcs { root(ConanRecipesVcs) }

    params {
        param("pkg.name", "")
        param("pkg.version", "")
        param("win.profile", "win-v143-x64")   // x86 slot: win-v142-x86
        param("win.slot", "win-x64")
        param("pkg.driver.win", "run_%pkg.name%_win.bat")
        param("pkg.output.win", "output-%pkg.name%-win")
    }

    steps {
        script {
            name = "conan build (win)"
            scriptContent = "set PROFILE_NAME=%win.profile% & set PKG_VERSION=%pkg.version% & test-windows\\%pkg.driver.win%"
        }
    }

    artifactRules = "%pkg.output.win%\\*.nupkg => %win.slot%"

    requirements {
        contains("teamcity.agent.jvm.os.name", "Windows")
    }
})

object PublishToProGet : Template({
    id("PublishToProGet")
    name = "Publish to Conan ProGet"
    description = "Collect leaf .nupkg (via artifact deps) and push to the `conan` NuGet feed on ProGet"

    vcs { root(ConanRecipesVcs) }

    steps {
        script {
            name = "publish nupkg -> ProGet"
            scriptContent = "API_KEY=%ProGet.ApiKey% PROGET_URL=%PROGET_URL% FEED=%FEED% NUPKG_DIR=nupkg ./test-astra/tc_publish_conan.sh"
        }
    }
    // artifact dependencies are wired per concrete publish config (leaf ids differ) —
    // see conanPackage()/grpcLine() above.
})

// ---------------------------------------------------------------------------
// Project root — global params + the package list (THIS is what you edit daily)
// ---------------------------------------------------------------------------
project {
    description = "SANDBOX: Conan packages described via Kotlin DSL (IN-658). Add a package = one line below."

    vcsRoot(ConanRecipesVcs)
    template(ConanBuildLinux)
    template(ConanBuildWindows)
    template(PublishToProGet)

    params {
        param("REGISTRY", "proget.inc.elara.local/main")
        param("PROGET_URL", "http://proget.inc.elara.local")
        param("FEED", "conan")
        param("env.LEGACY_NUPKG_LINKAGE", "shared")        // StaticRT slot -> "static"
        param("env.LEGACY_NUPKG_VERSION_SUFFIX", "")       // ".1" to coexist with legacy on ProGet
        // With "Store secure values outside of VCS" ON, TC replaces the value with a
        // credentialsJSON:<uuid> token on first apply — leave the placeholder, add the
        // real key once in the UI.
        password("ProGet.ApiKey", "<FILL: credentialsJSON token after first apply>", label = "ProGet API key (conan feed)")
    }

    // ===== the data-driven package list =====
    val packages = listOf(
        ConanPkg("gtest", "1.17.0"),
        ConanPkg("fmt", "11.2.0")
        // ConanPkg("zlib", "1.3.1"),      // <- new standalone package: just add a line
    )
    packages.forEach { conanPackage(it) }

    // grpc lines (7-package stack each — driver-pinned, version is display only)
    grpcLine("1601", "1.60.1")
    grpcLine("1781", "1.78.1")
}
