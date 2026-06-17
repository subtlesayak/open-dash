package com.example.opendash.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecretAndFlavorBoundaryTest {
    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    @Test
    fun ossFlavorDoesNotReferenceGoogleNavigationSdkClasses() {
        val ossSource = File(root, "app/src/oss/java")
        val text = ossSource.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(text.contains("com.google.android.libraries.navigation"))
    }

    @Test
    fun googleNavigationDependencyIsFlavorScoped() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue(gradle.contains("\"googleNavImplementation\"(libs.google.navigation)"))
        assertFalse(Regex("""(?m)^\s*implementation\(libs\.google\.navigation\)""").containsMatchIn(gradle))
    }

    @Test
    fun localOnlySecretFilesAreIgnored() {
        val ignore = File(root, ".gitignore").readText()
        assertTrue(ignore.contains("secrets.properties"))
        assertTrue(ignore.contains(".env"))
        assertTrue(ignore.contains("*.keystore"))
        assertTrue(ignore.contains("*.apk"))
    }
}
