package com.aceinandroid.inkflow

import android.content.Context
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Locale

internal enum class BookSortField { TITLE, AUTHOR, LAST_READ, PROGRESS, IMPORTED }

internal data class BookshelfSort(
    val field: BookSortField = BookSortField.LAST_READ,
    val descending: Boolean = true,
) {
    fun apply(books: List<StoredBook>): List<StoredBook> {
        val collator = Collator.getInstance(Locale.getDefault())
        val times = books.associate { it.id to (bookTime(it.createdAt) to bookTime(it.updatedAt)) }
        val comparator = Comparator<StoredBook> { left, right ->
            val result = when (field) {
                BookSortField.TITLE -> collator.compare(left.title, right.title)
                BookSortField.AUTHOR -> collator.compare(left.author, right.author)
                BookSortField.LAST_READ -> times.getValue(left.id).second.compareTo(times.getValue(right.id).second)
                BookSortField.PROGRESS -> left.progress.compareTo(right.progress)
                BookSortField.IMPORTED -> times.getValue(left.id).first.compareTo(times.getValue(right.id).first)
            }
            (if (descending) -result else result).takeIf { it != 0 } ?: right.id.compareTo(left.id)
        }
        return books.sortedWith(comparator)
    }
}

private val bookTimestamp = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,6}))?(Z|[+-]\d{2}:?\d{2})?$""")

internal fun bookTime(value: String): Long {
    val match = bookTimestamp.matchEntire(value) ?: return Long.MIN_VALUE
    val milliseconds = match.groupValues[2].padEnd(3, '0').take(3)
    val zone = match.groupValues[3].let { if (it == "Z") "+0000" else it.replace(":", "") }
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS" + if (zone.isEmpty()) "" else "Z", Locale.US)
    format.isLenient = false
    return runCatching { format.parse("${match.groupValues[1]}.$milliseconds$zone")?.time }
        .getOrNull() ?: Long.MIN_VALUE
}

internal class BookshelfSortStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "native_bookshelf", Context.MODE_PRIVATE)

    fun load(): BookshelfSort = BookshelfSort(
        BookSortField.entries.firstOrNull { it.name == preferences.getString("sort_field", null) }
            ?: BookSortField.LAST_READ,
        preferences.getBoolean("descending", true),
    )

    fun save(value: BookshelfSort) {
        check(preferences.edit().putString("sort_field", value.field.name)
            .putBoolean("descending", value.descending).commit()) {
            "Cannot save bookshelf sorting"
        }
    }
}
