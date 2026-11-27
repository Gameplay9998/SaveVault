package com.savevault.app.data

import org.json.JSONObject
import java.util.UUID

enum class Category { ANDROID, EMULATOR }

/** De onde vêm os arquivos do save. */
enum class SourceType {
    /** Pasta escolhida pelo seletor do Android (SAF). Funciona sem root. */
    FOLDER,

    /** Caminho absoluto lido com root (ex.: /data/data/com.jogo). */
    ROOT
}

enum class Destination(val label: String) {
    LOCAL("Local"), DRIVE("Google Drive"), BOTH("Local + Drive");

    val local get() = this != DRIVE
    val drive get() = this != LOCAL
}

enum class StorageLocation { LOCAL, DRIVE }

data class Game(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: Category,
    val emulator: String? = null,
    val sourceType: SourceType = SourceType.FOLDER,
    /** URI da árvore SAF (quando sourceType = FOLDER). */
    val sourceUri: String? = null,
    /** Caminho absoluto (quando sourceType = ROOT). */
    val rootPath: String? = null,
    val packageName: String? = null,
    /** Caminho interno da capa copiada para o app. */
    val coverPath: String? = null,
    val destination: Destination = Destination.LOCAL,
    val autoBackup: Boolean = true,
    val onlyIfChanged: Boolean = true,
    val keepVersions: Int = 5,
    val lastBackupAt: Long = 0,
    val lastFingerprint: String? = null,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val shortId: String get() = id.replace("-", "").take(8)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("category", category.name)
        put("emulator", emulator)
        put("sourceType", sourceType.name)
        put("sourceUri", sourceUri)
        put("rootPath", rootPath)
        put("packageName", packageName)
        put("coverPath", coverPath)
        put("destination", destination.name)
        put("autoBackup", autoBackup)
        put("onlyIfChanged", onlyIfChanged)
        put("keepVersions", keepVersions)
        put("lastBackupAt", lastBackupAt)
        put("lastFingerprint", lastFingerprint)
        put("lastError", lastError)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject) = Game(
            id = o.getString("id"),
            name = o.getString("name"),
            category = enumOr(o.str("category"), Category.EMULATOR),
            emulator = o.str("emulator"),
            sourceType = enumOr(o.str("sourceType"), SourceType.FOLDER),
            sourceUri = o.str("sourceUri"),
            rootPath = o.str("rootPath"),
            packageName = o.str("packageName"),
            coverPath = o.str("coverPath"),
            destination = enumOr(o.str("destination"), Destination.LOCAL),
            autoBackup = o.optBoolean("autoBackup", true),
            onlyIfChanged = o.optBoolean("onlyIfChanged", true),
            keepVersions = o.optInt("keepVersions", 5),
            lastBackupAt = o.optLong("lastBackupAt", 0),
            lastFingerprint = o.str("lastFingerprint"),
            lastError = o.str("lastError"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

data class BackupVersion(
    val gameId: String,
    val name: String,
    val timestamp: Long,
    val size: Long,
    val location: StorageLocation,
    /** Caminho/URI (local) ou ID do arquivo (Drive). */
    val ref: String,
)

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

inline fun <reified T : Enum<T>> enumOr(value: String?, default: T): T =
    runCatching { enumValueOf<T>(value!!) }.getOrDefault(default)

/** Emuladores comuns, com a pasta onde o save costuma ficar (pode variar por versão/config). */
data class EmulatorPreset(val name: String, val hint: String)

val EMULATOR_PRESETS = listOf(
    EmulatorPreset("RetroArch", "RetroArch/saves e RetroArch/states"),
    EmulatorPreset("PPSSPP", "PSP/SAVEDATA (e PSP/PPSSPP_STATE)"),
    EmulatorPreset("Dolphin", "Pasta de usuário do Dolphin → GC / Wii"),
    EmulatorPreset("NetherSX2 / AetherSX2", "Pasta de dados → memcards"),
    EmulatorPreset("DuckStation", "Pasta de dados → memcards"),
    EmulatorPreset("Azahar / Citra / Lime3DS", "Pasta de usuário → sdmc/Nintendo 3DS"),
    EmulatorPreset("Eden / Sudachi / Citron / Yuzu", "Pasta de dados → nand/user/save"),
    EmulatorPreset("DraStic", "DraStic/backup"),
    EmulatorPreset("Lemuroid", "Pasta de saves configurada no app"),
    EmulatorPreset("My Boy! / Pizza Boy", "Pasta da ROM ou pasta de saves configurada"),
    EmulatorPreset("Mupen64Plus FZ", "Pasta de dados → GameData / saves"),
    EmulatorPreset("Winlator", "Pasta do container → drive_c/users"),
    EmulatorPreset("Outro", "Escolha a pasta onde o emulador grava os saves"),
)
