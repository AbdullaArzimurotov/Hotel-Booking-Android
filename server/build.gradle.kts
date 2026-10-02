plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.dokka)
}
application {
    mainClass.set("ru.arzimurotov.hotel.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
kotlin.sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies {
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation("io.ktor:ktor-server-auth-jwt:3.2.3")
    implementation("io.ktor:ktor-server-status-pages:3.2.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation(libs.ktor.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.logback)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
