package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.AuctionData
import com.example.models.Auctions
import com.example.models.CreateAuctionRequest
import com.example.models.CreateAuctionResponse
import com.example.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.sql.insertAndGetId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun Route.auctionRoutes() {
    authenticate("auth-jwt") {
        route("api/auctions") {
            post("/create/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to create a task"))
                    return@post
                }

                val request = call.receive<CreateAuctionRequest>()

                if (validateDescription(request.description)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Description invalid"))
                    return@post
                }

                val startTime = if (request.startNow) {
                    LocalDateTime.now()
                } else {
                    request.startTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Start time required when not starting immediately"))
                }

                val endTime = LocalDateTime.parse(request.endTime, DateTimeFormatter.ISO_DATE_TIME)

                if (endTime.isBefore(startTime)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "End time must be after start time"))
                    return@post
                }

                // use 1-minute buffer
                if (!request.startNow && startTime.isBefore(LocalDateTime.now().minusMinutes(1))) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Start time cannot be in the past"))
                    return@post
                }

                val auctionId = dbQuery {
                    Auctions.insertAndGetId {
                        it[Auctions.groupId] = groupId
                        it[Auctions.creatorId] = userId
                        it[Auctions.rewardName] = request.rewardName
                        it[Auctions.description] = request.description
                        it[Auctions.rewardImage] = request.rewardImage
                        it[Auctions.startTime] = startTime
                        it[Auctions.endTime] = endTime
                        request.minimumBid?.let { bid -> it[Auctions.minimumBid] = bid }
                        request.bidIncrement?.let { increment -> it[Auctions.bidIncrement] = increment}
                    }
                }

                call.respond(HttpStatusCode.Created, CreateAuctionResponse(auctionId.value))
            }
        }
    }
}