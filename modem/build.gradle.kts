plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    explicitApi()
    jvmToolchain(21)

    jvm()
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
