package com.example.models

import kotlinx.serialization.Serializable

// Define JSON structure for API requests/responses
// AUTH
@Serializable
data class UserData(
    val id: Int,
    val email: String,
    val username: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val token: String,
    val user: UserData
)

// GROUPS
@Serializable
data class MemberData(
    val userId: Int,
    val username: String,
    val points: Int
)

@Serializable
data class CreateGroupRequest(
    val groupName: String,
    val description: String
)

@Serializable
data class CreateGroupResponse(
    val groupId: Int,
    val groupName: String,
    val description: String,
    val inviteCode: String,
    val creatorId: Int
)

@Serializable
data class EditGroupRequest(
    val groupName: String? = null,
    val description: String? = null,
    val autoApprove: Boolean? = null,
    val taskPointsMin: Int? = null,
    val taskPointsAverage: Int? = null,
    val taskPointsMax: Int? = null,
    val status: Status? = null
)

@Serializable
data class EditGroupResponse(
    val groupName: String? = null,
    val description: String? = null,
    val autoApprove: Boolean? = null,
    val taskPointsMin: Int? = null,
    val taskPointsAverage: Int? = null,
    val taskPointsMax: Int? = null,
    val status: Status? = null
)

@Serializable
data class ViewMembersRequest(
    val groupId: Int
)

@Serializable
data class ViewMembersResponse(
    val members: List<MemberData>
)

@Serializable
data class JoinGroupRequest(
    val userId: Int,
    val inviteCode: String
)

@Serializable
data class JoinGroupResponse(
    val membershipId: Int,
    val groupId: Int,
    val groupName: String,
    val description: String,
    val creatorId: Int
)

@Serializable
data class GroupData(
    val groupId: Int,
    val groupName: String,
    val description: String
)

@Serializable
data class ViewGroupsResponse(
    val groups: List<GroupData>
)

// TASKS
@Serializable
data class TaskData(
    val taskId: Int,
    val taskName: String,
    val description: String,
    val dueDate: String? = null, // ISO 8601 for locale independence
    val points: Int,
    val quantity: Int,
    val requireProof: Boolean
)

@Serializable
data class ViewTaskResponse(
    val tasks: List<TaskData>
)

enum class Difficulty {
    EASY,
    AVERAGE,
    HARD
}

@Serializable
data class CreateTaskRequest(
    val taskName: String,
    val description: String,
    val dueDate: String? = null,
    val points: Int? = null,
    val difficulty: Difficulty? = null,
    val quantity: Int,
    val requireProof: Boolean
)

@Serializable
data class CreateTaskResponse(
    val task: TaskData
)

@Serializable
data class ClaimResponse(
    val claimId: Int
)

@Serializable
data class CreateSubmissionRequest(
    val coAuthorId: Int? = null,
    val textContent: String? = null,
    val imageContent: String? = null
)

@Serializable
data class ClaimData(
    val claimId: Int,
    val claimantId: Int,
    val claimantName: String,
    // if submission and review are empty, "In Progress"
    val submission: SubmissionData?,
    val review: ReviewData?
)

@Serializable
data class ViewClaimsResponse(
    val claims: List<ClaimData>
)


@Serializable
data class SubmissionData(
    val submissionId: Int,
    val authorId: Int, // same as ClaimantId
    val authorName: String, // same as ClaimantName
    val coAuthorId: Int? = null,
    val coAuthorName: String? = null,
    val submittedAt: String, // ISO 8601 for locale independence
    val textContent: String? = null,
    val imageContent: String? = null
)

@Serializable
data class ReviewData(
    val reviewId: Int,
    val reviewedAt: String, // ISO 8601 for locale independence
    val decision: Decision
)

@Serializable
data class GradeSubmissionRequest(
    val submissionId: Int,
    val reviewerId: Int,
    val decision: Decision
)

// AUCTIONS
@Serializable
data class AuctionData(
    val auctionId: Int,
    val rewardName: String,
    val description: String,
    val rewardImage: String,
    val startTime: String,
    val endTime: String,
    val minimumBid: Int,
    val bidIncrement: Int
)

@Serializable
data class CreateAuctionRequest(
    val rewardName: String,
    val description: String,
    val rewardImage: String? = null,
    val startTime: String? = null,
    val startNow: Boolean = false,
    val endTime: String,
    val minimumBid: Int? = null,
    val bidIncrement: Int? = null
)

@Serializable
data class CreateAuctionResponse(
    val auctionId: Int
)

@Serializable
data class ViewAuctionsRequest(
    val groupId: Int
)

@Serializable
data class ViewAuctionsResponse(
    val auctions: List<AuctionData>
)

@Serializable
data class ViewBidsRequest(
    val auctionId: Int
)

@Serializable
data class ViewBidsResponse(
    val bids: List<BidData>
)

@Serializable
data class BidData(
    val auctionId: Int,
    val bidderId: Int,
    val bidderName: String,
    val bidAmount: Int,
    val bidAt: String
)

@Serializable
data class BidRequest(
    val auctionId: Int,
    val bidderId: Int,
    val bidAmount: Int
)

@Serializable
data class BidResponse(
    val bids: List<BidData>
)
