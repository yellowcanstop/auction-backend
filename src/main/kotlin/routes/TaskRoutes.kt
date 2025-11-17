package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.ClaimData
import com.example.models.ClaimResponse
import com.example.models.Claims
import com.example.models.CreateSubmissionRequest
import com.example.models.CreateTaskRequest
import com.example.models.CreateTaskResponse
import com.example.models.Decision
import com.example.models.Difficulty
import com.example.models.GradeSubmissionRequest
import com.example.models.Groups
import com.example.models.Memberships
import com.example.models.ReviewData
import com.example.models.Reviews
import com.example.models.Status
import com.example.models.SubmissionData
import com.example.models.Submissions
import com.example.models.TaskData
import com.example.models.Tasks
import com.example.models.Users
import com.example.models.ViewClaimsResponse
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.alias
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.GroupLayout
import kotlin.and
import kotlin.text.get
import kotlin.text.set

fun Route.taskRoutes() {
    authenticate("auth-jwt") {
        route("/api/tasks") {
            get("/view/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (notActiveMember(userId, groupId) && !isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You are not permitted to view tasks in this group"))
                    return@get
                }

                val tasks = dbQuery {
                    Tasks.select(Tasks.id, Tasks.taskName, Tasks.description, Tasks.status, Tasks.dueDate, Tasks.points, Tasks.quantity, Tasks.requireProof)
                        .where { (Tasks.groupId eq groupId) and (Tasks.status eq Status.ACTIVE) and (Tasks.quantity neq 0)}
                        .map {
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
                }

                call.respond(HttpStatusCode.OK, ViewTaskResponse(tasks))
            }

            post("/create/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to create a task"))
                    return@post
                }

                val request = call.receive<CreateTaskRequest>()

                val descError = validateDescription(request.description)
                if (descError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to descError))
                    return@post
                }

                val nameError = validateTaskName(request.taskName)
                if (nameError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to nameError))
                    return@post
                }

                val dueDateError = validateDueDate(request.dueDate)
                if (dueDateError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to dueDateError))
                    return@post
                }

                val quantityError = validateQuantity(request.quantity)
                if (quantityError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to quantityError))
                    return@post
                }

                val pointsError = validatePoints(request.points)
                if (pointsError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to pointsError))
                    return@post
                }

                val taskLimits = dbQuery {
                    Groups.select(Groups.id, Groups.taskPointsMax, Groups.taskPointsMin, Groups.taskPointsAverage )
                        .where { (Groups.id eq groupId) and (Groups.status eq Status.ACTIVE) }
                        .singleOrNull()
                }

                if (taskLimits == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Group not found"))
                    return@post
                }
                val maxPoints = taskLimits[Groups.taskPointsMax]
                val minPoints = taskLimits[Groups.taskPointsMin]
                val avgPoints = taskLimits[Groups.taskPointsAverage]
                var points = request.points

                if (request.points != null) {
                    if (maxPoints != null && request.points > maxPoints) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task points exceed group maximum of $maxPoints"))
                        return@post
                    }
                    if (minPoints != null && request.points < minPoints) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task points below group minimum of $minPoints"))
                        return@post
                    }
                } else {
                    if (request.difficulty == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Either points or difficulty must be specified"))
                        return@post
                    }
                    if (minPoints == null || avgPoints == null || maxPoints == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You need to set task point ranges for the group to use difficulty-based point assignment"))
                        return@post
                    }
                    points = when (request.difficulty) {
                        Difficulty.EASY -> minPoints
                        Difficulty.AVERAGE-> avgPoints
                        Difficulty.HARD -> maxPoints
                    }
                }

                val trimmedTaskName = request.taskName.trim()
                val trimmedDescription = request.description.trim()

                val taskId = dbQuery {
                    Tasks.insertAndGetId {
                        it[Tasks.groupId] = groupId
                        it[Tasks.creatorId] = userId
                        it[Tasks.taskName] = trimmedTaskName
                        it[Tasks.description] = trimmedDescription
                        it[Tasks.dueDate] = request.dueDate?.let { date -> LocalDateTime.parse(date, DateTimeFormatter.ISO_DATE_TIME)}
                        it[Tasks.points] = points
                        it[Tasks.quantity] = request.quantity
                        it[Tasks.requireProof] = request.requireProof
                    }
                }

                call.respond(HttpStatusCode.Created, CreateTaskResponse(
                    task = TaskData(
                        taskId = taskId.value,
                        taskName = trimmedTaskName,
                        description = trimmedDescription,
                        dueDate = request.dueDate,
                        points = points,
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
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to remove a task"))
                    return@post
                }

                val task = dbQuery {
                    Tasks.select(Tasks.id)
                        .where { (Tasks.id eq taskId) and (Tasks.status eq Status.ACTIVE) }
                        .singleOrNull()
                }

                if (task == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task not found"))
                    return@post
                }

                dbQuery {
                    Tasks.update({ Tasks.id eq taskId }) {
                        it[status] = Status.INACTIVE
                    }
                    Claims.update( { (Claims.taskId eq taskId) and (Claims.releasedAt eq null) }) {
                        it[releasedAt] = LocalDateTime.now()
                    }
                    Submissions.update( { Submissions.taskId eq taskId }) {
                        it[status] = Status.INACTIVE
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("message" to "Task and all associated claims and submissions deleted successfully"))
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
                        .where { (Claims.taskId eq taskId) and (Claims.claimantId eq userId) and (Claims.releasedAt eq null) }
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

                call.respond(HttpStatusCode.Created, ClaimResponse(claimId))
            }

            post("/unclaim/{claimId}") {
                val claimId = call.parameters["claimId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid claim ID"))

                val userId = call.userId()

                try {
                    dbQuery {
                        val claim = Claims.select(Claims.taskId)
                            .where { (Claims.id eq claimId) and (Claims.claimantId eq userId) and (Claims.releasedAt eq null) }
                            .singleOrNull()
                            ?: throw IllegalStateException("You do not have an active claim.")

                        Claims.update({ Claims.id eq claimId }) {
                            it[releasedAt] = LocalDateTime.now()
                        }

                        Tasks.update({ Tasks.id eq claim[Claims.taskId] }) {
                            it.update(quantity, quantity + 1) // use SQL to directly increment
                        }
                    }
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    return@post
                }

                call.respond(HttpStatusCode.OK, ClaimResponse(claimId))
            }

            post("/submit/{groupId}/{claimId}") {
                val claimId = call.parameters["claimId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid claim ID"))

                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                val request = call.receive<CreateSubmissionRequest>()

                val textError = validateTextContent(request.textContent)
                if (textError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to textError))
                    return@post
                }

                val imageError = validateImageContent(request.imageContent)
                if (imageError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to imageError))
                    return@post
                }

                if (request.coAuthorId == userId) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You cannot add yourself as co-author"))
                    return@post
                }

                try {
                    dbQuery {
                        val claim = Claims.select(Claims.taskId, Claims.claimantId)
                                .where { (Claims.id eq claimId) and (Claims.claimantId eq userId) and (Claims.releasedAt eq null) }
                                .singleOrNull()
                                ?: throw IllegalStateException("You do not have an active claim.")

                        val task = Tasks.select(Tasks.requireProof, Tasks.groupId, Tasks.points, Tasks.dueDate)
                            .where { (Tasks.id eq claim[Claims.taskId]) and (Tasks.status eq Status.ACTIVE) }
                            .singleOrNull()
                            ?: throw IllegalStateException("Task not available")

                        if (task[Tasks.dueDate]?.isBefore(LocalDateTime.now()) == true) {
                            throw IllegalStateException("Task expired")
                        }

                        if (task[Tasks.requireProof]) {
                            if (request.textContent.isNullOrBlank() || request.imageContent.isNullOrBlank()) {
                                throw IllegalStateException("This task requires proof of completion.")
                            }
                        }

                        Memberships.select(Memberships.id).where { (Memberships.userId eq userId) and (Memberships.groupId eq task[Tasks.groupId]) and (Memberships.status eq Status.ACTIVE) }
                            .singleOrNull()
                            ?: throw IllegalStateException("You are not an active member of the group.")

                        if (request.coAuthorId != null) {
                            Memberships.select(Memberships.id)
                                .where { (Memberships.userId eq request.coAuthorId) and (Memberships.groupId eq task[Tasks.groupId]) and (Memberships.status eq Status.ACTIVE) }
                                .singleOrNull()
                                ?: throw IllegalStateException("Co-author is not an active member of the group.")
                        }

                        val subId = Submissions.insertAndGetId {
                            it[Submissions.taskId] = claim[Claims.taskId]
                            it[Submissions.claimId] = claimId
                            it[Submissions.authorId] = userId
                            it[Submissions.coAuthorId] = request.coAuthorId
                            it[Submissions.textContent] = request.textContent?.trim()
                            it[Submissions.imageContent] = request.imageContent?.trim()
                        }

                        val autoApprove = Groups.select(Groups.id, Groups.autoApprove, Groups.creatorId)
                                .where { (Groups.id eq groupId) and (Groups.status eq Status.ACTIVE) }
                                .singleOrNull()

                        if (autoApprove?.get(Groups.autoApprove) == true) {
                            Reviews.insert {
                                it[Reviews.claimId] = claimId
                                it[Reviews.submissionId] = subId
                                it[Reviews.reviewerId] = autoApprove[Groups.creatorId]
                                it[Reviews.decision] = Decision.ACCEPT
                            }
                            if (request.coAuthorId == null) {
                                Memberships.update({ (Memberships.id eq userId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + task[Tasks.points]
                                }
                            } else {
                                val splitPoints = task[Tasks.points] / 2
                                Memberships.update({ (Memberships.id eq userId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + splitPoints
                                }
                                Memberships.update({ (Memberships.id eq request.coAuthorId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + splitPoints
                                }
                            }
                        }
                    }
                } catch(e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    return@post
                }
                call.respond(HttpStatusCode.OK, mapOf("message" to "Submission created successfully"))
            }

            get("/claims/{groupId}/{taskId}") {
                val taskId = call.parameters["taskId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid task ID"))

                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to view claims"))
                    return@get
                }

                val task = dbQuery {
                    Tasks.select(Tasks.id)
                        .where { (Tasks.id eq taskId) and (Tasks.status eq Status.ACTIVE) }
                        .singleOrNull()
                }

                if (task == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Task not found"))
                    return@get
                }

                val claims = dbQuery {
                    (Claims innerJoin Users)
                        .select(Claims.id, Claims.claimantId, Users.username)
                        .where { (Claims.taskId eq taskId) and (Claims.releasedAt eq null) and (Claims.claimantId eq Users.id) }
                        .toList()
                }

                val claimIds = claims.map { it[Claims.id].value }

                if (claimIds.isEmpty()) {
                    call.respond(HttpStatusCode.OK, ViewClaimsResponse(emptyList()))
                    return@get
                }

                // Although submission is one-to-one with claim,
                // fetch all submissions for all claims in one query
                // instead of querying per claimId
                val submissions = dbQuery {
                    Submissions.select(Submissions.id, Submissions.claimId, Submissions.authorId, Submissions.coAuthorId, Submissions.submittedAt, Submissions.textContent, Submissions.imageContent)
                        .where { (Submissions.claimId inList claimIds) and (Submissions.status eq Status.ACTIVE) }
                        .toList()
                    }.groupBy { it[Submissions.claimId].value }

                // fetch co-author names
                val coAuthorIds = submissions.values.flatten().mapNotNull { it[Submissions.coAuthorId]?.value }.toSet()
                val coAuthorNames = if (coAuthorIds.isNotEmpty()) {
                    dbQuery {
                        Users.select(Users.id, Users.username)
                            .where { Users.id inList coAuthorIds }
                            .toList()
                    }.associate { it[Users.id].value to it[Users.username] }
                } else emptyMap()

                // review is one-to-one with submission which is one-to-one with claim
                val reviews = dbQuery {
                    Reviews.select(Reviews.id, Reviews.claimId, Reviews.submissionId, Reviews.reviewedAt, Reviews.decision)
                            .where { Reviews.claimId inList claimIds }
                        .toList()
                    }.groupBy { it[Reviews.claimId].value }

                // build list of ClaimData
                val claimList = claims.map { claimRow ->
                    ClaimData(
                        claimRow[Claims.id].value,
                        claimRow[Claims.claimantId].value,
                        claimRow[Users.username],
                        submissions[claimRow[Claims.id].value]?.firstOrNull()?.let { subRow ->
                            SubmissionData(
                                submissionId = subRow[Submissions.id].value,
                                authorId = claimRow[Claims.claimantId].value,
                                authorName = claimRow[Users.username],
                                coAuthorId = subRow[Submissions.coAuthorId]?.value,
                                coAuthorName = coAuthorNames[subRow[Submissions.coAuthorId]?.value],
                                submittedAt = subRow[Submissions.submittedAt].toString(),
                                textContent = subRow[Submissions.textContent],
                                imageContent = subRow[Submissions.imageContent]
                            )
                        },
                        reviews[claimRow[Claims.id].value]?.firstOrNull()?.let { revRow ->
                            ReviewData(
                                reviewId = revRow[Reviews.id].value,
                                reviewedAt = revRow[Reviews.reviewedAt].toString(),
                                decision = revRow[Reviews.decision]
                            )
                        }
                    )
                }

                call.respond(HttpStatusCode.OK, ViewClaimsResponse(claimList))
            }

            post("/grade/{groupId}/{submissionId}") {
                val submissionId = call.parameters["submissionId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid submission ID"))

                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                val request = call.receive<GradeSubmissionRequest>()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to grade this submission"))
                    return@post
                }

                try {
                    dbQuery {
                        val submission = (Submissions innerJoin Tasks)
                            .select(Submissions.id, Submissions.claimId, Submissions.taskId, Submissions.authorId, Submissions.coAuthorId, Submissions.status, Tasks.id, Tasks.points)
                            .where { (Submissions.id eq submissionId) and (Submissions.status eq Status.ACTIVE) and (Submissions.taskId eq Tasks.id)}
                            .singleOrNull()
                            ?: throw IllegalStateException("Submission not found")

                        val existingReview = Reviews.select(Reviews.id)
                            .where { (Reviews.submissionId eq submissionId) }
                            .singleOrNull()

                        if (existingReview != null) {
                            throw IllegalStateException("Submission has already been graded")
                        }

                        Reviews.insert {
                            it[Reviews.claimId] = submission[Submissions.claimId]
                            it[Reviews.submissionId] = submissionId
                            it[Reviews.reviewerId] = userId
                            it[Reviews.decision] = request.decision
                        }

                        if (request.decision == Decision.ACCEPT) {
                            val taskPoints = submission[Tasks.points]
                            val authorId = submission[Submissions.authorId].value
                            val coAuthorId = submission[Submissions.coAuthorId]?.value

                            if (coAuthorId == null) {
                                Memberships.update({ (Memberships.userId eq authorId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + taskPoints
                                }
                            } else {
                                val splitPoints = taskPoints / 2
                                Memberships.update({ (Memberships.userId eq authorId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + splitPoints
                                }
                                Memberships.update({ (Memberships.userId eq coAuthorId) and (Memberships.groupId eq groupId) }) {
                                    it[points] = points + splitPoints
                                }
                            }
                        }
                    }
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    return@post
                }

                call.respond(HttpStatusCode.OK, mapOf("message" to "Submission graded successfully"))
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

private fun validateTaskName(name: String): String? {
    val trimmedName = name.trim()
    return when {
        trimmedName.isBlank() -> "Task name cannot be blank"
        trimmedName.length < 3 -> "Task name must be at least 3 characters"
        trimmedName.length > 100 -> "Task name cannot exceed 100 characters"
        else -> null
    }
}

private fun validateDueDate(dueDate: String?): String? {
    if (dueDate == null) return null

    return try {
        val parsedDate = LocalDateTime.parse(dueDate, DateTimeFormatter.ISO_DATE_TIME)
        val now = LocalDateTime.now()
        val bufferMinutes = 1L // 1-minute buffer

        when {
            parsedDate.isBefore(now.minusMinutes(bufferMinutes)) ->
                "Due date cannot be in the past"
            parsedDate.isAfter(now.plusYears(1)) ->
                "Due date cannot be more than 1 year in the future"
            else -> null
        }
    } catch (e: Exception) {
        "Invalid date format. Use ISO-8601 format (yyyy-MM-dd'T'HH:mm:ss)"
    }
}

private fun validateQuantity(quantity: Int): String? {
    return when {
        quantity < 1 -> "Quantity must be at least 1"
        quantity > 1000 -> "Quantity cannot exceed 1000"
        else -> null
    }
}

private fun validatePoints(points: Int?): String? {
    if (points == null) return null
    return when {
        points < 0 -> "Points cannot be negative"
        points > 10000 -> "Points cannot exceed 10000"
        else -> null
    }
}

private fun validateTextContent(content: String?): String? {
    if (content == null) return null

    val trimmed = content.trim()
    return when {
        trimmed.length > 5000 -> "Text content cannot exceed 5000 characters"
        trimmed.contains("<script>", ignoreCase = true) -> "Text content contains invalid content"
        trimmed.contains("</script>", ignoreCase = true) -> "Text content contains invalid content"
        trimmed.contains("javascript:", ignoreCase = true) -> "Text content contains invalid content"
        else -> null
    }
}

private fun validateImageContent(content: String?): String? {
    if (content == null) return null

    val trimmed = content.trim()
    return when {
        trimmed.isBlank() -> "Image content cannot be blank"
        trimmed.length > 500 -> "Image URL cannot exceed 500 characters"
        !trimmed.startsWith("http://") && !trimmed.startsWith("https://") -> "Image content must be a valid URL"
        else -> null
    }
}
