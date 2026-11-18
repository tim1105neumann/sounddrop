plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.ktlint)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()
    android {
        namespace = "dev.sounddrop.modem"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }
    wasmJs {
        nodejs()
        browser()
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
