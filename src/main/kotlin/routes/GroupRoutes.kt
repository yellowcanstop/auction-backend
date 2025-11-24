package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.CreateGroupRequest
import com.example.models.CreateGroupResponse
import com.example.models.EditGroupRequest
import com.example.models.EditGroupResponse
import com.example.models.FCMTokenRequest
import com.example.models.GroupData
import com.example.models.Groups
import com.example.models.JoinGroupRequest
import com.example.models.JoinGroupResponse
import com.example.models.MemberData
import com.example.models.Memberships
import com.example.models.PointResponse
import com.example.models.Status
import com.example.models.Users
import com.example.models.ViewGroupsResponse
import com.example.models.ViewMembersRequest
import com.example.models.ViewMembersResponse
import com.example.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.http.set
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.update
import java.util.UUID
import kotlin.text.get
import kotlin.text.set


fun Route.groupRoutes() {
    authenticate("auth-jwt") {
        route("/api/groups") {
            post("/fcm") {
                val userId = call.userId()
                val request = call.receive<FCMTokenRequest>()

                if (request.fcmToken.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "FCM token cannot be blank"))
                    return@post
                }

                dbQuery {
                    Users.update({ Users.id eq userId }) {
                        it[fcmToken] = request.fcmToken.trim()
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("message" to "FCM token updated"))
            }

            post("/create") {
                val request = call.receive<CreateGroupRequest>()

                val userId = call.userId()

                val groupNameError = validateGroupName(request.groupName)
                if (groupNameError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to groupNameError))
                    return@post
                }

                val descError = validateDescription(request.description)
                if (descError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to descError))
                    return@post
                }

                val trimmedGroupName = request.groupName.trim()
                val trimmedDescription = request.description.trim()

                // Same creator cannot have multiple groups with the same name
                val existingGroups = dbQuery {
                    Groups.select(Groups.groupName).where { (Groups.creatorId eq userId) and (Groups.groupName eq trimmedGroupName) }
                        .singleOrNull()
                }

                if (existingGroups != null) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "You already have a group with this name"))
                    return@post
                }

                val code = generateInviteCode()
                var newCode = code

                val groupId = try {
                    dbQuery {
                        Groups.insertAndGetId {
                            it[groupName] = request.groupName
                            it[description] = request.description
                            it[creatorId] = userId
                            it[inviteCode] = code
                        }
                    }
                } catch (e: ExposedSQLException) {
                    // Retry with new invite code if rare UUID collision
                    newCode = generateInviteCode()
                    dbQuery {
                        Groups.insertAndGetId {
                            it[groupName] = request.groupName
                            it[description] = request.description
                            it[creatorId] = userId
                            it[inviteCode] = newCode
                        }
                    }
                }

                call.respond(HttpStatusCode.Created,
                    CreateGroupResponse(
                        groupId = groupId.value,
                        groupName = trimmedGroupName,
                        description = trimmedDescription,
                        inviteCode = newCode,
                        creatorId = userId
                ))
            }

            patch("/edit/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to edit this group"))
                    return@patch
                }

                val request = call.receive<EditGroupRequest>()

                if (noGroupFound(groupId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@patch
                }

                if (request.groupName != null) {
                    val nameError = validateGroupName(request.groupName)
                    if (nameError != null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to nameError))
                        return@patch
                    }
                }

                if (request.description != null) {
                    val descError = validateDescription(request.description)
                    if (descError != null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to descError))
                        return@patch
                    }
                }

                val pointsError = validateTaskPoints(request.taskPointsMin, request.taskPointsAverage, request.taskPointsMax)
                if (pointsError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to pointsError))
                    return@patch
                }

                dbQuery {
                    Groups.update({ Groups.id eq groupId }) {
                        request.groupName?.let { name -> it[groupName] = name }
                        request.description?.let { desc -> it[description] = desc }
                        request.autoApprove?.let { auto -> it[autoApprove] = auto }
                        request.taskPointsMin?.let { min -> it[taskPointsMin] = min }
                        request.taskPointsAverage?.let { avg -> it[taskPointsAverage] = avg }
                        request.taskPointsMax?.let { max -> it[taskPointsMax] = max }
                        request.status?.let { stat -> it[status] = stat }
                    }
                }

                call.respond(HttpStatusCode.OK, EditGroupResponse(
                    groupName = request.groupName,
                    description = request.description,
                    autoApprove = request.autoApprove,
                    taskPointsMin = request.taskPointsMin,
                    taskPointsAverage = request.taskPointsAverage,
                    taskPointsMax = request.taskPointsMax,
                    status = request.status
                ))
            }

            get("/members/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to view this group"))
                    return@get
                }

                if (noGroupFound(groupId)) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Group not found"))
                    return@get
                }

                val existingMembers = dbQuery {
                    (Memberships innerJoin Users)
                        .select(Memberships.userId, Memberships.points, Users.username)
                        .where { (Memberships.groupId eq groupId) and (Memberships.status eq Status.ACTIVE) and (Memberships.userId eq Users.id)}
                        .map {
                            MemberData(
                                userId = it[Memberships.userId].value,
                                username = it[Users.username],
                                points = it[Memberships.points]
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, ViewMembersResponse(existingMembers))
            }

            post("/join/{inviteCode}") {
                val userId = call.userId()

                val request = call.receive<JoinGroupRequest>()

                val codeError = validateInviteCode(request.inviteCode)
                if (codeError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to codeError))
                    return@post
                }

                val trimmedCode = request.inviteCode.trim().uppercase()

                val group = dbQuery {
                    Groups.select(Groups.id, Groups.groupName, Groups.description, Groups.creatorId).where { Groups.inviteCode eq trimmedCode }.singleOrNull()
                }

                if (group == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Invalid invite code"))
                    return@post
                }

                // Given unique index on (userId, groupId) for Memberships, need to handle exception if already a member
                val membershipId = try {
                    dbQuery {
                        Memberships.insert {
                            it[Memberships.userId] = userId
                            it[Memberships.groupId] = group[Groups.id].value
                        }[Memberships.id].value
                    }
                } catch (e: ExposedSQLException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "You are already a member of this group"))
                    return@post
                }

                call.respond(HttpStatusCode.Created, JoinGroupResponse(
                    membershipId,
                    group[Groups.id].value,
                    group[Groups.groupName],
                    group[Groups.description],
                    group[Groups.creatorId].value,
                ))
            }

            get("/member") {
                val userId = call.userId()

                val groups = dbQuery {
                    (Memberships innerJoin Groups)
                        .select(Memberships.groupId, Groups.groupName, Groups.description, Groups.inviteCode, Groups.creatorId)
                        .where { (Memberships.userId eq userId) and (Memberships.status eq Status.ACTIVE) and (Groups.status eq Status.ACTIVE) }
                        .map {
                            GroupData(
                                groupId = it[Memberships.groupId].value,
                                groupName = it[Groups.groupName],
                                description = it[Groups.description],
                                inviteCode = it[Groups.inviteCode],
                                creatorId = it[Groups.creatorId].value
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, ViewGroupsResponse(groups))
            }

            get("/admin") {
                val userId = call.userId()

                val groups = dbQuery {
                    Groups.select(Groups.id, Groups.groupName, Groups.description, Groups.inviteCode, Groups.creatorId)
                        .where { (Groups.creatorId eq userId) and (Groups.status eq Status.ACTIVE) }
                        .map {
                            GroupData(
                                groupId = it[Groups.id].value,
                                groupName = it[Groups.groupName],
                                description = it[Groups.description],
                                inviteCode = it[Groups.inviteCode],
                                creatorId = it[Groups.creatorId].value
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, ViewGroupsResponse(groups))

            }

            post("/leave/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                val membership = dbQuery {
                    Memberships.select(Memberships.status)
                        .where { (Memberships.userId eq userId) and (Memberships.groupId eq groupId) }
                        .singleOrNull()
                }

                if (membership == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "You are not a member of this group"))
                    return@post
                }

                if (membership[Memberships.status] == Status.INACTIVE) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "You have already left this group"))
                    return@post
                }

                dbQuery {
                    Memberships.update({ (Memberships.userId eq userId) and (Memberships.groupId eq groupId) }) {
                        it[status] = Status.INACTIVE
                }}

                call.respond(HttpStatusCode.OK, mapOf("message" to "Successfully left the group"))
            }

            post("remove/member/{groupId}/{memberId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val memberId = call.parameters["memberId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid member Id"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to edit this group"))
                    return@post
                }

                // Preceding "view members" already ensures only active members in the group are shown

                dbQuery {
                    Memberships.update({ (Memberships.userId eq memberId) and (Memberships.groupId eq groupId) }) {
                        it[status] = Status.INACTIVE
                    }
                }

                call.respond(HttpStatusCode.OK, mapOf("message" to "Successfully removed member from the group"))
            }

            get("member/points/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                val points = dbQuery {
                    Memberships.select(Memberships.points)
                        .where { (Memberships.userId eq userId) and (Memberships.groupId eq groupId) and (Memberships.status eq Status.ACTIVE) }
                        .singleOrNull()
                }

                if (points == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "You are not an active member of this group"))
                    return@get
                }

                call.respond(HttpStatusCode.OK, PointResponse(points[Memberships.points]))
            }
        }
    }
}

