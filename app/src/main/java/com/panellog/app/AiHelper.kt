package com.panellog.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Клиент ИИ-API для отдельной сборки G1 Hotelo AI.
 * Автоматически определяет провайдера по началу ключа:
 *  - gsk_...    → Groq (api.groq.com)
 *  - sk-or-...  → OpenRouter (openrouter.ai)
 *  - sk-...     → OpenAI (api.openai.com)
 *  - AQ. / AIza → Google Gemini (generativelanguage.googleapis.com / Vertex Express)
 */
object AiHelper {

    private val GEMINI_MODELS = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
    private val GROQ_MODELS = listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b")
    private val OPENROUTER_MODELS = listOf("google/gemini-2.5-flash:free", "meta-llama/llama-3.3-70b-instruct:free", "openrouter/free")
    private val OPENAI_MODELS = listOf("gpt-4.1-mini", "gpt-4o-mini")

    fun ask(apiKey: String, systemPrompt: String, userPrompt: String): Result<String> {
        val key = apiKey.trim()
        if (key.isEmpty()) return Result.failure(IllegalArgumentException("API key is empty"))
        return when {
            key.startsWith("gsk_") -> askOpenAiCompat("https://api.groq.com/openai/v1/chat/completions", key, GROQ_MODELS, systemPrompt, userPrompt)
            key.startsWith("sk-or-") -> askOpenAiCompat("https://openrouter.ai/api/v1/chat/completions", key, OPENROUTER_MODELS, systemPrompt, userPrompt)
            key.startsWith("sk-") -> askOpenAiCompat("https://api.openai.com/v1/chat/completions", key, OPENAI_MODELS, systemPrompt, userPrompt)
            else -> askGemini(key, systemPrompt, userPrompt)
        }
    }

