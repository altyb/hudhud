plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.altayeb.hudhud"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.altayeb.hudhud"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.4.0"
    }
    // Release key lives outside the repo; set HUDHUD_KS / HUDHUD_KS_PASS / HUDHUD_KEY_ALIAS to sign.
    signingConfigs {
        create("release") {
            System.getenv("HUDHUD_KS")?.let { storeFile = file(it) }
            storePassword = System.getenv("HUDHUD_KS_PASS")
            keyAlias = System.getenv("HUDHUD_KEY_ALIAS")
            keyPassword = System.getenv("HUDHUD_KS_PASS")
        }
    }
    buildTypes {
        release {
            if (System.getenv("HUDHUD_KS") != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "tsv" }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended") // R8 keeps only the icons used
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.81")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.credentials:credentials:1.5.0")
    testImplementation("junit:junit:4.13.2")
}

// Privacy guarantee: the app must never be able to reach the network. Fails the build if any
// dependency or edit adds the INTERNET permission to the merged manifest.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val check = tasks.register("check${name}NoInternet") {
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                check("android.permission.INTERNET" !in text) { "INTERNET permission found in merged manifest" }
            }
        }
        afterEvaluate { tasks.named("assemble$name") { dependsOn(check) } }
    }
}

// The licence texts ship inside the app, for Settings > Open source licenses.
val copyLegal = tasks.register<Copy>("copyLegal") {
    from(rootProject.file("NOTICE")) { rename { "NOTICE.txt" } }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    into(layout.buildDirectory.dir("generated/legal"))
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/legal").get().asFile)
tasks.named("preBuild") { dependsOn(copyLegal) }
