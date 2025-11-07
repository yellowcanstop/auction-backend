package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.ClaimTaskResponse
import com.example.models.Claims
import com.example.models.CreateTaskRequest
import com.example.models.CreateTaskResponse
import com.example.models.Memberships
import com.example.models.Status
import com.example.models.TaskData
import com.example.models.Tasks
import com.example.models.ViewTaskResponse
import com.example.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable.isActive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.and
import kotlin.text.get
import kotlin.text.set

fun Route.taskRoutes() {
    authenticate("auth-jwt") {
        route("/api/tasks") {
            get("/view/{groupId}/{taskId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (notActiveMember(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You are not permitted to view tasks in this group"))
                    return@get
                }

                val tasks = dbQuery {
                    Tasks.select(Tasks.id, Tasks.taskName, Tasks.description, Tasks.status, Tasks.dueDate, Tasks.points, Tasks.quantity, Tasks.requireProof)
                        .where { (Tasks.groupId eq groupId) and (Tasks.status eq Status.ACTIVE)}
                }

                val taskList = tasks.map {
                    TaskData(
                        taskId = it[Tasks.id].value,
                        taskName = it[Tasks.taskName],
                        description = it[Tasks.description],
                        dueDate = it[Tasks.dueDate]?.toString(),
                        points = it[Tasks.points],
                        quantity = it[Tasks.quantity],
                        requireProof = it[Tasks.requireProof]
                    )
                }

                call.respond(HttpStatusCode.OK, ViewTaskResponse(taskList))
            }

            post("/create/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to edit this group"))
                    return@post
                }

                val request = call.receive<CreateTaskRequest>()

                if (validateDescription(request.description)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Description invalid"))
                    return@post
                }

                if (request.points < 0 || request.quantity < 1) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Points must be non-negative and quantity must be at least 1"))
                    return@post
                }

                val taskId = dbQuery {
                    Tasks.insertAndGetId {
                        it[Tasks.groupId] = groupId
                        it[Tasks.creatorId] = userId
                        it[Tasks.taskName] = request.taskName
                        it[Tasks.description] = request.description
                        it[Tasks.dueDate] = request.dueDate?.let { date -> LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME)}
                        it[Tasks.points] = request.points
                        it[Tasks.quantity] = request.quantity
                        it[Tasks.requireProof] = request.requireProof
                    }
                }

                call.respond(HttpStatusCode.Created, CreateTaskResponse(
                    task = TaskData(
                        taskId = taskId.value,
                        taskName = request.taskName,
                        description = request.description,
                        dueDate = request.dueDate,
                        points = request.points,
                        quantity = request.quantity,
                        requireProof = request.requireProof
                    )
                ))
            }

            post("/delete/{groupId}/{taskId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to edit this group"))
                    return@post
                }

                dbQuery {
                    Tasks.update({ Tasks.id eq taskId }) {
                        it[status] = Status.INACTIVE
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("message" to "Task deleted successfully"))
            }

            post("/claim/{groupId}/{taskId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))

                val userId = call.userId()

                if (notActiveMember(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You are not permitted to view tasks in this group"))
                    return@post
                }

                val existingClaim = dbQuery {
                    Claims.select(Claims.id)
                        .where { (Claims.taskId eq taskId) and (Claims.claimantId eq userId) and (Claims.status eq Status.ACTIVE) }
                        .singleOrNull()
                }

                if (existingClaim != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You already have an existing active claim on this task"))
                    return@post
                }

                val claimId = try {
                    dbQuery {
                        val task = Tasks.select(Tasks.quantity, Tasks.dueDate)
                                .where { (Tasks.id eq taskId) and (Tasks.status eq Status.ACTIVE) and (Tasks.quantity neq 0) }
                                .forUpdate() // pessimistic row-lock
                                .singleOrNull()
                            ?: throw IllegalStateException("Task not available")

                        if (task[Tasks.dueDate]?.isBefore(LocalDateTime.now()) == true) {
                            throw IllegalStateException("Task expired")
                        }

                        val newClaimId = Claims.insertAndGetId {
                            it[Claims.taskId] = taskId
                            it[Claims.claimantId] = userId
                        }

                        Tasks.update({ Tasks.id eq taskId }) {
                            it.update(quantity, quantity - 1) // use SQL to directly decrement
                        }

                        newClaimId.value
                    }
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    return@post
                }

                call.respond(HttpStatusCode.Created, ClaimTaskResponse(claimId))
            }
        }
    }
}

suspend fun notActiveMember(userId: Int, groupId: Int): Boolean {
    val membership = dbQuery {
        Memberships.select(Memberships.status)
            .where { (Memberships.userId eq userId) and (Memberships.groupId eq groupId) and (Memberships.status eq Status.ACTIVE) }
            .singleOrNull()
    }
    return membership == null
}
