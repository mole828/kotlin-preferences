package preferences.backend

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.sqlite.SQLite

internal actual fun openSqliteDatabase(url: String, options: ConnectionPool.Options): Driver =
    SQLite(url = url, options = options)
