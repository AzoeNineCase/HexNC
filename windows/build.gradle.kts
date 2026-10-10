import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.UUID

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "com.hexnc.music"
version = "1.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Skiko/AWT desktop bundle. Pulls the runtime, foundation, ui and material modules plus the
    // platform-specific Skia binary for the machine doing the build (compose.desktop.currentOs).
    implementation(compose.desktop.currentOs)

    // The same Compose Multiplatform artifacts the Android build pins, so the shared UI sources
    // compile against precisely the versions the phone app was verified with.
    implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    implementation("org.jetbrains.compose.ui:ui:1.12.1")

    // Miuix: the root (multiplatform) artifact ids resolve to the `desktop` variant here, whereas
    // the Android build pins the `-android` ids.
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-core:0.9.4")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Dispatchers.Main on the AWT event thread; without this `Dispatchers.Main` throws on desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Library database: plain JDBC SQLite. The DAO sources keep the Android schema and SQL verbatim
    // and talk to a thin JDBC-backed compatibility layer.
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    // Local file tags (ID3/Vorbis/MP4) for the folder importer.
    implementation("org.jaudiotagger:jaudiotagger:2.0.1")

    // Pure-Java QR encoder for the NetEase sign-in QR code.
    implementation("com.google.zxing:core:3.5.4")

    // Java Sound decoders: MP3 (mp3spi + tritonus) and OGG Vorbis (vorbisspi + jorbis).
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    // FLAC is not covered by soundlibs; jflac ships its own Java Sound SPI.
    implementation("org.jflac:jflac-codec:1.5.2")

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn")
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

compose.desktop {
    application {
        mainClass = "com.hexnc.music.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "HexNC"
            packageVersion = "1.1.0"
            description = "HexNC — NetEase Cloud Music desktop client"
            vendor = "HexNC"
            copyright = "© HexNC"

            // jlink only bundles the modules it can infer from the classpath, and the inference is
            // not enough here: the JDBC compatibility layer calls `java.sql.DriverManager`, which
            // lives in `java.sql`. The first `createDistributable` therefore produced an app image
            // that ran fine in `:run` (full toolchain JDK) and crashed on the packaged runtime with
            // `NoClassDefFoundError: java/sql/DriverManager` the moment the library was counted.
            // The same argument applies to `jdk.crypto.ec` (TLS ECDHE to NetEase), `jdk.unsupported`
            // and `jdk.localedata`, which are just as invisible until something touches them.
            // Bundling every module costs roughly 60 MB and removes that whole class of failure.
            includeAllModules = true

            windows {
                menu = true
                // jpackage's implicit Start-menu group name came out as the garbled literal "未知"
                // on this machine (the MSI's Directory row for ProgramMenuFolder's child). Setting it
                // explicitly gives a normal "HexNC" folder in the Start menu.
                menuGroup = "HexNC"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "0b7d9d1e-6b1b-4d3f-9a4e-2f5c8a1d77aa"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Windows MSI post-processing
// ---------------------------------------------------------------------------
// jpackage derives the MSI ProductCode from packageName + packageVersion alone,
// so every rebuild of one version carried the ProductCode of the already
// installed build of that same version and Windows Installer classified the new
// MSI as a maintenance run of the same product: `msiexec /i` returned 0 and
// replaced nothing ("Skipping RemoveExistingProducts action: current
// configuration is maintenance mode or an uninstall"). On top of that jpackage
// compiles its WiX template with IncludeMaximum="no" for the app's own version,
// so a product of the same version matches neither Upgrade row and would not be
// removed even if the ProductCode did change.
//
// Handing jpackage a custom main.wxs through `--resource-dir` is not possible:
// the Compose plugin empties that directory from inside its own task action
// (clearDirs(jpackageResources) in prepareWorkingDir and makeArgs), i.e. after
// any doFirst/doLast-with-dependency hook, and fails the build when the delete
// does not succeed ("Unable to delete directory ...").
//
// The finished MSI is therefore edited in place. packaging/windows/patch-msi.ps1
// gives it a build-unique ProductCode (light already stamps a fresh PackageCode
// for every build, and the summary-information PackageCode is not writable
// through the COM API), makes the
// JP_UPGRADABLE_FOUND Upgrade row inclusive of the current version, and adds the
// Start-menu "Uninstall HexNC" shortcut. The UpgradeCode stays as configured, so
// installing a rebuilt MSI removes the previous product and installs the new one
// (remove-then-install): one product, one ARP entry, no side-by-side install, no
// downgrade abort. The Exe target is not covered by this patch; its embedded MSI
// is inside the .exe.
val hexncMsiPatchScript = layout.projectDirectory.file("packaging/windows/patch-msi.ps1")
val hexncProductCode = "{" + UUID.randomUUID().toString().uppercase() + "}"

tasks.withType(AbstractJPackageTask::class.java).configureEach {
    if (name != "packageMsi") return@configureEach
    val packageTask = this
    val patchScript = hexncMsiPatchScript.asFile
    inputs.file(patchScript)
        .withPropertyName("hexncMsiPatchScript")
        .withPathSensitivity(PathSensitivity.NONE)
    doLast {
        val msi = layout.buildDirectory.dir("compose/binaries/main/msi").get().asFile
            .resolve("${packageTask.packageName.get()}-${packageTask.packageVersion.get()}.msi")
        check(msi.isFile) { "HexNC: expected MSI was not produced: $msi" }
        val process = ProcessBuilder(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", patchScript.absolutePath,
            "-Msi", msi.absolutePath,
            "-ProductCode", hexncProductCode,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        output.lineSequence().filter { it.isNotBlank() }.forEach { logger.lifecycle(it) }
        check(exit == 0) { "HexNC: MSI post-processing failed with exit code $exit" }
    }
}
