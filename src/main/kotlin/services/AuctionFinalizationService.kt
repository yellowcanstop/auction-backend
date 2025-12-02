package com.example.services

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.*
import com.example.services.NotificationService.notifyAuctionWon
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import java.time.LocalDateTime
import java.time.ZoneId

// background job to get ended auctions every 60 seconds
// there could be better ways but that is for future me
object AuctionFinalizationService {
    private var job: Job? = null

    fun start(scope: CoroutineScope, checkIntervalSeconds: Long = 60) {
        job = scope.launch {
            while (isActive) {
                try {
                    finalizeEndedAuctions()
                } catch (e: Exception) {
                    println("Error finalizing auctions: ${e.message}")
                }
                delay(checkIntervalSeconds * 1000)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun finalizeEndedAuctions() {
        val now = LocalDateTime.now(ZoneId.of("UTC"))

        val endedAuctions = dbQuery {
            Auctions.select(Auctions.id, Auctions.groupId, Auctions.status, Auctions.endTime)
                .where {
                    (Auctions.status eq Status.ACTIVE) and (Auctions.endTime lessEq now)
                }
                .map { it[Auctions.id].value to it[Auctions.groupId].value } // pair up
        }

        endedAuctions.forEach { (auctionId, groupId) ->
            finalizeAuction(auctionId, groupId)
        }
    }

    private suspend fun finalizeAuction(auctionId: Int, groupId: Int) {
        dbQuery {
            val alreadyWon = AuctionWinners.select(AuctionWinners.auctionId)
                .where { AuctionWinners.auctionId eq auctionId }
                .singleOrNull()

            if (alreadyWon != null) return@dbQuery

            val bids = Bids
                .join(Users, JoinType.INNER, onColumn = Bids.bidderId, otherColumn = Users.id)
                .join(Memberships, JoinType.INNER, onColumn = Memberships.userId, otherColumn = Users.id)
                .select(
                    Bids.bidderId,
                    Bids.bidAmount,
                    Memberships.points,
                    Users.username
                )
                .where {
                    (Bids.auctionId eq auctionId) and
                            (Bids.bidderId eq Memberships.userId) and
                            (Memberships.groupId eq groupId) and
                            (Memberships.status eq Status.ACTIVE) and
                            (Bids.bidderId eq Users.id)
                }
                .orderBy(Bids.bidAmount to SortOrder.DESC, Bids.bidAt to SortOrder.ASC)
                .toList()

            /*
            val bids = (Bids innerJoin Memberships innerJoin Users)
                .select(
                    Bids.bidderId,
                    Bids.bidAmount,
                    Memberships.points,
                    Users.username
                )
                .where {
                    (Bids.auctionId eq auctionId) and
                            (Bids.bidderId eq Memberships.userId) and
                            (Memberships.groupId eq groupId) and
                            (Memberships.status eq Status.ACTIVE) and
                            (Bids.bidderId eq Users.id)
                }
                .orderBy(Bids.bidAmount to SortOrder.DESC, Bids.bidAt to SortOrder.ASC)
                .toList()
                */

            // move to next highest if top bidder does not have enough points
            val winner = bids.firstOrNull { bid ->
                bid[Memberships.points] >= bid[Bids.bidAmount]
            }

            if (winner != null) {
                val winnerId = winner[Bids.bidderId].value
                val winningBid = winner[Bids.bidAmount]

                Memberships.update({
                    (Memberships.userId eq winnerId) and (Memberships.groupId eq groupId)
                }) {
                    it[points] = points - winningBid
                }

                val winnerRecordId = AuctionWinners.insertAndGetId {
                    it[AuctionWinners.auctionId] = auctionId
                    it[AuctionWinners.winnerId] = winnerId
                    it[AuctionWinners.winningBid] = winningBid
                    it[AuctionWinners.notified] = false
                }
                // TODO to remove print statements
                println("Auction $auctionId won by ${winner[Users.username]} with bid $winningBid")

                notifyAuctionWon(winnerId, groupId, auctionId, winningBid)
            } else {
                println("Auction $auctionId ended with no valid winner (insufficient points)")
            }

            Auctions.update({ Auctions.id eq auctionId }) {
                it[status] = Status.INACTIVE
            }

        }
    }
}
