plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "sh.haven.core.wayland"
    compileSdk = 37

    defaultConfig {
        minSdk = 26 // Runtime API check guards features needing 28+
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Native binaries built from source by buildWaylandNatives below.
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// Build the five wayland native binaries from the wayland-android submodule,
// instead of shipping hand-compiled copies checked into the tree (#493).
//
// Why this exists: until now nothing rebuilt these. They were compiled by hand
// and committed, which produced #469 (a liblabwc_android.so three versions
// stale, left 51 symbols undefined, and every cage/app-window feature died at
// dlopen with nothing failing until a user hit it) and the F-Droid gap (their
// recipe scandeletes the directory and only rebuilt one of the five, so their
// APKs shipped without an XWayland wrapper or GPU renderer at all).
//
// arm64-v8a only: that is the entire committed set, so x64/armv7 APKs have
// never carried these. The matrix jobs for those ABIs skip via -PtargetAbi
// rather than spending ~20 minutes building binaries they do not package.
val waylandAbi = "arm64-v8a"
val targetAbi = providers.gradleProperty("targetAbi").orNull
val waylandScriptDir = rootProject.file("wayland-android")
val waylandOut = file("src/main/jniLibs/$waylandAbi")

val buildWaylandNatives by tasks.registering {
    val labwc = File(waylandScriptDir, "build_liblabwc_android.sh")
    val helpers = File(waylandScriptDir, "build-native-helpers.sh")
    val virgl = File(waylandScriptDir, "build-virgl-android.sh")

    inputs.files(labwc, helpers, virgl).withPropertyName("buildScripts")
    // The submodule's HEAD is the real input — the sources are tens of
    // thousands of files across wlroots/labwc/virglrenderer, and hashing them
    // all would cost more than the build. Keying on the commit is what makes a
    // submodule bump reliably invalidate the binaries, which is precisely the
    // staleness #469 was.
    inputs.property(
        "waylandAndroidSha",
        providers.exec {
            commandLine("git", "-C", waylandScriptDir.absolutePath, "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.orElse("no-submodule"),
    )
    outputs.dir(waylandOut)

    // Skip when the submodule isn't checked out (the CI test job initialises an
    // explicit subset and leaves wayland-android out, because its freedesktop
    // chain intermittently 5xxs), and when this build is for an ABI these
    // binaries are not shipped for.
    onlyIf {
        val abiWanted = when (targetAbi) {
            null -> true            // unqualified build: produce them
            "arm64" -> true
            else -> false
        }
        val present = labwc.exists() && helpers.exists() && virgl.exists()
        if (!present) {
            logger.lifecycle("[wayland] submodule not checked out — skipping native build")
        }
        abiWanted && present
    }

    doLast {
        val built = File(waylandScriptDir, "jniLibs/$waylandAbi")
        // inheritIO, not a captured provider: these scripts are long and their
        // failures are deep (a missing header surfaces thousands of lines into
        // wlroots). Swallowing their output turns any of that into a bare
        // "finished with non-zero exit value 1", which is what the first cut of
        // this task did and it cost a debugging round.
        listOf(labwc, helpers, virgl).forEach { script ->
            val proc = ProcessBuilder("bash", script.absolutePath)
                .directory(waylandScriptDir)
                .inheritIO()
                .apply { environment()["ABI"] = waylandAbi }
                .start()
            val code = proc.waitFor()
            require(code == 0) { "${script.name} failed with exit code $code" }
        }
        copy {
            from(built) { include("*.so") }
            into(waylandOut)
        }
        // Assert rather than assume. A silently-empty jniLibs dir is exactly how
        // the F-Droid APK shipped without a desktop for months.
        val expected = listOf(
            "liblabwc_android.so",
            "libxwayland_wrapper.so",
            "libbenchmark_gles.so",
            "libvirgl_test_server.so",
            "libvirgl_render_server.so",
        )
        val missing = expected.filterNot { File(waylandOut, it).isFile }
        require(missing.isEmpty()) {
            "wayland native build produced no $missing in $waylandOut"
        }
        logger.lifecycle("[wayland] built ${expected.size} native binaries into $waylandOut")
    }
}

tasks.named("preBuild") { dependsOn(buildWaylandNatives) }

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:toolbar"))
    implementation(project(":core:data"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
