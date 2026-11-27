package com.savevault.app.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.savevault.app.data.BackupVersion
import com.savevault.app.data.Settings
import com.savevault.app.data.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class DriveAuthRequired : IOException("Reconecte o Google Drive nas Configurações")

/**
 * Cliente mínimo da API REST do Google Drive v3.
 * Usa o escopo drive.file: o app só enxerga os arquivos que ele mesmo criou.
 */
class DriveClient(private val context: Context, private val settings: Settings) {

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val FOLDER_NAME = "SaveVault"
    }

    private val authClient get() = Identity.getAuthorizationClient(context)

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    // ---------- Conexão da conta ----------

    sealed interface ConnectStep {
        data class NeedsUser(val intent: PendingIntent) : ConnectStep
        data class Connected(val email: String?) : ConnectStep
    }

    /** Primeira etapa da conexão. Se precisar da escolha da conta, devolve um PendingIntent. */
    suspend fun beginConnect(): ConnectStep {
        val result = authClient.authorize(request()).await()
        val pi = result.pendingIntent
        return if (result.hasResolution() && pi != null) ConnectStep.NeedsUser(pi)
        else finishConnect(result)
    }

    /** Segunda etapa: resultado da tela de escolha da conta. */
    suspend fun completeConnect(data: Intent?): ConnectStep {
        val result = authClient.getAuthorizationResultFromIntent(data)
        return finishConnect(result)
    }

    private suspend fun finishConnect(result: AuthorizationResult): ConnectStep {
        val token = result.accessToken ?: throw IOException("A conta não liberou acesso ao Drive")
        val email = runCatching { fetchEmail(token) }.getOrNull()
        settings.driveConnected = true
        settings.driveEmail = email
        settings.driveFolderId = null
        return ConnectStep.Connected(email)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching {
            val token = token()
            val c = URL("https://oauth2.googleapis.com/revoke?token=$token").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.close()
            c.responseCode
            GoogleAuthUtil.clearToken(context, token)
        }
        settings.driveConnected = false
        settings.driveEmail = null
        settings.driveFolderId = null
    }

    /** Token de acesso sem interação (funciona em segundo plano depois da primeira conexão). */
    private suspend fun token(): String {
        if (!settings.driveConnected) throw DriveAuthRequired()
        val result = authClient.authorize(request()).await()
        if (result.hasResolution()) throw DriveAuthRequired()
        return result.accessToken ?: throw DriveAuthRequired()
    }

    // ---------- HTTP ----------

    private class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private suspend fun call(
        method: String,
        url: String,
        json: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        retry: Boolean = true,
    ): Resp = withContext(Dispatchers.IO) {
        val token = token()
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        extraHeaders.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (json != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(json.toString().toByteArray()) }
        }
        val code = c.responseCode
        if (code == 401 && retry) {
            GoogleAuthUtil.clearToken(context, token)
            return@withContext call(method, url, json, extraHeaders, retry = false)
        }
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.readText() ?: ""
        if (code >= 400) throw IOException("Drive respondeu $code: ${body.take(200)}")
        Resp(code, body, c.headerFields.filterKeys { it != null })
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------- Operações ----------

    private suspend fun fetchEmail(token: String): String? = withContext(Dispatchers.IO) {
        val c = URL("$API/about?fields=user(emailAddress)").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $token")
        if (c.responseCode != 200) return@withContext null
        JSONObject(c.inputStream.bufferedReader().readText())
            .optJSONObject("user")?.optString("emailAddress")
    }

    /** ID da pasta "SaveVault" no Drive (cria se não existir). */
    private suspend fun folderId(): String {
        settings.driveFolderId?.let { return it }
        val q = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = JSONObject(call("GET", "$API/files?q=${enc(q)}&fields=files(id)&spaces=drive").body)
            .getJSONArray("files")
        val id = if (found.length() > 0) {
            found.getJSONObject(0).getString("id")
        } else {
            val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME)
            JSONObject(call("POST", "$API/files?fields=id", meta).body).getString("id")
        }
        settings.driveFolderId = id
        return id
    }

    /** Envia o arquivo com upload "resumable" (suporta arquivos grandes). */
    suspend fun upload(file: File, name: String, gameId: String, timestamp: Long): String {
        val meta = JSONObject()
            .put("name", name)
            .put("parents", org.json.JSONArray().put(folderId()))
            .put("appProperties", JSONObject().put("gameId", gameId).put("ts", timestamp.toString()))
        val start = call(
            "POST", "$UPLOAD/files?uploadType=resumable&fields=id", meta,
            mapOf("X-Upload-Content-Type" to "application/zip", "X-Upload-Content-Length" to file.length().toString()),
        )
        val location = start.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value?.firstOrNull()
            ?: throw IOException("Drive não devolveu a URL de upload")

        return withContext(Dispatchers.IO) {
            val c = URL(location).openConnection() as HttpURLConnection
            c.requestMethod = "PUT"
            c.doOutput = true
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(file.length())
            c.setRequestProperty("Content-Type", "application/zip")
            c.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            val code = c.responseCode
            if (code !in 200..201) {
                val err = c.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("Falha no upload ($code): ${err.take(200)}")
            }
            JSONObject(c.inputStream.bufferedReader().readText()).getString("id")
        }
    }

    suspend fun list(gameId: String): List<BackupVersion> {
        val q = "'${folderId()}' in parents and trashed = false and " +
            "appProperties has { key='gameId' and value='$gameId' }"
        val out = ArrayList<BackupVersion>()
        var pageToken: String? = null
        do {
            val url = "$API/files?q=${enc(q)}&pageSize=200&spaces=drive" +
                "&fields=${enc("nextPageToken,files(id,name,size,createdTime,appProperties)")}" +
                (pageToken?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(call("GET", url).body)
            val files = json.getJSONArray("files")
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val ts = f.optJSONObject("appProperties")?.optString("ts")?.toLongOrNull()
                    ?: runCatching { Instant.parse(f.getString("createdTime")).toEpochMilli() }.getOrDefault(0L)
                out += BackupVersion(
                    gameId = gameId,
                    name = f.getString("name"),
                    timestamp = ts,
                    size = f.optString("size").toLongOrNull() ?: 0L,
                    location = StorageLocation.DRIVE,
                    ref = f.getString("id"),
                )
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    suspend fun download(fileId: String, dest: File) = withContext(Dispatchers.IO) {
        val c = URL("$API/files/$fileId?alt=media").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer ${token()}")
        c.readTimeout = 120_000
        if (c.responseCode != 200) throw IOException("Falha ao baixar do Drive (${c.responseCode})")
        c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    suspend fun delete(fileId: String) {
        call("DELETE", "$API/files/$fileId")
    }
}
