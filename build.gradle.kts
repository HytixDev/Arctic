plugins {
    `java-library`
    `maven-publish`
}

group = "dev.hytixmc"
version = "1.0.0-SNAPSHOT"
description = "A streamed, crash-safe world format for Minestom"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("net.minestom:minestom:2026.07.12-26.2")
    implementation("com.github.luben:zstd-jni:1.5.7-7")

    testImplementation("net.minestom:minestom:2026.07.12-26.2")
    testImplementation(platform("org.junit:junit-bom:6.0.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
    withJavadocJar()
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:all,-missing", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
