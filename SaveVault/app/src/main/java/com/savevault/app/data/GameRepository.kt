package com.savevault.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Guarda a lista de jogos em um JSON dentro do armazenamento interno do app. */
class GameRepository private constructor(context: Context) {

    private val file = File(context.filesDir, "games.json")
    private val mutex = Mutex()
    private val _games = MutableStateFlow(load())
    val games: StateFlow<List<Game>> = _games

    fun get(id: String): Game? = _games.value.firstOrNull { it.id == id }

    suspend fun upsert(game: Game) = edit { list ->
        val i = list.indexOfFirst { it.id == game.id }
        if (i >= 0) list.toMutableList().also { it[i] = game } else list + game
    }

    suspend fun update(id: String, transform: (Game) -> Game) = edit { list ->
        list.map { if (it.id == id) transform(it) else it }
    }

    suspend fun delete(id: String) {
        get(id)?.coverPath?.let { File(it).delete() }
        edit { list -> list.filterNot { it.id == id } }
    }

    private suspend fun edit(block: (List<Game>) -> List<Game>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val newList = block(_games.value)
            save(newList)
            _games.value = newList
        }
    }

    private fun load(): List<Game> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { Game.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun save(list: List<Game>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        val tmp = File(file.parentFile, "games.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: GameRepository? = null
        fun get(context: Context): GameRepository =
            instance ?: synchronized(this) {
                instance ?: GameRepository(context.applicationContext).also { instance = it }
            }
    }
}
