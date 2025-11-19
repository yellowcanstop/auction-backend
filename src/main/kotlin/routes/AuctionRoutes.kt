package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.AuctionData
import com.example.models.AuctionWinnerData
import com.example.models.AuctionWinners
import com.example.models.Auctions
import com.example.models.BidData
import com.example.models.BidRequest
import com.example.models.BidResponse
import com.example.models.Bids
import com.example.models.CreateAuctionRequest
import com.example.models.CreateAuctionResponse
import com.example.models.Memberships
import com.example.models.Status
import com.example.models.Users
import com.example.models.ViewAuctionsResponse
import com.example.models.ViewBidsResponse
import com.example.plugins.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.and
import kotlin.compareTo
import kotlin.text.get

fun Route.auctionRoutes() {
    authenticate("auth-jwt") {
        route("api/auctions") {
            post("/create/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "You do not have permission to create a task")
                    )
                    return@post
                }

                val request = call.receive<CreateAuctionRequest>()

                val nameError = validateRewardName(request.rewardName)
                if (nameError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to nameError))
                    return@post
                }

                val descError = validateDescription(request.description)
                if (descError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to descError))
                    return@post
                }

                val imageError = validateRewardImage(request.rewardImage)
                if (imageError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to imageError))
                    return@post
                }

                val minBidError = validateMinimumBid(request.minimumBid)
                if (minBidError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to minBidError))
                    return@post
                }

                val incrementError = validateBidIncrement(request.bidIncrement)
                if (incrementError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to incrementError))
                    return@post
                }

                val startTime = if (request.startNow) {
                    LocalDateTime.now()
                } else {
                    try {
                        request.startTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Start time required when not starting immediately")
                            )
                    } catch (e: Exception) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Invalid start time format. Use ISO-8601 format (yyyy-MM-dd'T'HH:mm:ss)")
                        )
                    }
                }

                val endTime = try {
                    LocalDateTime.parse(request.endTime, DateTimeFormatter.ISO_DATE_TIME)
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid end time format. Use ISO-8601 format (yyyy-MM-dd'T'HH:mm:ss)")
                    )
                }

                val timeError = validateAuctionTimes(startTime, endTime, request.startNow)
                if (timeError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to timeError))
                    return@post
                }

                val trimmedRewardName = request.rewardName.trim()
                val trimmedDescription = request.description.trim()
                val trimmedRewardImage = request.rewardImage?.trim()

                val auctionId = dbQuery {
                    Auctions.insertAndGetId {
                        it[Auctions.groupId] = groupId
                        it[Auctions.creatorId] = userId
                        it[Auctions.rewardName] = trimmedRewardName
                        it[Auctions.description] = trimmedDescription
                        it[Auctions.rewardImage] = trimmedRewardImage
                        it[Auctions.startTime] = startTime
                        it[Auctions.endTime] = endTime
                        request.minimumBid?.let { bid -> it[Auctions.minimumBid] = bid }
                        request.bidIncrement?.let { increment -> it[Auctions.bidIncrement] = increment }
                    }
                }

                call.respond(HttpStatusCode.Created, CreateAuctionResponse(auctionId.value))
            }

            get("/view/{groupId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId) && notActiveMember(userId, groupId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "You are not permitted to view auctions in this group")
                    )
                    return@get
                }

                val auctions = dbQuery {
                    Auctions.select(
                        Auctions.id,
                        Auctions.rewardName,
                        Auctions.description,
                        Auctions.rewardImage,
                        Auctions.startTime,
                        Auctions.endTime,
                        Auctions.minimumBid,
                        Auctions.bidIncrement
                    )
                        .where { (Auctions.groupId eq groupId) and (Auctions.status eq Status.ACTIVE) }
                        .map {
                            AuctionData(
                                auctionId = it[Auctions.id].value,
                                rewardName = it[Auctions.rewardName],
                                description = it[Auctions.description],
                                rewardImage = it[Auctions.rewardImage],
                                startTime = it[Auctions.startTime].format(DateTimeFormatter.ISO_DATE_TIME),
                                endTime = it[Auctions.endTime].format(DateTimeFormatter.ISO_DATE_TIME),
                                minimumBid = it[Auctions.minimumBid],
                                bidIncrement = it[Auctions.bidIncrement]
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, ViewAuctionsResponse(auctions))
            }

            get("/view/{groupId}/{auctionId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val auctionId = call.parameters["auctionId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid auction ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId) && notActiveMember(userId, groupId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "You are not permitted to view auctions in this group")
                    )
                    return@get
                }

                dbQuery {
                    Auctions.select(Auctions.id, Auctions.startTime, Auctions.endTime, Auctions.status)
                        .where { (Auctions.groupId eq groupId) and (Auctions.id eq auctionId) and (Auctions.status eq Status.ACTIVE) }
                        .singleOrNull()
                } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Auction not found"))

                val bids = dbQuery {
                    (Bids innerJoin Users)
                        .select(Bids.auctionId, Bids.bidderId, Bids.bidAmount, Bids.bidAt, Users.username)
                        .where { (Bids.auctionId eq auctionId) and (Bids.bidderId eq Users.id) }
                        .orderBy(Bids.bidAmount to SortOrder.DESC, Bids.bidAt to SortOrder.DESC)
                        .map {
                            BidData(
                                bidderId = it[Bids.bidderId].value,
                                bidderName = it[Users.username],
                                bidAmount = it[Bids.bidAmount],
                                bidAt = it[Bids.bidAt].format(DateTimeFormatter.ISO_DATE_TIME)
                            )
                        }
                }

                call.respond(HttpStatusCode.OK, ViewBidsResponse(bids))
            }

            post("/delete/{groupId}/{auctionId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val auctionId = call.parameters["auctionId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid auction ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "You do not have permission to delete this auction")
                    )
                    return@post
                }

                val updatedRows = dbQuery {
                    Auctions.update({ (Auctions.id eq auctionId) and (Auctions.status eq Status.ACTIVE) }) {
                        it[status] = Status.INACTIVE
                    }
                }

                if (updatedRows == 0) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No active auction found"))
                } else call.respond(HttpStatusCode.OK, mapOf("message" to "Auction deleted successfully"))
            }

            post("/bid/{groupId}/{auctionId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val auctionId = call.parameters["auctionId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid auction ID"))

                val userId = call.userId()

                if (notActiveMember(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You are not permitted to bid in this group"))
                    return@post
                }

                val request = call.receive<BidRequest>()

                val bidError = validateBidAmount(request.bidAmount)
                if (bidError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to bidError))
                    return@post
                }

                val userPoints = getUserPoints(userId, groupId)
                if (userPoints == null) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Membership not found"))
                    return@post
                }

                if (request.bidAmount > userPoints) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Insufficient points. You have $userPoints points but need ${request.bidAmount} points")
                    )
                    return@post
                }

                val bidId = try {
                    dbQuery {
                        val auction = Auctions.select(Auctions.id, Auctions.groupId, Auctions.startTime, Auctions.endTime, Auctions.minimumBid, Auctions.bidIncrement, Auctions.status)
                            .where{ (Auctions.id eq auctionId) and (Auctions.groupId eq groupId) and (Auctions.status eq Status.ACTIVE)}
                            .singleOrNull()
                            ?: throw IllegalStateException("Auction not found")

                        val now = LocalDateTime.now()
                        if (auction[Auctions.startTime].isAfter(now)) {
                            throw IllegalStateException("Auction has not started yet")
                        }
                        if (auction[Auctions.endTime].isBefore(now)) {
                            throw IllegalStateException("Auction has ended")
                        }

                        val highestBid = Bids.select(Bids.auctionId, Bids.bidAmount, Bids.bidderId)
                            .where { Bids.auctionId eq auctionId }
                            .orderBy(Bids.bidAmount to SortOrder.DESC)
                            .limit(1)
                            .singleOrNull()

                        if (highestBid != null && highestBid[Bids.bidderId].value == userId) {
                            throw IllegalStateException("You are already the highest bidder")
                        }

                        val minBid = highestBid?.let {
                            highestBid[Bids.bidAmount] + auction[Auctions.bidIncrement]
                        } ?: auction[Auctions.minimumBid]

                        if (request.bidAmount < minBid) {
                            throw IllegalStateException("Bid amount must be at least $minBid points")
                        }

                        val bid = try {
                            Bids.insertAndGetId {
                                it[Bids.auctionId] = auctionId
                                it[Bids.bidderId] = userId
                                it[Bids.bidAmount] = request.bidAmount
                            }
                        } catch (e: ExposedSQLException) {
                            // unique index constraint to prevent duplicate bids
                            throw IllegalStateException("Another bid with this amount was just placed. Please retry with a higher amount.")
                        }

                        bid.value
                    }
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
                    return@post
                }

                call.respond(HttpStatusCode.OK, BidResponse(bidId))
            }

            // TODO not yet consumed by android app
            get("/winner/{groupId}/{auctionId}") {
                val groupId = call.parameters["groupId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid group ID"))

                val auctionId = call.parameters["auctionId"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid auction ID"))

                val userId = call.userId()

                if (!isAdminOfGroup(userId, groupId) && notActiveMember(userId, groupId)) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Not permitted"))
                    return@get
                }

                val winner = dbQuery {
                    (AuctionWinners innerJoin Users)
                        .select(
                            AuctionWinners.auctionId,
                            AuctionWinners.winnerId,
                            AuctionWinners.winningBid,
                            AuctionWinners.finalizedAt,
                            Users.username
                        )
                        .where { AuctionWinners.auctionId eq auctionId }
                        .singleOrNull()
                }

                if (winner == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "No winner found"))
                } else {
                    call.respond(HttpStatusCode.OK, AuctionWinnerData(
                        auctionId = winner[AuctionWinners.auctionId].value,
                        winnerId = winner[AuctionWinners.winnerId].value,
                        winnerName = winner[Users.username],
                        winningBid = winner[AuctionWinners.winningBid],
                        finalizedAt = winner[AuctionWinners.finalizedAt].format(DateTimeFormatter.ISO_DATE_TIME)
                    )
                    )
                }
            }

        }
    }
}

