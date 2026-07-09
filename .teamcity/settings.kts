import jetbrains.buildServer.configs.kotlin.v2018_1.*
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.ScriptBuildStep
import jetbrains.buildServer.configs.kotlin.v2018_1.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2018_1.triggers.finishBuildTrigger

/*
 * TeamCity Kotlin DSL - CONAN third-party project (IN-658 Conan package builds).
 * Target server: TeamCity Enterprise 2018.1.3  =>  API version "2018.1".
 *
 * Three templated package builds: grpc, fmt, gtest. Each is one <PKG>_CONAN subtree
 * (Linux x86_64 / Linux ARM arm+arm64 / Windows x64 + a PUBLISH config). Describe a
 * package ONCE via conanPackage()/grpcLine(); adding a package is one line, bumping a
 * version is one string. Build logic itself lives in the conan-recipes test-astra
 * shell drivers - the DSL only wires configs + parameters.
 *
 * CAUTION: Kotlin block comments NEST (unlike Java). Never put the two-character
 * sequence slash-then-star inside a comment (a path followed by a shell glob is the
 * classic way) - it opens a nested comment that silently swallows the rest of the
 * file until the next star-then-slash, and TC reports "Expecting an element" at a
 * seemingly random spot inside a string literal far below.
 *
 * VCS: builds check out the conan-recipes repo through the EXISTING shared, parametrized
 * root  AbsoluteId("Bitbucket")  (url scm/%repoProject%/%repoName%.git, auth Password/git).
 * repoProject/repoName below point it at dev/conan. No new GitVcsRoot, no authMethod.
 *
 * NOTE: this file must reach the TeamCity settings VCS repo BYTE-EXACT (via git, not
 * copy-paste / web editor). ASCII only; keep straight double-quote chars intact.
 */

version = "2018.1"

// ---------------------------------------------------------------------------
// Data model + factories - describe everything template-style
// ---------------------------------------------------------------------------
data class ConanPkg(
    val name: String,
    val version: String,
    val arches: List<String> = listOf("x86_64", "arm", "arm64"),
    val windows: Boolean = true
)

/*
 * Tree shape produced by both factories (matches the TC-generated FMT_CONAN):
 *
 *   <PKG>_CONAN
 *     - PUBLISH <PKG> TO CONAN PROGET        (publish config at package level)
 *     - Linux        -> <PKG> BUILD Conan x86_64
 *     - Linux ARM    -> <PKG> BUILD Conan arm, arm64
 *     - Windows      -> <PKG> BUILD Conan Windows x64
 */

