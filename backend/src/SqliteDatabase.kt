package preferences.backend

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.Driver

internal expect fun openSqliteDatabase(url: String, options: ConnectionPool.Options): Driver
