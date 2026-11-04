package com.mmi.modinstaller.core

enum class Severity { INFO, WARNING, ERROR }

enum class FindingCode {
    // Archivio / file
    NOT_AN_ARCHIVE, HTML_PAGE, UNSUPPORTED_FORMAT, RAR5_UNSUPPORTED, PASSWORD_PROTECTED,
    CORRUPT_ARCHIVE, EMPTY_ARCHIVE, UNSUPPORTED_COMPRESSION,

    // Limiti e sicurezza
    ARCHIVE_TOO_LARGE, TOO_MANY_ENTRIES, ENTRY_TOO_LARGE, UNCOMPRESSED_TOO_LARGE, COMPRESSION_BOMB,
    UNSAFE_PATH, EXECUTABLE_FILE, DISK_SPACE,

    // Contenuto
    NO_CONTENT, JAVA_EDITION, NESTED_ARCHIVE, JUNK_FILES, RESTRUCTURED, MANIFEST_ONLY, JSON_FILE, EXTRA_FILES,

    // Manifest
    MANIFEST_MISSING, MANIFEST_INVALID, MANIFEST_FIELD, UUID_INVALID, VERSION_INVALID,
    MODULE_UNKNOWN, MODULE_UUID_SAME, FORMAT_VERSION, EDITOR_EXTENSION,

    // Dipendenze / compatibilità
    DEPENDENCY_MISSING, EXPERIMENTAL, ENGINE_TOO_OLD, WORLD_TOO_NEW, ENGINE_UNKNOWN,

    // Mondi
    WORLD_INCOMPLETE, WORLD_JAVA, WORLD_EXPERIMENTS, WORLD_UNREADABLE,

    // Duplicati
    DUPLICATE_IN_ARCHIVE, SAME_AS_IMPORTED, NEWER_THAN_IMPORTED, OLDER_THAN_IMPORTED,

    GENERIC
}

/**
 * Un'osservazione del motore di analisi. Un ERROR [overridable] blocca l'installazione ma l'utente
 * può forzarla (es. versione di Minecraft troppo vecchia); gli altri ERROR sono definitivi.
 */
data class Finding(
    val severity: Severity,
    val code: FindingCode,
    val message: String,
    val detail: String? = null,
    val overridable: Boolean = false
)
