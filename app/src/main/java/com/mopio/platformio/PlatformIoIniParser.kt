package com.mopio.platformio

import java.io.File

/**
 * Minimal parser for platformio.ini files.
 *
 * Extracts [env:*] sections and their key=value pairs.
 * Does NOT support multi-line values or includes (those are handled by pio itself).
 */
object PlatformIoIniParser {

    data class Env(
        val name: String,
        val board: String,
        val platform: String,
        val framework: String,
        val raw: Map<String, String>
    )

    data class ProjectConfig(
        val envs: List<Env>,
        val globalSection: Map<String, String>
    )

    fun parse(iniFile: File): ProjectConfig = parse(iniFile.readText())

    fun parse(text: String): ProjectConfig {
        val lines = text.lines()
        var currentSection = ""
        val sections = mutableMapOf<String, MutableMap<String, String>>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.isBlank()) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removeSurrounding("[", "]").trim()
                sections.getOrPut(currentSection) { mutableMapOf() }
                continue
            }

            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0 && currentSection.isNotEmpty()) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim().removeComment()
                sections[currentSection]?.put(key, value)
            }
        }

        val global = sections["env"] ?: emptyMap()
        val envs = sections.entries
            .filter { it.key.startsWith("env:") }
            .map { (sectionName, kvs) ->
                val name = sectionName.removePrefix("env:")
                val merged = global.toMutableMap().also { it.putAll(kvs) }
                Env(
                    name      = name,
                    board     = merged["board"] ?: "",
                    platform  = merged["platform"] ?: "",
                    framework = merged["framework"] ?: "",
                    raw       = merged
                )
            }

        return ProjectConfig(envs = envs, globalSection = global)
    }

    private fun String.removeComment(): String {
        val idx = indexOf(';')
        return if (idx >= 0) substring(0, idx).trim() else this
    }
}
