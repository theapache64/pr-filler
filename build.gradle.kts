plugins {
    kotlin("jvm") version "2.1.10"
}

group = "io.github.theapache64"
// [latest version - i promise!]
version = "1.0.5"

repositories {
    mavenCentral()
}

tasks.jar {
    archiveFileName.set("pr-filler.main.jar")
    manifest {
        attributes["Main-Class"] = "MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    testImplementation(kotlin("test"))

    // OkHttp : Square’s meticulous HTTP client for Java and Kotlin.
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")

    // JSON In Java : JSON is a light-weight, language independent, data interchange format.See http://www.JSON.org/The
	// files in this package implement JSON encoders/decoders in Java.It also includes
	// the capability to convert between JSON and XML, HTTPheaders, Cookies, and CDL.This
	// is a reference implementation. There are a large number of JSON packagesin Java.
	// Perhaps someday the Java community will standardize on one. Untilthen, choose carefully.
    implementation("org.json:json:20250517")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}