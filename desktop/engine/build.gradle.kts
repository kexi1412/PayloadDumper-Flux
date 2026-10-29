import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "2.1.0"
    application
    id("com.google.protobuf") version "0.9.4"
}

group = "com.flux.payload"
version = "3.1.0-pc.1"
kotlin { jvmToolchain(21) }
application { mainClass.set("com.flux.payload.desktop.MainKt") }

sourceSets {
    main {
        kotlin.srcDirs("src/main/kotlin", "../../app/src/main/java")
        kotlin.exclude(
            "**/DumperApplication.kt", "**/MainActivity.kt", "**/data/**",
            "**/ui/**", "**/viewmodel/**", "**/model/SourceSelection.kt",
            "**/core/UriSource.kt", "**/core/Net.kt", "**/core/Diagnostics.kt",
            "**/core/NestedDirCache.kt",
        )
        proto.srcDir("../../app/src/main/proto")
    }
    test {
        kotlin.srcDirs("src/test/kotlin", "../../app/src/test/java")
        kotlin.include("com/flux/payload/dumper/core/**", "com/flux/payload/desktop/**")
        kotlin.exclude("**/MeizuOtaSmokeTest.kt") // Uses Android's generated BuildConfig.
        resources.srcDir("../../app/src/test/resources")
    }
}

protobuf { protoc { artifact = "com.google.protobuf:protoc:4.29.1" } }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.google.protobuf:protobuf-java:4.29.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("io.airlift:aircompressor:0.27")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("file.encoding", "UTF-8")
    testLogging { events("passed", "failed", "skipped") }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.register<Jar>("fatJar") {
    archiveFileName.set("flux-engine.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.flux.payload.desktop.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(tasks.classes)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "module-info.class", "META-INF/versions/**/module-info.class")
}
