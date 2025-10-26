package com.example.database

import com.example.models.Auctions
import com.example.models.Bids
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDateTime
//
//sealed class BidResult {
//    data class Success(val bidId: Int) : BidResult()
//    data class Failure(val error: String) : BidResult()
//}
//
//class BidRepository {
//
//    fun placeBid(auctionId: Int, userId: Int, amount: BigDecimal): BidResult {
//        return transaction {
//            val auction = Auctions.select(Auctions.id, Auctions.groupId, Auctions.startingBid, Auctions.bidIncrement, Auctions.endTime, Auctions.isCancelled).where { Auctions.id eq auctionId }
//                .forUpdate() // row-level lock
//                .singleOrNull()
//                ?: return@transaction BidResult.Failure("Auction not found")
//
//            if (LocalDateTime.now().isAfter(auction[Auctions.endTime]) || auction[Auctions.isCancelled]) {
//                return@transaction BidResult.Failure("Auction is no longer open for bidding")
//            }
//
//
//
//            val highestBid = Bids.select(Bids.auctionId). where { Bids.auctionId eq auctionId }
//                .orderBy(Bids.amount, SortOrder.DESC) // for correctness guarantee
//                .limit(1)
//                .singleOrNull()
//
//
//
//
//
//            val minBidAmount = highestBid?.get(Bids.amount)?.plus(BigDecimal("0.01")) ?: auction[Auctions.startingPrice]
//            if (amount < minBidAmount) {
//                return@transaction BidResult.Failure("Bid amount must be at least $minBidAmount")
//            }
//
//            // Insert new bid
//            Bids.insert {
//                it[Bids.auctionId] = auctionId
//                it[Bids.userId] = userId
//                it[Bids.amount] = amount
//                it[Bids.bidTime] = org.jetbrains.exposed.sql.javatime.CurrentDateTime()
//            }
//
//            BidResult.Success("Bid placed successfully")
//        }
//    }
//}