package com.example.routes

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.AuctionData
import com.example.models.Auctions
import com.example.models.BidData
import com.example.models.BidRequest
import com.example.models.BidResponse
import com.example.models.Bids
import com.example.models.CreateAuctionRequest
import com.example.models.CreateAuctionResponse
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

                if (validateDescription(request.description)) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Description invalid"))
                    return@post
                }

                val startTime = if (request.startNow) {
                    LocalDateTime.now()
                } else {
                    request.startTime?.let { LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME) }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Start time required when not starting immediately")
                        )
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
                }

                val auctionList = auctions.map {
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

                call.respond(HttpStatusCode.OK, ViewAuctionsResponse(auctionList))
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
                }

                val bidList = bids.map {
                    BidData(
                        bidderId = it[Bids.bidderId].value,
                        bidderName = it[Users.username],
                        bidAmount = it[Bids.bidAmount],
                        bidAt = it[Bids.bidAt].format(DateTimeFormatter.ISO_DATE_TIME)
                    )
                }

                call.respond(HttpStatusCode.OK, ViewBidsResponse(bidList))
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

                val bidId = try {
                    dbQuery {
                        val auction = Auctions.select(Auctions.id, Auctions.groupId, Auctions.startTime, Auctions.endTime, Auctions.minimumBid, Auctions.bidIncrement, Auctions.status)
                            .where{ (Auctions.id eq auctionId) and (Auctions.groupId eq groupId) and (Auctions.status eq Status.ACTIVE)}
                            .singleOrNull()
                            ?: throw IllegalStateException("Auction not found")

                        if (auction[Auctions.startTime].isAfter(LocalDateTime.now()) || auction[Auctions.endTime].isBefore(LocalDateTime.now())) {
                            throw IllegalStateException("Auction is not currently available for bidding")
                        }

                        val highestBid = Bids.select(Bids.auctionId, Bids.bidAmount)
                            .where { Bids.auctionId eq auctionId }
                            .orderBy(Bids.bidAmount to SortOrder.DESC)
                            .limit(1)
                            .singleOrNull()

                        val minBid = highestBid?.let {
                            highestBid[Bids.bidAmount] + auction[Auctions.bidIncrement]
                        } ?: auction[Auctions.minimumBid]

                        if (request.bidAmount < minBid) {
                            throw IllegalStateException("Bid amount must be at least $minBid")
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
        }
    }
}