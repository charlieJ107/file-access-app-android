plugins {
    alias(libs.plugins.android.library)

}
android {
    namespace = "space.zhuoling.fileaccess.protocol.smb"
    compileSdk { version = release(37) }
    defaultConfig {
        minSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:storage-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.smbj)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
