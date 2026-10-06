package com.banca

import java.io.File

/**
 * Settings, from the environment first and then from a `.env` file beside the
 * server, which is how they are kept on a developer's machine. A deployment
 * sets real environment variables and has no such file.
 */
object Config {
    private val fromFile: Map<String, String> by lazy {
        val file = File(".env")
        if (!file.isFile) return@lazy emptyMap()

        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .associate { line ->
                val (name, value) = line.split("=", limit = 2)
                name.trim() to value.trim().removeSurrounding("\"").removeSurrounding("'")
            }
    }

    /** The setting called [name], or null when it is missing or left empty. */
    operator fun get(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() } ?: fromFile[name]?.takeIf { it.isNotBlank() }
}
