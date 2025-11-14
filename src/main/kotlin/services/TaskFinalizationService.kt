package com.example.services

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.LocalDateTime

object TaskFinalizationService {
    private var job: Job? = null

    fun start(scope: CoroutineScope, checkIntervalSeconds: Long = 60) {
        job = scope.launch {
            while (isActive) {
                try {
                    finalizeOverdueTasks()
                } catch (e: Exception) {
                    println("Error finalizing tasks: ${e.message}")
                }
                delay(checkIntervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun finalizeOverdueTasks() {
        val now = LocalDateTime.now()

        val overdueTasks = dbQuery {
            Tasks.select(Tasks.id, Tasks.groupId, Tasks.status, Tasks.dueDate)
                .where {
                    (Tasks.status eq Status.ACTIVE) and (Tasks.dueDate.isNotNull()) and (Tasks.dueDate lessEq now)
                }
                .map { it[Tasks.id].value to it[Tasks.groupId].value }
        }

        overdueTasks.forEach { (taskId, groupId) ->
            finalizeTask(taskId, groupId)
        }
    }

    private suspend fun finalizeTask(taskId: Int, groupId: Int) {
        dbQuery {
            // a task can have multiple claims (depends on task.quantity set)
            // claim-to-submission is one-to-one
            // a claim without a submission is considered overdue
            // provided that the claim is not released, and claimant is still active
            val overdueClaims = (Claims leftJoin Submissions leftJoin Memberships)
                .select(Claims.id, Claims.claimantId)
                .where {
                    (Claims.taskId eq taskId) and
                            (Claims.releasedAt.isNull()) and
                            (Submissions.id.isNull()) and
                            (Memberships.userId eq Claims.claimantId) and
                            (Memberships.groupId eq groupId) and
                            (Memberships.status eq Status.ACTIVE)
                }
                .toList()

            // overdue claims should be released
            if (overdueClaims.isNotEmpty()) {
                val overdueClaimIds = overdueClaims.map { it[Claims.id].value }

                Claims.update({ Claims.id inList overdueClaimIds }) {
                    it[releasedAt] = LocalDateTime.now()
                }
                // TODO remove print statements
                println("Task $taskId: Released ${overdueClaims.size} overdue claim(s)")
            }

            Tasks.update({ Tasks.id eq taskId }) {
                it[status] = Status.INACTIVE
            }

            println("Task $taskId finalized")
        }
    }
}
