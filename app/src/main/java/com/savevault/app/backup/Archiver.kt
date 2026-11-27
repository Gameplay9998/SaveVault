package com.savevault.app.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Compacta/descompacta saves em .zip, tanto de pastas SAF quanto de pastas comuns. */
object Archiver {

    /** Compacta todo o conteúdo de uma pasta SAF. Retorna a quantidade de arquivos. */
    fun zipTree(tree: SafTree, out: File): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            tree.walk { path, node ->
                if (node.isDir) {
                    zos.putNextEntry(ZipEntry("$path/").apply { if (node.modified > 0) time = node.modified })
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(ZipEntry(path).apply { if (node.modified > 0) time = node.modified })
                    tree.openRead(node.id).use { it.copyTo(zos) }
                    zos.closeEntry()
                    count++
                }
            }
        }
        return count
    }

    /** Compacta uma pasta comum, ignorando links simbólicos e os nomes de [excludeTopLevel]. */
    fun zipDir(dir: File, out: File, excludeTopLevel: Set<String> = emptySet()): Int {
        var count = 0
        fun add(zos: ZipOutputStream, f: File, path: String) {
            if (Files.isSymbolicLink(f.toPath())) return
            if (f.isDirectory) {
                zos.putNextEntry(ZipEntry("$path/").apply { time = f.lastModified() })
                zos.closeEntry()
                f.listFiles()?.forEach { add(zos, it, "$path/${it.name}") }
            } else {
                zos.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
                BufferedInputStream(FileInputStream(f)).use { it.copyTo(zos) }
                zos.closeEntry()
                count++
            }
        }
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            dir.listFiles()
                ?.filter { it.name !in excludeTopLevel }
                ?.forEach { add(zos, it, it.name) }
        }
        return count
    }

    /** Extrai o zip por cima da pasta SAF (sobrescreve arquivos com o mesmo nome). */
    fun unzipToTree(zip: File, tree: SafTree) {
        val childrenCache = HashMap<String, MutableMap<String, SafTree.Node>>()
        fun children(dirId: String) = childrenCache.getOrPut(dirId) {
            tree.list(dirId).associateBy { it.name }.toMutableMap()
        }

        fun ensureDir(segments: List<String>): String {
            var cur = tree.rootId
            for (s in segments) {
                val existing = children(cur)[s]
                cur = if (existing != null && existing.isDir) {
                    existing.id
                } else {
                    val id = tree.createDir(cur, s)
                    children(cur)[s] = SafTree.Node(id, s, true, 0, 0)
                    childrenCache[id] = mutableMapOf()
                    id
                }
            }
            return cur
        }

        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val segments = safeSegments(entry.name)
                if (segments.isEmpty()) continue
                if (entry.isDirectory) {
                    ensureDir(segments)
                    continue
                }
                val parentId = ensureDir(segments.dropLast(1))
                val fileName = segments.last()
                val existing = children(parentId)[fileName]
                val fileId = if (existing != null && !existing.isDir) {
                    existing.id
                } else {
                    tree.createFile(parentId, fileName).also {
                        children(parentId)[fileName] = SafTree.Node(it, fileName, false, 0, 0)
                    }
                }
                tree.openWrite(fileId).use { zis.copyTo(it) }
            }
        }
    }

    /** Extrai o zip para uma pasta comum (com proteção contra "zip slip"). */
    fun unzipToDir(zip: File, dir: File) {
        dir.mkdirs()
        val base = dir.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val segments = safeSegments(entry.name)
                if (segments.isEmpty()) continue
                val target = File(dir, segments.joinToString(File.separator))
                if (!target.canonicalPath.startsWith(base)) throw IOException("Entrada inválida no zip")
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(target)).use { zis.copyTo(it) }
                }
            }
        }
    }

    /** "Impressão digital" da pasta: muda quando algum arquivo muda de tamanho/data. */
    fun fingerprintTree(tree: SafTree): String {
        val lines = ArrayList<String>()
        tree.walk { path, node -> if (!node.isDir) lines += "$path|${node.size}|${node.modified}" }
        lines.sort()
        return sha1(lines.joinToString("\n"))
    }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun safeSegments(name: String): List<String> {
        val segs = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return if (segs.any { it == ".." }) emptyList() else segs
    }
}