/** Standalone single package (gtest, fmt, zlib, ...) as a <PKG>_CONAN subtree. */
fun Project.conanPackage(p: ConanPkg) {
    val idBase = p.name.capitalize().replace("-", "")   // Kotlin 1.2 API (TC 2018.1 compiler)
    val leaves = mutableListOf<BuildType>()

    fun linuxLeaf(sp: Project, arch: String) = sp.buildType {
        id("${idBase}_Build_${arch.replace("-", "_")}")
        name = "${p.name} BUILD Conan $arch"
        templates(ConanBuildLinux)
        params {
            param("pkg.name", p.name)
            param("pkg.version", p.version)
            param("pkg.arch", arch)
        }
    }.also { leaves.add(it) }

    subProject {
        id("${idBase}_CONAN")
        name = "${p.name.toUpperCase()}_CONAN"

        subProject {
            id("${idBase}_Linux")
            name = "Linux"
            p.arches.filter { it == "x86_64" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("${idBase}_LinuxARM")
            name = "Linux ARM"
            p.arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (p.windows) subProject {
            id("${idBase}_Windows")
            name = "Windows"
            val winLeaf = buildType {
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
            leaves.add(winLeaf)
        }

        buildType {
            id("${idBase}_Publish")
            name = "PUBLISH ${p.name.toUpperCase()} TO CONAN PROGET"
            templates(PublishToProGet)
            dependencies {
                leaves.forEach { b ->
                    artifacts(b) {
                        artifactRules = "**/*.nupkg => nupkg/"
                    }
                }
            }
            triggers {
                finishBuildTrigger {
                    // v2018_1 API: the property is buildTypeExtId (renamed to buildType in 2019.x)
                    buildTypeExtId = leaves.first().id.toString()
                }
            }
        }
    }
}

/**
 * grpc line - the exception. Version is NOT a free variable: build_<line>_nodocker.sh
 * pins a 7-package stack and needs grpc/target_info/grpc_<ver>.yml. So `line` selects
 * the driver; `version` is display-only. Same <PKG>_CONAN tree shape as conanPackage().
 */
fun Project.grpcLine(
    line: String,
    version: String,
    arches: List<String> = listOf("x86_64", "arm", "arm64"),
    windows: Boolean = true
) {
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
    }.also { leaves.add(it) }

    subProject {
        id("Grpc_${line}_CONAN")
        name = "GRPC_${line}_CONAN"

        subProject {
            id("Grpc_${line}_Linux")
            name = "Linux"
            arches.filter { it == "x86_64" }.forEach { linuxLeaf(this, it) }
        }
        subProject {
            id("Grpc_${line}_LinuxARM")
            name = "Linux ARM"
            arches.filter { it == "arm" || it == "arm64" }.forEach { linuxLeaf(this, it) }
        }
        if (windows) subProject {
            id("Grpc_${line}_Windows")
            name = "Windows"
            val winLeaf = buildType {
                id("Grpc_${line}_Build_win_x64")
                name = "grpc-$line BUILD Conan Windows x64"
                templates(ConanBuildWindows)
                params {
                    param("pkg.name", "grpc")
                    param("pkg.version", version)
                    param("win.profile", "win-v143-x64")
                    param("win.slot", "win-x64")
                    param("pkg.driver.win", "run_grpc_${line}_win.bat")
                    param("pkg.output.win", "output-grpc-$line-win")
                }
            }
            leaves.add(winLeaf)
        }

        buildType {
            id("Grpc_${line}_Publish")
            name = "PUBLISH GRPC_$line TO CONAN PROGET"
            templates(PublishToProGet)
            dependencies {
                leaves.forEach { b ->
                    artifacts(b) {
                        artifactRules = "**/*.nupkg => nupkg/"
                    }
                }
            }
            triggers {
                finishBuildTrigger {
                    // v2018_1 API: the property is buildTypeExtId (renamed to buildType in 2019.x)
                    buildTypeExtId = leaves.first().id.toString()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Templates - the shared shape every leaf inherits
// ---------------------------------------------------------------------------
object ConanBuildLinux : Template({
    id("ConanBuildLinux")
    name = "Conan Build Linux"
    description = "One Conan package, one arch, built inside grpc-tc-mirror docker image -> legacy .nupkg"

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

    params {
        param("pkg.name", "")
        param("pkg.version", "")
        param("pkg.arch", "x86_64")   // x86_64 | arm | arm64
        param("docker.image", "%REGISTRY%/grpc-tc-mirror-%pkg.arch%:0.1.0")
        param("pkg.driver", "build_%pkg.name%_nodocker.sh")
        param("pkg.output", "output-%pkg.name%-%pkg.arch%")
    }

    steps {
        script {
            name = "conan build"
            scriptContent = """
                #!/bin/bash
                set -euo pipefail
                export REGISTRY="%REGISTRY%"
                ARCH=%pkg.arch% PKG_VERSION=%pkg.version% bash ./test-astra/%pkg.driver%
            """.trimIndent()
            dockerImage = "%docker.image%"
            dockerImagePlatform = ScriptBuildStep.ImagePlatform.Linux
            dockerPull = true
        }
    }

    artifactRules = "%pkg.output%/*.nupkg => %pkg.arch%"

    requirements {
        equals("system.agent.type", "build-linux")
        equals("system.agent.version", "2")
    }
})

object ConanBuildWindows : Template({
    id("ConanBuildWindows")
    name = "Conan Build Windows"
    description = "One Conan package on a native MSVC agent (no docker) -> legacy .nupkg. NOT yet legacy-byte-validated."

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

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
    description = "Collect leaf .nupkg (via artifact deps) and push to the conan NuGet feed on ProGet"

    vcs {
        root(AbsoluteId("Bitbucket"))
    }

    steps {
        script {
            name = "publish nupkg -> ProGet"
            // explicit bash: the exec bit does not survive every checkout path (exit 126)
            scriptContent = "API_KEY=%ProGet.ApiKey% PROGET_URL=%PROGET_URL% FEED=%FEED% NUPKG_DIR=nupkg bash ./test-astra/tc_publish_conan.sh"
        }
    }

    // bash publish must land on a Linux build agent (not a Windows one from the same pool)
    requirements {
        startsWith("system.agent.type", "build-")
        equals("system.agent.version", "2")
        doesNotEqual("system.agent.type", "build-windows")
    }
})

// ---------------------------------------------------------------------------
// Project root - global params + the package list (THIS is what you edit daily)
// ---------------------------------------------------------------------------
project {
    description = "CONAN third-party: grpc / fmt / gtest package builds via Kotlin DSL (IN-658)."

    template(ConanBuildLinux)
    template(ConanBuildWindows)
    template(PublishToProGet)

    params {
        param("REGISTRY", "proget.inc.elara.local/main")
        param("PROGET_URL", "http://proget.inc.elara.local")
        param("FEED", "conan")
        // drive the shared AbsoluteId("Bitbucket") root at the conan-recipes repo
        param("repoProject", "dev")
        param("repoName", "conan")
        param("env.LEGACY_NUPKG_LINKAGE", "shared")        // StaticRT slot -> "static"
        param("env.LEGACY_NUPKG_VERSION_SUFFIX", "")       // ".1" to coexist with legacy on ProGet
        // ProGet.ApiKey is deliberately NOT defined here. Secrets never go through the
        // synced DSL: define it once as a password parameter on the PARENT project
        // (SANDBOX, which is not under versioned settings) - CONAN inherits it and
        // %ProGet.ApiKey% in the publish step resolves at build time. Defining a fake
        // credentialsJSON placeholder here breaks apply ("could not decrypt" + TC
        // auto-commits a patches/ file that then fails with "parameter not found").
    }

    // ===== the three templated package builds =====
    conanPackage(ConanPkg("gtest", "1.17.0"))
    conanPackage(ConanPkg("fmt", "11.2.0"))
    grpcLine("1601", "1.60.1")   // grpc - driver-pinned line; version is display only
}