private suspend fun getUserPoints(userId: Int, groupId: Int): Int? {
    return dbQuery {
        Memberships.select(Memberships.points)
            .where { (Memberships.userId eq userId) and (Memberships.groupId eq groupId) and (Memberships.status eq Status.ACTIVE) }
            .singleOrNull()
            ?.get(Memberships.points)
    }
}

private fun validateRewardName(name: String): String? {
    val trimmedName = name.trim()
    return when {
        trimmedName.isBlank() -> "Reward name cannot be blank"
        trimmedName.length < 3 -> "Reward name must be at least 3 characters"
        trimmedName.length > 100 -> "Reward name cannot exceed 100 characters"
        else -> null
    }
}

private fun validateRewardImage(imageUrl: String?): String? {
    if (imageUrl == null) return null

    val trimmed = imageUrl.trim()
    return when {
        trimmed.isBlank() -> "Image URL cannot be blank if provided"
        trimmed.length > 500 -> "Image URL cannot exceed 500 characters"
        !trimmed.startsWith("http://") && !trimmed.startsWith("https://") ->
            "Image URL must start with http:// or https://"
        else -> null
    }
}

private fun validateAuctionTimes(startTime: LocalDateTime, endTime: LocalDateTime, startNow: Boolean): String? {
    val now = LocalDateTime.now()
    val bufferMinutes = 1L

    return when {
        !startNow && startTime.isBefore(now.minusMinutes(bufferMinutes)) ->
            "Start time cannot be in the past"
        endTime.isBefore(startTime) ->
            "End time must be after start time"
        endTime.isBefore(now.minusMinutes(bufferMinutes)) ->
            "End time cannot be in the past"
        endTime.isAfter(now.plusMonths(1)) ->
            "End time cannot be more than 1 month in the future"
        endTime.isBefore(startTime.plusMinutes(5)) ->
            "Auction must run for at least 5 minutes"
        else -> null
    }
}

private fun validateMinimumBid(minimumBid: Int?): String? {
    if (minimumBid == null) return null

    return when {
        minimumBid < 0 -> "Minimum bid cannot be negative"
        minimumBid > 100000 -> "Minimum bid cannot exceed 100,000 points"
        else -> null
    }
}

private fun validateBidIncrement(bidIncrement: Int?): String? {
    if (bidIncrement == null) return null

    return when {
        bidIncrement < 1 -> "Bid increment must be at least 1"
        bidIncrement > 10000 -> "Bid increment cannot exceed 10,000 points"
        else -> null
    }
}

private fun validateBidAmount(bidAmount: Int): String? {
    return when {
        bidAmount < 0 -> "Bid amount cannot be negative"
        bidAmount > 1000000 -> "Bid amount cannot exceed 1,000,000 points"
        else -> null
    }
}
