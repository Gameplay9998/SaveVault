package com.savevault.app.backup

import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Executa comandos com root (su). Necessário para ler /data/data dos jogos Android. */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    fun isAvailable(): Boolean =
        runCatching { run("id", timeoutSec = 15).let { it.ok && it.out.contains("uid=0") } }
            .getOrDefault(false)

    fun run(cmd: String, timeoutSec: Long = 300): Result {
        val p = ProcessBuilder("su", "-c", cmd).start()
        var err = ""
        val errThread = thread { err = p.errorStream.bufferedReader().readText() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            return Result(-1, out, "Tempo esgotado")
        }
        errThread.join(2000)
        return Result(p.exitValue(), out, err)
    }

    /** Coloca entre aspas simples para o shell. */
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
