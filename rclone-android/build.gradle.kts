plugins {
    kotlin("jvm") version "2.0.21"
    `maven-publish`
}

group = "sh.haven"
version = "0.1.0"

repositories {
    mavenCentral()
    google()
}

// Produced by tools/build-android.sh via gomobile; see buildRcloneNative below.
val rcbridgeJar: File = layout.buildDirectory.file("rcbridge-bindings.jar").get().asFile

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    // gomobile-generated Java bindings (produced by build-android.sh).
    //
    // ★ #493: this used to be `api(files("build/rcbridge-bindings.jar"))`, and
    // that is why the jar could not be untracked. A raw file dependency has no
    // producing task, so it is demanded the moment a *consumer* resolves its
    // runtime classpath — :app:checkArm64DebugDuplicateClasses asks for it long
    // before buildRcloneNative has run, and `compileKotlin dependsOn(...)` is
    // far too late to help. Attaching builtBy to the file collection does not
    // survive AGP's artifact transform either.
    //
    // So: compile against it here (compileKotlin already depends on the task
    // that produces it), and fold its classes into this project's own jar
    // below. Consumers then get the bound classes through the ordinary project
    // dependency, with no raw file for them to resolve early.
    compileOnly(files(rcbridgeJar))
    testImplementation(files(rcbridgeJar))
    testImplementation("junit:junit:4.13.2")
}

// Kotlin wrapper sources generated alongside the Go → gomobile build
sourceSets {
    main {
        kotlin.srcDir("kotlin")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Build rclone native library from Go source via gomobile.
// Prerequisites: Go 1.26+, gomobile, gobind, Android NDK.
// The .so files are NOT committed — they're built as part of the Gradle build.
val buildRcloneNative by tasks.registering(Exec::class) {
    val goDir = file("go")
    val jniDir = file("jniLibs")
    val toolsDir = file("tools")

    inputs.dir(goDir)
    inputs.file(toolsDir.resolve("build-android.sh"))
    outputs.dir(jniDir)
    // NOT `outputs.file(rcbridgeJar)` — yet. Declaring it makes Gradle delete a
    // *tracked* file as a stale output on every build. It goes in at the same
    // time as the untracking, once F-Droid's recipe stops scandelete-ing
    // rclone-android/build (see #493).

    // Up-to-date checking is driven by Gradle's inputs/outputs above: the
    // expensive Go cross-compile is skipped when `go/` and build-android.sh are
    // unchanged AND the jniLibs outputs exist, and re-runs when the Go source
    // changes. (Do NOT gate with an `onlyIf { !so.exists() }` — that short-
    // circuits staleness so a source change leaves a stale .so on disk, e.g. a
    // pre-mailbridge build crashing at the first Mailbridge.mbRPC call.)

    workingDir = projectDir
    commandLine("bash", toolsDir.resolve("build-android.sh").absolutePath)

    // Ensure Go toolchain is on PATH for the build script
    val goRoot = "/usr/local/go"
    val goPath = System.getenv("GOPATH") ?: "${System.getProperty("user.home")}/go"
    environment("PATH", "$goRoot/bin:$goPath/bin:${System.getenv("PATH")}")

    // Pass SDK/NDK paths through environment (no hardcoded fallbacks)
    val ndkHome = System.getenv("ANDROID_NDK_HOME")
        ?: System.getenv("ANDROID_HOME")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { sdk ->
            file("$sdk/ndk").listFiles()?.maxByOrNull { it.name }?.absolutePath
        }
    if (ndkHome != null) {
        environment("ANDROID_NDK_HOME", ndkHome)
    }
    val sdkRoot = System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    if (sdkRoot != null) {
        environment("ANDROID_HOME", sdkRoot)
    }
}

// Ensure the Go native build completes before Kotlin compilation,
// because compileKotlin needs rcbridge-bindings.jar produced by gomobile.
tasks.named("compileKotlin") {
    dependsOn(buildRcloneNative)
}

// #493: carry the gomobile classes in this project's own jar, so consumers
// (core:rclone, core:tunnel) reach them through `project(":rclone-android")`
// rather than through a raw file path they would have to resolve themselves.
// That is what lets the jar stop being a committed binary.
tasks.named<Jar>("jar") {
    dependsOn(buildRcloneNative)
    from(provider { if (rcbridgeJar.isFile) zipTree(rcbridgeJar) else files() })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