    private fun askOpenAiCompat(urlStr: String, key: String, models: List<String>, systemPrompt: String, userPrompt: String): Result<String> {
        var lastErr = "Unknown error"
        for (model in models) {
            try {
                val msgs = JSONArray()
                if (systemPrompt.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", systemPrompt))
                msgs.put(JSONObject().put("role", "user").put("content", userPrompt))
                val payload = JSONObject()
                    .put("model", model)
                    .put("messages", msgs)
                    .put("temperature", 0.3)
                    .put("max_tokens", 4096)
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 45000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (_: Exception) { "" }
                conn.disconnect()
                if (code in 200..299) {
                    val j = JSONObject(body)
                    val msgObj = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                    val txt = (msgObj?.optString("content", "") ?: "").trim()
                    if (txt.isNotEmpty()) return Result.success(txt)
                    lastErr = "Empty response from $model"
                } else {
                    val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200) } catch (_: Exception) { body.take(200) }
                    lastErr = "HTTP $code ($model): $msg"
                    if (code == 404 || code == 429 || code == 500 || code == 503) continue
                    return Result.failure(IllegalStateException(lastErr))
                }
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                return Result.failure(IllegalStateException(lastErr))
            }
        }
        return Result.failure(IllegalStateException(lastErr))
    }

    private fun askGemini(key: String, systemPrompt: String, userPrompt: String): Result<String> {
        // Новые ключи Google AI Studio тоже начинаются на AQ. — всегда сначала пробуем
        // generativelanguage.googleapis.com, и только при ошибке 401/403/404 для AQ. пробуем Vertex Express.
        val bases = if (key.startsWith("AQ.")) listOf(
            "https://generativelanguage.googleapis.com/v1beta/models/",
            "https://aiplatform.googleapis.com/v1/publishers/google/models/"
        ) else listOf(
            "https://generativelanguage.googleapis.com/v1beta/models/"
        )
        var lastErr = "Unknown error"
        for (base in bases) {
            for (model in GEMINI_MODELS) {
                val urlStr = "$base$model:generateContent"
                try {
                    val payload = JSONObject().apply {
                        if (systemPrompt.isNotBlank()) {
                            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
                        }
                        put("contents", JSONArray().put(
                            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                        ))
                        put("generationConfig", JSONObject().put("temperature", 0.3).put("maxOutputTokens", 8192))
                    }
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        connectTimeout = 15000
                        readTimeout = 45000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("x-goog-api-key", key)
                    }
                    conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val body = try {
                        (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    } catch (_: Exception) { "" }
                    conn.disconnect()
                    if (code in 200..299) {
                        val j = JSONObject(body)
                        val cands = j.optJSONArray("candidates")
                        val parts = cands?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val sb = StringBuilder()
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val t = parts.optJSONObject(i)?.optString("text", "") ?: ""
                                if (t.isNotEmpty()) sb.append(t)
                            }
                        }
                        val txt = sb.toString().trim()
                        if (txt.isNotEmpty()) return Result.success(txt)
                        lastErr = "Empty response from $model"
                    } else {
                        val msg = try { JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200) } catch (_: Exception) { body.take(200) }
                        lastErr = "HTTP $code ($model): $msg"
                        if (code == 401 || code == 403) break // пробуем следующий base (Vertex), если есть
                        if (code == 404 || code == 429 || code == 500 || code == 503) continue // авто-переход на запасную модель
                        return Result.failure(IllegalStateException(lastErr))
                    }
                } catch (e: Exception) {
                    lastErr = e.message ?: e.javaClass.simpleName
                    return Result.failure(IllegalStateException(lastErr))
                }
            }
        }
        return Result.failure(IllegalStateException(lastErr))
    }

    fun systemPrompt(he: Boolean): String = if (he) {
        "אתה מהנדס מומחה למערכות גילוי אש Notifier NFS2-3030 (ישראל, תקן 1220). " +
            "ענה בקצרה, בצורה ברורה ומעשית לטכנאי בשטח: זהה נתקים בלופ (Loop), קצרים, תקלות הארקה, גלאים חוזרים, ואזעקות שווא. " +
            "כתוב בעברית ברורה עם נקודות מסודרות, בטקסט נקי ללא סימני Markdown כמו ** או ###."
    } else {
        "Ты опытный инженер по пожарной сигнализации Notifier NFS2-3030 (Израиль, стандарт 1220). " +
            "Отвечай кратко, чётко и практически для техника на объекте: ищи обрывы/КЗ на шлейфах (Loop), замыкания на землю, " +
            "повторяющиеся датчики, причины ложных пожарных тревог и предлагай понятный порядок проверки. " +
            "Отвечай на русском языке (если просят черновик для клиента — пиши на грамотном иврите). " +
            "Пиши чистым читаемым текстом без звёздочек Markdown (** или ###), используй списки с «• » и пустые строки."
    }

    private fun fmt(ts: Long) = if (ts > 0) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).format(Date(ts)) else ""

    fun buildSiteFaultsContext(site: Site, panels: List<Panel>, faults: List<Fault>, store: Store): String {
        val pName = panels.associate { it.id to it.name }
        val active = faults.filter { it.active && !it.hidden }
        val fixed = faults.filter { !it.active && !it.hidden }.take(30)
        val sb = StringBuilder()
        sb.append("Объект: ").append(site.name)
        if (site.address.isNotEmpty()) sb.append(" (").append(site.address).append(")")
        sb.append("\nПанелей: ").append(panels.size).append(" · Активных ошибок: ").append(active.size).append("\n\n")
        sb.append("=== АКТИВНЫЕ НЕИСПРАВНОСТИ (").append(active.size).append(") ===\n")
        for (f in active.take(120)) {
            sb.append("- [").append(pName[f.panelId] ?: "רכזת").append("] ")
            if (f.address.isNotEmpty()) sb.append(f.address).append(' ')
            if (f.zone.isNotEmpty()) sb.append(f.zone).append(' ')
            if (f.devType.isNotEmpty()) sb.append('(').append(f.devType).append(") ")
            sb.append("| ").append(f.problem)
            val pl = listOf(f.label, f.label2, f.node).filter { it.isNotEmpty() }.joinToString(" · ")
            if (pl.isNotEmpty()) sb.append(" | ").append(pl)
            if (f.recur > 0) sb.append(" | повтор ×").append(f.recur)
            if (f.repaired) sb.append(" | отмечено: починил")
            if (f.parked) sb.append(" | не актуально")
            if (f.action.isNotEmpty()) sb.append(" | действие: ").append(store.actionHe(f.action))
            if (f.comment.isNotBlank()) sb.append(" | комм: ").append(f.comment)
            sb.append('\n')
        }
        if (fixed.isNotEmpty()) {
            sb.append("\n=== НЕДАВНО УСТРАНЁННЫЕ (").append(fixed.size).append(") ===\n")
            for (f in fixed) {
                sb.append("- ").append(f.address.ifEmpty { "Система" }).append(" | ").append(f.problem)
                val pl = listOf(f.label, f.label2).filter { it.isNotEmpty() }.joinToString(" · ")
                if (pl.isNotEmpty()) sb.append(" | ").append(pl)
                if (f.resolvedTs > 0) sb.append(" | ушла ").append(fmt(f.resolvedTs))
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    fun buildRecordsContext(site: Site, panels: List<Panel>, recs: List<RecordRec>, maxEvents: Int = 120): String {
        val pName = panels.associate { it.id to it.name }
        val sb = StringBuilder()
        sb.append("Объект: ").append(site.name).append(" · Записей тревог/истории: ").append(recs.size).append("\n\n")
        var count = 0
        for (r in recs.take(5)) {
            if (count >= maxEvents) break
            val all = try {
                PanelDecoder.decode(File(r.raw).readBytes()).let { d -> if (d.graphic) PanelDecoder.parse(d.visualLines) else emptyList() }
            } catch (_: Exception) { emptyList() }
            val evs = RecordExport.select(all, maxEvents - count)
            sb.append("=== ").append(if (r.kind == "alarms") "ПОЖАРНЫЕ ТРЕВОГИ" else "ИСТОРИЯ")
                .append(" · ").append(pName[r.panelId] ?: "").append(" · ").append(fmt(r.ts))
                .append(" (всего ").append(all.size).append(") ===\n")
            for (e in evs) {
                sb.append(e.date).append(' ').append(e.time).append(" | ")
                    .append(e.event.trim()).append(" | ")
                    .append(e.address.ifEmpty { "Система" }).append(' ')
                    .append(e.zone).append(" | ")
                    .append(e.problem).append(" | ")
                    .append(listOf(e.label, e.label2, e.node).filter { it.isNotEmpty() }.joinToString(" · "))
                    .append('\n')
                count++
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    fun buildEventsContext(title: String, siteName: String, panelName: String, events: List<PanelDecoder.PanelEvent>): String {
        val sb = StringBuilder()
        sb.append(title).append(" · Объект: ").append(siteName)
        if (panelName.isNotEmpty()) sb.append(" · ").append(panelName)
        sb.append(" · Событий: ").append(events.size).append("\n\n")
        for (e in events.take(150)) {
            sb.append(e.date).append(' ').append(e.time).append(" | ")
                .append(e.event.trim()).append(" | ")
                .append(e.address.ifEmpty { "Система" }).append(' ')
                .append(e.zone).append(" | ")
                .append(e.problem).append(" | ")
                .append(listOf(e.label, e.label2, e.node).filter { it.isNotEmpty() }.joinToString(" · "))
                .append('\n')
        }
        return sb.toString()
    }
}
