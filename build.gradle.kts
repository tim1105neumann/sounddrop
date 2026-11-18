plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

/**
 * Counts non-blank, non-comment lines per source set and reports how much code is shared
 * between platforms. Run with `./gradlew sharedCodeReport`.
 */
abstract class SharedCodeReport : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun count() {
        val root = rootDir.get().asFile
        val lines = mutableMapOf<String, Int>()
        sources.files.sorted().forEach { file ->
            val parts = file.relativeTo(root).invariantSeparatorsPath.split('/')
            val sourceSet =
                when {
                    parts.first() == "iosApp" -> "iosApp (Swift)"
                    parts.size > 2 && parts[1] == "src" -> "${parts[0]}/${parts[2]}"
                    else -> return@forEach
                }
            lines[sourceSet] = (lines[sourceSet] ?: 0) + codeLines(file)
        }
        val production = lines.filterKeys { !it.endsWith("Test") }
        val shared = production.filterKeys { it.endsWith("/commonMain") }.values.sum()
        val total = production.values.sum()
        val percent = if (total == 0) 0.0 else 100.0 * shared / total
        val text =
            buildString {
                appendLine("| Source set | Lines of code |")
                appendLine("|---|---:|")
                lines.toSortedMap().forEach { (set, count) -> appendLine("| $set | $count |") }
                appendLine()
                appendLine("**Shared production code: $shared of $total lines (${"%.1f".format(percent)} %).**")
            }
        report.get().asFile.writeText(text)
        println(text)
    }

    private fun codeLines(file: File): Int {
        var inBlock = false
        return file.readLines().count { raw ->
            val line = raw.trim()
            when {
                inBlock -> {
                    if (line.contains("*/")) inBlock = false
                    false
                }

                line.startsWith("/*") -> {
                    inBlock = !line.contains("*/")
                    false
                }

                else -> line.isNotEmpty() && !line.startsWith("//")
            }
        }
    }
}

tasks.register<SharedCodeReport>("sharedCodeReport") {
    description = "Reports lines of code per source set and the share of common code."
    sources.from(
        fileTree(rootDir) {
            include("modem/src/**/*.kt", "composeApp/src/**/*.kt", "composeApp/src/**/*.js", "iosApp/**/*.swift")
            include("androidApp/src/**/*.kt")
            exclude("**/build/**")
        },
    )
    rootDir.set(layout.projectDirectory)
    report.set(layout.buildDirectory.file("reports/shared-code.md"))
}
