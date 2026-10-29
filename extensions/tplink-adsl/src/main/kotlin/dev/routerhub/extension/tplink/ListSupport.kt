package dev.routerhub.extension.tplink

import dev.routerhub.api.InvalidSettingException
import org.jsoup.Jsoup

/** One row of a router list page, with the identifier its remove checkbox carries. */
internal class ListRow(val cells: List<String>, val removeId: String)

/** What a write has to do to turn the router's current list into the wanted one. */
internal class ListChange(val removeIds: List<String>, val toAdd: List<List<String>>)

internal object ListPages {

    /**
     * Rows of a view page: every table row holding a checkbox named [checkbox]. Cells are the other
     * `<td>`s, in order, so they line up with the page's column headers. Rows are found by the
     * page's own removal mechanism instead of by markup details, which keeps this working for the
     * different list pages of the firmware.
     */
    fun checkboxRows(html: String, checkbox: String = "rml"): List<ListRow> =
        Jsoup.parse(html).select("tr").mapNotNull { tr ->
            // Layout tables nest; only the innermost row is a data row.
            if (tr.select("tr").size != 1) return@mapNotNull null
            val box = tr.selectFirst("input[name=$checkbox]") ?: return@mapNotNull null
            val cells = tr.children()
                .filter { it.tagName() == "td" && it.selectFirst("input[name=$checkbox]") == null }
                .map { it.text().replace(' ', ' ').trim() }
            ListRow(cells, box.attr("value"))
        }

    /**
     * Multiset difference between [current] rows and the [wanted] ones. Unchanged rows match
     * (whatever their normalisation, because the host echoes back exactly what a read produced), so
     * only real removals and additions turn into requests. Two identical rows count twice.
     */
    fun diff(current: List<ListRow>, wanted: List<List<String>>): ListChange {
        val remaining = current.toMutableList()
        val toAdd = mutableListOf<List<String>>()
        for (row in wanted) {
            val match = remaining.indexOfFirst { it.cells == row }
            if (match >= 0) remaining.removeAt(match) else toAdd += row
        }
        return ListChange(remaining.map { it.removeId }, toAdd)
    }

    /** A wanted row must have exactly the columns the page shows. */
    fun requireShape(key: String, rows: List<List<String>>, columns: Int) {
        rows.firstOrNull { it.size != columns }?.let {
            throw InvalidSettingException(key, "A row has ${it.size} values; expected $columns")
        }
    }
}
