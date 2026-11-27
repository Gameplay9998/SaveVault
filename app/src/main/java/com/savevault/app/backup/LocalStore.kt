package com.savevault.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

data class LocalEntry(val name: String, val size: Long, val modified: Long, val ref: String)

/**
 * Onde os backups locais ficam. Cada jogo ganha uma subpasta "<Nome>__<id>",
 * e cada backup é um arquivo "<Nome>_<data>.zip".
 */
interface LocalStore {
    val description: String
    fun save(folderName: String, idSuffix: String, fileName: String, src: File)
    fun list(idSuffix: String): List<LocalEntry>
    fun open(ref: String): InputStream
    fun delete(ref: String)
}

/** Pasta interna do app (Android/data/com.savevault.app/files/backups). Apagada se desinstalar. */
class FileLocalStore(private val root: File) : LocalStore {
    override val description = root.absolutePath

    private fun gameDir(idSuffix: String): File? =
        root.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith("__$idSuffix") }

    override fun save(folderName: String, idSuffix: String, fileName: String, src: File) {
        val dir = gameDir(idSuffix) ?: File(root, "${folderName}__$idSuffix").apply { mkdirs() }
        src.copyTo(File(dir, fileName), overwrite = true)
    }

    override fun list(idSuffix: String): List<LocalEntry> =
        gameDir(idSuffix)?.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.map { LocalEntry(it.name, it.length(), it.lastModified(), it.absolutePath) }
            ?: emptyList()

    override fun open(ref: String): InputStream = FileInputStream(ref)

    override fun delete(ref: String) {
        File(ref).delete()
    }
}

/** Pasta escolhida pelo usuário (pode ser no armazenamento interno ou cartão SD). */
class SafLocalStore(private val context: Context, treeUri: Uri) : LocalStore {
    private val tree = SafTree(context, treeUri)
    override val description: String = treeUri.lastPathSegment?.substringAfter(':') ?: treeUri.toString()

    private fun gameDirId(idSuffix: String): String? =
        tree.list(tree.rootId).firstOrNull { it.isDir && it.name.endsWith("__$idSuffix") }?.id

    override fun save(folderName: String, idSuffix: String, fileName: String, src: File) {
        val dirId = gameDirId(idSuffix) ?: tree.createDir(tree.rootId, "${folderName}__$idSuffix")
        val fileId = tree.createFile(dirId, fileName, "application/zip")
        tree.openWrite(fileId).use { out -> src.inputStream().use { it.copyTo(out) } }
    }

    override fun list(idSuffix: String): List<LocalEntry> {
        val dirId = gameDirId(idSuffix) ?: return emptyList()
        return tree.list(dirId)
            .filter { !it.isDir && it.name.endsWith(".zip") }
            .map { LocalEntry(it.name, it.size, it.modified, tree.uri(it.id).toString()) }
    }

    override fun open(ref: String): InputStream =
        context.contentResolver.openInputStream(Uri.parse(ref))
            ?: throw java.io.FileNotFoundException(ref)

    override fun delete(ref: String) {
        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(ref))
    }
}
