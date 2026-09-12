import java.io.File

plugins { kotlin("jvm") version "2.2.0" }
repositories { mavenCentral() }
kotlin {
    // Reuse the actual portable brush identities without bringing Android rendering into the lab.
    sourceSets.main {
        kotlin.srcDir("../ink/src/main/kotlin")
        kotlin.srcDir("../format/src/main/kotlin")
        kotlin.include("com/forestnote/core/reader/**", "com/forestnote/core/ink/BrushKind.kt",
            "com/forestnote/core/format/SchemaReconciliation.kt", "com/forestnote/core/format/LegacySyncHistory.kt")
    }
    // Compare the actual writer registry too, without loading Android or a stale copied fixture.
    sourceSets.test {
        kotlin.srcDir("../format/src/main/kotlin")
        kotlin.include("com/forestnote/core/reader/**", "com/forestnote/core/format/ForestNoteRegistry.kt")
    }
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
        apiVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0
    }
}
java { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
dependencies {
    implementation("io.rhizome:rhizome-core:0.8.2")
    implementation("io.rhizome:rhizome-sqlite:0.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.0")
    testImplementation("org.xerial:sqlite-jdbc:3.45.2.0")
    testImplementation("io.rhizome:rhizome-http:0.8.2")
    testImplementation(project(":writer-schema"))
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
}
tasks.test {
    useJUnit()
    systemProperty("migrationChildClasspath", sourceSets.test.get().runtimeClasspath.asPath)
    // The large import fixture must exceed the entire Java heap, not just a chosen buffer size.
    maxHeapSize = "96m"
    inputs.property("importBooks", providers.environmentVariable("FORESTREAD_IMPORT_BOOKS").orElse(""))
    inputs.files(providers.environmentVariable("FORESTREAD_IMPORT_BOOKS").orElse("").map {
        it.split(File.pathSeparator).filter(String::isNotEmpty)
    })
    inputs.property("importReport", providers.environmentVariable("FORESTREAD_IMPORT_REPORT").orElse(""))
    inputs.property("huffVectors", providers.environmentVariable("FORESTREAD_HUFF_VECTORS").orElse(""))
    inputs.property("contractVectors", providers.environmentVariable("FORESTREAD_CONTRACT_VECTORS").orElse(""))
    inputs.property("projectionVectors", providers.environmentVariable("FORESTREAD_PROJECTION_VECTORS").orElse(""))
    inputs.files(providers.environmentVariable("FORESTREAD_TEST_SERVER").orElse("").map { if (it.isBlank()) emptyList<String>() else listOf(it) })
    testLogging { events("passed", "failed", "skipped") }
}

// Test-only child process classpath; no Android or production entrypoint.
tasks.register("e2eClasspath") {
    dependsOn(tasks.testClasses)
    doLast { file("build/e2e-classpath.txt").writeText(sourceSets.test.get().runtimeClasspath.asPath) }
}