fun validateDescription(description: String): String? {
    val trimmedDesc = description.trim()
    return when {
        trimmedDesc.length > 1000 -> "Description cannot exceed 1000 characters"
        trimmedDesc.contains("<script>", ignoreCase = true) -> "Description contains invalid content"
        trimmedDesc.contains("</script>", ignoreCase = true) -> "Description contains invalid content"
        trimmedDesc.contains("onerror=", ignoreCase = true) -> "Description contains invalid content"
        trimmedDesc.contains("javascript:", ignoreCase = true) -> "Description contains invalid content"
        trimmedDesc.contains("onclick=", ignoreCase = true) -> "Description contains invalid content"
        else -> null
    }
}

private suspend fun noGroupFound(groupId: Int) : Boolean {
    val group = dbQuery {
        Groups.select(Groups.id)
            .where { (Groups.id eq groupId) and (Groups.status eq Status.ACTIVE) }
            .singleOrNull()
    }
    return group == null
}

suspend fun isAdminOfGroup(userId: Int, groupId: Int) : Boolean {
    val group = dbQuery {
        Groups.select(Groups.id, Groups.creatorId)
            .where { (Groups.id eq groupId) and (Groups.creatorId eq userId) and (Groups.status eq Status.ACTIVE) }
            .singleOrNull()
    }
    return group != null
}

