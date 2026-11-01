// :core — pure Kotlin/JVM. No Android dependencies, so it runs under plain `java`
// and is exhaustively unit-tested without a device. The Android :app module
// depends on this for the layout engine and MCP protocol handling.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Desktop stand-in for the phone's MCP service: ./gradlew :core:devServer --args="8765 my-token"
tasks.register<JavaExec>("devServer") {
    group = "application"
    description = "Run the MCP server on this machine with a fake app catalog"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.picca.launcher.core.devserver.DevServerKt")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
