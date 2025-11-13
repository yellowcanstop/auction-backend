package com.example.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import com.example.models.*

// Hikari connection pool to cache Postgres db connections
// pool size: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
object DatabaseFactory {
    fun init() {
        val config = HikariConfig().apply{
            jdbcUrl = System.getenv("DATABASE_URL")
                ?: "jdbc:postgresql://localhost:5432/auction_db"
            driverClassName = "org.postgresql.Driver"
            username = System.getenv("DATABASE_USER")
                ?: "postgres"
            password = System.getenv("DATABASE_PASSWORD")
            maximumPoolSize = 20
        }

        val dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        transaction {
            SchemaUtils.create(
                Users, Groups, Memberships, Tasks, Claims, Submissions, Reviews, Auctions, Bids
            )
        }
    }

    // Atomic (uses transaction), non-blocking call
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}