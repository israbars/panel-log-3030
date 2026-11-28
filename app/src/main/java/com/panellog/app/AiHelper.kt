package com.panellog.app

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Подготовка промпта и контекста (активные ошибки, история, тревоги)
 * для анализа в мобильном приложении Gemini (.txt-вложение + короткая инструкция).
 */
object AiHelper {

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

    /** Объединённый контекст объекта: активные/устранённые ошибки + снятая история и тревоги (для поиска параллелей). */
    fun buildFullSiteContext(
        site: Site,
        panels: List<Panel>,
        faults: List<Fault>,
        recs: List<RecordRec>,
        store: Store,
        maxRecordEvents: Int = 80
    ): String {
        val sb = StringBuilder(buildSiteFaultsContext(site, panels, faults, store))
        if (recs.isNotEmpty()) {
            sb.append('\n').append(buildRecordsContext(site, panels, recs, maxRecordEvents))
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
