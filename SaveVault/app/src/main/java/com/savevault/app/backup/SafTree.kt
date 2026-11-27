package com.savevault.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * Acesso rápido a uma pasta escolhida pelo usuário (Storage Access Framework).
 * Usa consultas diretas ao DocumentsContract, bem mais rápidas que DocumentFile.
 */
class SafTree(context: Context, val treeUri: Uri) {

    data class Node(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val modified: Long,
    )

    private val cr = context.contentResolver
    val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    fun list(parentId: String): List<Node> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<Node>()
        cr.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Node(
                    id = c.getString(0),
                    name = c.getString(1) ?: continue,
                    isDir = c.getString(2) == Document.MIME_TYPE_DIR,
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        } ?: throw FileNotFoundException("Sem acesso à pasta. Escolha a pasta novamente.")
        return out
    }

    /** Percorre a árvore inteira chamando [action] com o caminho relativo de cada item. */
    fun walk(parentId: String = rootId, prefix: String = "", action: (String, Node) -> Unit) {
        for (node in list(parentId)) {
            val path = prefix + node.name
            action(path, node)
            if (node.isDir) walk(node.id, "$path/", action)
        }
    }

    fun createDir(parentId: String, name: String): String {
        val uri = DocumentsContract.createDocument(cr, uri(parentId), Document.MIME_TYPE_DIR, name)
            ?: throw FileNotFoundException("Não foi possível criar a pasta $name")
        return DocumentsContract.getDocumentId(uri)
    }

    fun createFile(parentId: String, name: String, mime: String = "application/octet-stream"): String {
        val uri = DocumentsContract.createDocument(cr, uri(parentId), mime, name)
            ?: throw FileNotFoundException("Não foi possível criar o arquivo $name")
        return DocumentsContract.getDocumentId(uri)
    }

    fun delete(id: String) {
        DocumentsContract.deleteDocument(cr, uri(id))
    }

    fun openRead(id: String): InputStream =
        cr.openInputStream(uri(id)) ?: throw FileNotFoundException(id)

    /** Abre para escrita truncando o arquivo (com fallback para provedores sem "wt"). */
    fun openWrite(id: String): OutputStream =
        runCatching { cr.openOutputStream(uri(id), "wt") }.getOrNull()
            ?: cr.openOutputStream(uri(id), "w")
            ?: throw FileNotFoundException(id)

    fun isAccessible(): Boolean = runCatching { list(rootId); true }.getOrDefault(false)
}
