// Module 40's build: SeatWave's disaster recovery. JDK-only models (point-in-time restore on real
// files, RPO and RTO per tier, the drill size model, the chaos experiment, the plan check) and the
// lab on real PostgreSQL with pgBackRest: the load generator, the JDBC checker, the pilot light.
plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Expected failures (a rejected backup, a replay that stopped short, a refused restore) as values.
    implementation(libs.result4j)
    // The drill reads pgBackRest's "info --output=json" into records.
    implementation(libs.jackson.databind)
    // The lab programs talk to PostgreSQL; the load generator's bounded pool is the client side of
    // the primary-kill experiment, which is about how a pool follows a database that went away.
    implementation(libs.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val stackSettings = file("config/stack.properties").absolutePath

// Tests tagged "stack" need the lab of compose.yaml; ./gradlew check never runs them, so a reader
// without Docker can still build and test. Backup, restore and replication are the subject, so no
// embedded stand-in replaces PostgreSQL and pgBackRest.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    // Tests read the plans and golden files by a path relative to the project folder.
    workingDir = projectDir
    testLogging {
        events("failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the lab stack (scripts/stack.sh test)."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    workingDir = projectDir
    useJUnitPlatform {
        includeTags("stack")
    }
    systemProperty("seatwave.stackSettings", stackSettings)
    // The stack is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Code that only runs against the stack is exercised by stackTest, not by ./gradlew check.
val stackOnly = listOf("**/stack/**")

tasks.jacocoTestReport {
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(stackOnly) })
    reports {
        xml.required = true
    }
}

// Every other class counts: the programs' main methods are tested too, by capturing what they print.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(stackOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// One task per program, so a reader runs one command per example. Programs that write files take a
// work folder under build/, emptied first.
data class Program(val task: String, val mainClass: String, val summary: String, val args: List<String>)

val work = layout.buildDirectory.dir("work").get().asFile
val programs = listOf(
    Program("pitrDrill", "io.plainround.seatwave.recovery.PitrDrillCli", "Four point-in-time restores on real files, each checked", listOf("$work/pitr")),
    Program("objectives", "io.plainround.seatwave.recovery.objectives.RecoveryObjectivesCli", "RPO and RTO per tier from the mechanism, and the break-even", listOf()),
    Program("restoreDrillModel", "io.plainround.seatwave.recovery.drill.RestoreDrillCli", "A timed restore on this machine's disk and the size model", listOf("$work/drill")),
    Program("chaos", "io.plainround.seatwave.recovery.chaos.ChaosExperimentCli", "The primary-kill experiment, its failures and the game day", listOf()),
    Program("runbookCheck", "io.plainround.seatwave.recovery.runbook.RunbookCheckCli", "The recovery plan, draft and fixed, checked against the loss of region-a", listOf("region-a", "dr/region-b-recovery-draft.tsv", "dr/region-b-recovery.tsv")),
    Program("runbookCheckRegionB", "io.plainround.seatwave.recovery.runbook.RunbookCheckCli", "The fixed plan checked against the loss of region-b", listOf("region-b", "dr/region-b-recovery.tsv")),
    Program("lab1Run", "io.plainround.seatwave.recovery.labs.Lab1StoreObjectives", "Lab 1: objectives for four stores, with reasons", listOf()),
    Program("lab2Run", "io.plainround.seatwave.recovery.labs.Lab2ChosenMoment", "Lab 2: restores to three moments around a mistake", listOf("$work/lab2")),
    Program("lab3Run", "io.plainround.seatwave.recovery.labs.Lab3ScaledDrill", "Lab 3 on files: a scaled restore and the experiment after the pool fix", listOf("$work/lab3")),
)
programs.forEach { program ->
    tasks.register<JavaExec>(program.task) {
        group = "seatwave"
        description = program.summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = program.mainClass
        workingDir = projectDir
        args(program.args)
        doFirst {
            program.args.filter { it.startsWith(work.path) }.forEach { folder ->
                project.delete(folder)
                file(folder).mkdirs()
            }
        }
    }
}

// The programs that need the lab stack (scripts/stack.sh up first). Each takes the settings file
// and its own arguments from -Pargs="...".
val stackPrograms = listOf(
    Triple("confirmLoad", "io.plainround.seatwave.recovery.stack.ConfirmLoadCli", "Sends an exact number of confirms at a fixed rate and writes the drill manifest"),
    Triple("restoreCheck", "io.plainround.seatwave.recovery.stack.RestoreCheckCli", "The checker over JDBC: the restored copy against the drill manifest"),
    Triple("pilotLight", "io.plainround.seatwave.recovery.stack.PilotLightCli", "Subscribes the region-b copy, measures its lag, promotes it"),
    Triple("archiveLag", "io.plainround.seatwave.recovery.stack.ArchiveLagCli", "Samples the archive's lag behind the primary and times segment pushes"),
)
stackPrograms.forEach { (taskName, mainClassName, summary) ->
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = summary
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
        args(listOf(stackSettings) + providers.gradleProperty("args").orElse("").get().split(" ").filter { it.isNotBlank() })
    }
}

// scripts/restore-drill.sh runs the checker with java directly, so Gradle's start-up is not part
// of the drill's verify step; this writes the classpath it needs.
tasks.register("stackClasspath") {
    group = "seatwave"
    description = "Writes the runtime classpath of the lab programs to build/stack/classpath.txt."
    val runtime = sourceSets.main.get().runtimeClasspath
    dependsOn(tasks.classes)
    doLast {
        val target = layout.buildDirectory.file("stack/classpath.txt").get().asFile
        target.parentFile.mkdirs()
        target.writeText(runtime.files.joinToString(File.pathSeparator) + "\n")
    }
}
