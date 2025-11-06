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
    val creatorId: Int,
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

// TASKS
@Serializable
data class TaskData(
    val taskId: Int,
    val taskName: String,
    val description: String,
    val dueDate: String?, // ISO 8601 for locale independence
    val points: Int,
    val quantity: Int,
    val requireProof: Boolean
)

@Serializable
data class ViewTaskRequest(
    val groupId: Int
)

@Serializable
data class ViewTaskResponse(
    val tasks: List<TaskData>
)

@Serializable
data class CreateTaskRequest(
    val creatorId: Int,
    val taskName: String,
    val description: String,
    val dueDate: String?,
    val points: Int,
    val quantity: Int,
    val requireProof: Boolean
)

@Serializable
data class CreateTaskResponse(
    val task: TaskData
)

@Serializable
data class RemoveTaskRequest(
    val taskId: Int
)

@Serializable
data class ClaimTaskRequest(
    val taskId: Int,
    val claimantId: Int
)

@Serializable
data class UnclaimTaskRequest(
    val claimId: Int
)

@Serializable
data class CreateSubmissionRequest(
    val taskId: Int,
    val claimId: Int,
    val authorId: Int,
    val coAuthorId: Int?,
    val textContent: String?,
    val imageContent: String?
)

@Serializable
data class ViewClaimsRequest(
    val taskId: Int
)

@Serializable
data class ClaimData(
    val claimantName: String,
    // if submissionId and reviewDecision are null, "In Progress"
    val submissionId: Int?, // for subsequent ViewSubmissionRequest
    val reviewDecision: Decision?,
    val reviewTime: String? // ISO 8601 for locale independence
)

@Serializable
data class ViewClaimsResponse(
    val claims: List<ClaimData>
)

@Serializable
data class ViewSubmissionRequest(
    val submissionId: Int
)

@Serializable
data class ViewSubmissionResponse(
    val submissionId: Int,
    val authorId: Int,
    val authorName: String,
    val coAuthorId: Int?,
    val coAuthorName: String?,
    val submittedAt: String, // ISO 8601 for locale independence
    val textContent: String?,
    val imageContent: String?
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
    val creatorId: Int,
    val rewardName: String,
    val description: String,
    val rewardImage: String,
    val startTime: String,
    val endTime: String,
    val minimumBid: Int,
    val bidIncrement: Int
)

@Serializable
data class CreateAuctionResponse(
    val auction: AuctionData
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
