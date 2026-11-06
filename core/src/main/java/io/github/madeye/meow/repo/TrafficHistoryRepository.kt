package io.github.madeye.meow.repo

import io.github.madeye.meow.database.DailyTraffic
import io.github.madeye.meow.database.PrivateDatabase
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrafficTotals(val tx: Long, val rx: Long) {
    val total: Long get() = tx + rx

    companion object {
        val Zero = TrafficTotals(0, 0)
    }
}

/** Daily upload/download history behind the Traffic screen's data usage cards. */
class TrafficHistoryRepository {

    private val dao get() = PrivateDatabase.dailyTrafficDao

    suspend fun today(): TrafficTotals = withContext(Dispatchers.IO) {
        dao.getByDate(LocalDate.now().format(FORMATTER)).toTotals()
    }

    suspend fun thisMonth(): TrafficTotals = withContext(Dispatchers.IO) {
        val firstOfMonth = LocalDate.now().withDayOfMonth(1).format(FORMATTER)
        dao.getSince(firstOfMonth).fold(TrafficTotals.Zero) { acc, row ->
            TrafficTotals(acc.tx + row.tx, acc.rx + row.rx)
        }
    }

    /** Drops rows older than the retention window; previously done ad hoc on read. */
    suspend fun prune(keepDays: Long = 31) = withContext(Dispatchers.IO) {
        dao.deleteBefore(LocalDate.now().minusDays(keepDays).format(FORMATTER))
    }

    private fun DailyTraffic?.toTotals() =
        if (this == null) TrafficTotals.Zero else TrafficTotals(tx, rx)

    companion object {
        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