fun generateInviteCode(): String {
    return UUID.randomUUID().toString()
        .replace("-", "")
        .substring(0, 10) // 10-character, as per database schema
        .uppercase()
}

private fun validateGroupName(name: String): String? {
    val trimmedName = name.trim()
    return when {
        trimmedName.isBlank() -> "Group name cannot be blank"
        trimmedName.length < 3 -> "Group name must be at least 3 characters"
        trimmedName.length > 100 -> "Group name cannot exceed 100 characters"
        else -> null
    }
}

private fun validateTaskPoints(min: Int?, avg: Int?, max: Int?): String? {
    if (min != null && min < 0) return "Minimum points cannot be negative"
    if (avg != null && avg < 0) return "Average points cannot be negative"
    if (max != null && max < 0) return "Maximum points cannot be negative"

    if (min != null && avg != null && min > avg) {
        return "Minimum points cannot be greater than average points"
    }
    if (avg != null && max != null && avg > max) {
        return "Average points cannot be greater than maximum points"
    }
    if (min != null && max != null && min > max) {
        return "Minimum points cannot be greater than maximum points"
    }

    return null
}

private fun validateInviteCode(code: String): String? {
    val trimmedCode = code.trim()
    return when {
        trimmedCode.isBlank() -> "Invite code cannot be blank"
        trimmedCode.length != 10 -> "Invalid invite code format"
        !trimmedCode.all { it.isLetterOrDigit() } -> "Invalid invite code format"
        else -> null
    }
}

