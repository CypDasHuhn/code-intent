#!/usr/bin/env kotlin

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

data class GraphGuardConfig(
    val modules: List<String>,
    val allowedEdges: List<List<String>>
)

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)

fun main(args: Array<String>) {
    val rootDir = if (args.isNotEmpty()) File(args[0]) else File(".")
    val configFile = File(rootDir, "graph-guard.json")
    
    if (!configFile.exists()) {
        System.err.println("ERROR: graph-guard.json not found in $rootDir")
        System.exit(1)
    }
    
    val json = Files.readString(configFile.toPath())
    val gson = Gson()
    val config = gson.fromJson(json, GraphGuardConfig::class.java)
    
    val allowedEdges = config.allowedEdges.map { (from, to) -> "$from->$to" }.toSet()
    val modules = config.modules.toSet()
    
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    
    // Check each module's build.gradle.kts for dependencies
    for (module in config.modules) {
        val moduleDir = File(rootDir, module)
        val buildFile = File(moduleDir, "build.gradle.kts")
        
        if (!buildFile.exists()) {
            // Some modules like apps/cli have nested structure
            val altBuildFile = File(rootDir, "apps/$module/build.gradle.kts")
            if (altBuildFile.exists()) {
                validateBuildFile(altBuildFile, module, allowedEdges, modules, errors, warnings)
            } else {
                val altBuildFile2 = File(rootDir, "tests/$module/build.gradle.kts")
                if (altBuildFile2.exists()) {
                    validateBuildFile(altBuildFile2, module, allowedEdges, modules, errors, warnings)
                } else {
                    warnings.add("Module '$module' has no build.gradle.kts")
                }
            }
        } else {
            validateBuildFile(buildFile, module, allowedEdges, modules, errors, warnings)
        }
    }
    
    // Check for reverse edges (cycles would be caught by Gradle, but we check for explicitly forbidden reverse edges)
    checkReverseEdges(allowedEdges, errors)
    
    val result = ValidationResult(errors.isEmpty(), errors, warnings)
    
    if (!result.isValid) {
        System.err.println("GRAPH GUARD FAILED:")
        for (error in result.errors) {
            System.err.println("  ERROR: $error")
        }
        System.exit(1)
    } else {
        println("GRAPH GUARD PASSED")
        if (result.warnings.isNotEmpty()) {
            for (warning in result.warnings) {
                println("  WARNING: $warning")
            }
        }
    }
}

fun validateBuildFile(
    buildFile: File,
    module: String,
    allowedEdges: Set<String>,
    modules: Set<String>,
    errors: MutableList<String>,
    warnings: MutableList<String>
) {
    val content = Files.readString(buildFile.toPath())
    
    // Find all implementation(project(":module")) dependencies
    val projectDepPattern = Pattern.compile("implementation\\(project\\(\"\\:([^\"]+)\"\\)\\)")
    val matcher = projectDepPattern.matcher(content)
    
    while (matcher.find()) {
        val dep = matcher.group(1)
        val edge = "$module->$dep"
        
        if (!modules.contains(dep)) {
            errors.add("Module '$module' depends on unknown module '$dep'")
        } else if (!allowedEdges.contains(edge)) {
            errors.add("Module '$module' has undeclared dependency on '$dep' (edge '$edge' not in allowedEdges)")
        }
    }
    
    // Also check for api/project dependencies
    val apiDepPattern = Pattern.compile("api\\(project\\(\"\\:([^\"]+)\"\\)\\)")
    val apiMatcher = apiDepPattern.matcher(content)
    while (apiMatcher.find()) {
        val dep = apiMatcher.group(1)
        val edge = "$module->$dep"
        
        if (!modules.contains(dep)) {
            errors.add("Module '$module' depends on unknown module '$dep' (api)")
        } else if (!allowedEdges.contains(edge)) {
            errors.add("Module '$module' has undeclared api dependency on '$dep' (edge '$edge' not in allowedEdges)")
        }
    }
}

fun checkReverseEdges(allowedEdges: Set<String>, errors: MutableList<String>) {
    // Check if any allowed edge has a reverse that's also allowed (would create a cycle)
    for (edge in allowedEdges) {
        val parts = edge.split("->")
        if (parts.size == 2) {
            val reverse = "${parts[1]}->${parts[0]}"
            if (allowedEdges.contains(reverse)) {
                errors.add("Reverse edge detected: both '$edge' and '$reverse' are allowed (creates cycle)")
            }
        }
    }
}