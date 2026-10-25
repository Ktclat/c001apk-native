import java.nio.file.Files
import java.security.MessageDigest
import io.github.ktclat.build.NativeBuildProvenance

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
// Generated on every build, after configuration: Git/source changes must never reuse stale metadata.
// No network, Python, timestamps, environment values or absolute paths enter the generated constants.
val nativeProvenanceRoot = rootProject.layout.projectDirectory.asFile.toPath()
val nativeProvenanceDirectory = layout.buildDirectory.dir("generated/source/nativeProvenance/main")
val generateNativeBuildProvenance by tasks.registering {
    group = "build"
    description = "Generate local, non-sensitive native source provenance."
    outputs.dir(nativeProvenanceDirectory)
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Provenance must re-read the live Git worktree and whitelist on every build")
    doLast {
        val report = NativeBuildProvenance.capture(nativeProvenanceRoot)
        val output = nativeProvenanceDirectory.get().file("io/github/ktclat/c001apk/nativeapp/diagnostics/NativeBuildInfo.kt").asFile
        output.parentFile.mkdirs()
        output.writeText(report.kotlinSource(), Charsets.UTF_8)
    }
}
android {
    sourceSets.getByName("main").java.srcDir(nativeProvenanceDirectory)
    namespace = "io.github.ktclat.c001apk.nativeapp"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.ktclat.c001apk.nativeapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "2g"
            // Robolectric downloads and lock files must not assume a writable OS account home.
            test.systemProperty("user.home", gradle.gradleUserHomeDir.resolve("caches/robolectric-home").apply { mkdirs() }.absolutePath)
            test.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.mindrot:jbcrypt:0.4")
    // Direct AST -> Compose; no HTML renderer, WebView, network or global source cache.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Keep binary fonts out of Git while preserving deterministic Android resources.
// This task is deliberately offline and requires only the JDK, not Python.
val verifyBundledFonts by tasks.registering {
    group = "verification"
    description = "Verify all three pinned Noto fonts; never download build inputs."
    val fontDirectory = layout.projectDirectory.dir("src/main/res/font")
    val manifest = layout.projectDirectory.file("tools/fonts.sha256")
    val expectedNames = setOf(
        "noto_sans_cjk_sc_regular.otf",
        "noto_sans_cjk_sc_medium.otf",
        "noto_sans_cjk_sc_bold.otf",
    )
    inputs.file(manifest)
    inputs.files(expectedNames.map { fontDirectory.file(it) })
    // No outputs: recheck bytes on every invocation, including an otherwise up-to-date build.
    doLast {
        generateSequence(fontDirectory.asFile.absoluteFile) { it.parentFile }.forEach { component ->
            if (Files.isSymbolicLink(component.toPath())) {
                throw GradleException("Unsafe symlinked font directory component: ${component.path}")
            }
        }
        val manifestFile = manifest.asFile
        if (!manifestFile.isFile) {
            throw GradleException("Missing pinned font manifest: ${manifestFile.path}")
        }
        val expectedHashes = linkedMapOf<String, String>()
        val record = Regex("([0-9a-f]{64})  ([a-z0-9_]+\\.otf)")
        manifestFile.readLines(Charsets.UTF_8).filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val match = record.matchEntire(line)
                ?: throw GradleException("Invalid entry in ${manifestFile.path}")
            val (hash, name) = match.destructured
            if (name !in expectedNames || expectedHashes.put(name, hash) != null) {
                throw GradleException("Unexpected or duplicate font in ${manifestFile.path}: $name")
            }
        }
        if (expectedHashes.keys != expectedNames) {
            throw GradleException("Font manifest must pin exactly Regular, Medium, and Bold")
        }
        val problems = expectedHashes.mapNotNull { (name, expectedHash) ->
            val fontFile = fontDirectory.file(name).asFile
            when {
                Files.isSymbolicLink(fontFile.toPath()) -> "$name: symlinks are not permitted"
                !fontFile.isFile -> "$name: missing or not a regular file"
                else -> {
                    val digest = MessageDigest.getInstance("SHA-256")
                    fontFile.inputStream().buffered().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    if (actualHash == expectedHash) null else "$name: pinned SHA-256 mismatch"
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Pinned font build resources are missing or invalid:\n" + problems.joinToString("\n") +
                    "\nFrom the repository root, run: python3 app/tools/fetch_fonts.py" +
                    "\nExisting invalid files are not overwritten; investigate and move them aside first." +
                    "\nTo verify prepared files without network access: python3 app/tools/fetch_fonts.py --offline"
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyBundledFonts, generateNativeBuildProvenance) }
