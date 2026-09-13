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
    // Same artifact already used by SMBJ; explicit for serializable SHA-256 verification state.
    implementation(libs.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    // AGP assigns the test classpath after task registration; resolve it at execution time.
    doFirst {
        val testTask = this as Test
        testTask.systemProperty("fileaccess.test.classpath", testTask.classpath.asPath)
    }
    inputs.file("src/test/fixtures/smb_server.py")
    for (name in listOf("FILEACCESS_SMB_TEST_PYTHON", "FILEACCESS_SMB_TEST_WSL_DISTRO", "FILEACCESS_SMB_LARGE_TEST")) {
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}
