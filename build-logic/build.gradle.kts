plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.plugin.kotlin)
    implementation(libs.gradle.plugin.android)
    implementation(libs.gradle.plugin.kotlinter)
    implementation(libs.gradle.plugin.detekt)
    implementation(libs.gradle.plugin.cryptography)
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
