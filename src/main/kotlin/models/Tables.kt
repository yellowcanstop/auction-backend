package com.example.models

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime
import java.time.ZoneId

enum class Status { ACTIVE, INACTIVE }
enum class Decision { ACCEPT, REJECT }

object Users : IntIdTable("users") {
    val username = varchar("username", 255).uniqueIndex()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)
    val fcmToken = varchar("fcm_token", 4096).nullable()
    // fcm token length seems to be currently 163 chars, but this format may change in the future
    // we allow 4096 (max size of a cookie) just to be safe
}

object Groups : IntIdTable("groups") {
    val groupName = varchar("group_name", 255)
    val description = text("description")
    val inviteCode = varchar("invite_code", 10).uniqueIndex()
    val creatorId = reference("creator_id", Users)
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val autoApprove = bool("auto_approve").default(false)
    val taskPointsMin = integer("task_points_min").nullable()
    val taskPointsAverage = integer("task_points_average").nullable()
    val taskPointsMax = integer("task_points_max").nullable()
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)

    init {
        index(false, inviteCode, id, status) // speed up invite code lookups
    }
}

object Memberships : IntIdTable("memberships") {
    val userId = reference("user_id", Users)
    val groupId = reference("group_id", Groups)
    val joinedAt = datetime("joined_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val points = integer("points").default(0)
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)

    init {
        uniqueIndex(userId, groupId)
    }
}

object Tasks : IntIdTable("tasks") {
    val groupId = reference("group_id", Groups)
    val creatorId = reference("creator_id", Users)
    val taskName = varchar("task_name", 255)
    val description = text("description")
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val dueDate = datetime("due_date").nullable()
    val points = integer("points")
    val quantity = integer("quantity").default(1)
    val requireProof = bool("require_proof").default(true)
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)
}

object Claims : IntIdTable("claims") {
    val taskId = reference("task_id", Tasks)
    val claimantId = reference("claimant_id", Users)
    val claimedAt = datetime("claimed_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val releasedAt = datetime("released_at").nullable()
}

object Submissions : IntIdTable("submissions") {
    val taskId = reference("task_id", Tasks)
    val claimId = reference("claim_id", Claims)
    val authorId = reference("author_id", Users)
    val coAuthorId = reference("co_author_id", Users).nullable()
    val submittedAt = datetime("submitted_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val textContent = text("text_content").nullable()
    val imageContent = varchar("image_content", 255).nullable()
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)
}

object Reviews : IntIdTable("reviews") {
    val claimId = reference("claim_id", Claims)
    val submissionId = reference("submission_id", Submissions)
    val reviewerId = reference("reviewer_id", Users)
    val reviewedAt = datetime("reviewed_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val decision = enumeration("decision", Decision::class)
}

object Auctions : IntIdTable("auctions") {
    val groupId = reference("group_id", Groups)
    val creatorId = reference("creator_id", Users)
    val rewardName = varchar("reward_name", 255)
    val description = text("description")
    val rewardImage = varchar("reward_image", 255).nullable()
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val startTime = datetime("start_time")
    val endTime = datetime( "end_time")
    val minimumBid = integer("minimum_bid").default(0)
    val bidIncrement = integer("bid_increment").default(1)
    val status = enumeration("status", Status::class ).default(Status.ACTIVE)
}

object Bids : IntIdTable("bids") {
    val auctionId = reference("auction_id", Auctions)
    val bidderId = reference("bidder_id", Users)
    val bidAmount = integer("bid_amount")
    val bidAt = datetime("bid_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }

    init {
        uniqueIndex(auctionId, bidAmount) // prevent duplicate bids
        index(false, auctionId, bidAmount) // speed up highest bid lookup
        index(false, bidderId, bidAt) // speed up user bid history lookup
    }
}

object AuctionWinners : IntIdTable("auction_winners") {
    val auctionId = reference("auction_id", Auctions).uniqueIndex()
    val winnerId = reference("winner_id", Users)
    val winningBid = integer("winning_bid")
    val finalizedAt = datetime("finalized_at").clientDefault { LocalDateTime.now(ZoneId.of("UTC")) }
    val notified = bool("notified").default(false)
}
