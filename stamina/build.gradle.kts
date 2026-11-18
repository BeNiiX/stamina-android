plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "io.getstamina"
version = "1.1.0"

android {
    namespace = "io.getstamina.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions.unitTests.isIncludeAndroidResources = true
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Install Referrer Play Store (attribution déterministe Android)
    implementation("com.android.installreferrer:installreferrer:2.2")
    // GAID (respect de Limit Ad Tracking)
    implementation("com.google.android.gms:play-services-ads-identifier:18.1.0")
    // Démarrage sans code via <meta-data io.getstamina.API_KEY>
    implementation("androidx.startup:startup-runtime:1.2.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "io.getstamina"
                artifactId = "stamina-android"
                version = project.version.toString()
            }
        }
    }
}
