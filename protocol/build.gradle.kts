import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// 定义与 iOS 互通的消息、编解码、对时和设置模型，不依赖 Android，测试在电脑上跑。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    // 把安卓编出的报文写到 build/，供 tools/ios-golden 反向解码。
    systemProperty("androidEncodedOut", layout.buildDirectory.file("android-encoded.tsv").get().asFile.absolutePath)
}
