package com.example.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.`java-time`.CurrentDateTime
import org.jetbrains.exposed.sql.`java-time`.datetime


object Groups : Table("groups") {
    val id = integer("id").autoIncrement()
    val groupName = varchar("name", 50).uniqueIndex()
    val description = varchar("description", 255).nullable()
    val createdBy = integer("created_by").references(Users.id)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime())
    override val primaryKey = PrimaryKey(id)
}

object Users : Table("users") {
    val id = integer("id").autoIncrement()
    val username = varchar("username", 50).uniqueIndex()
    val email = varchar("email", 100).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime())
    val points = integer("points").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Memberships : Table("memberships") {
    val userId = integer("user_id").references(Users.id)
    val groupId = integer("group_id").references(Groups.id)
    val joinedAt = datetime("joined_at").defaultExpression(CurrentDateTime())
    override val primaryKey = PrimaryKey(userId, groupId)
}

object Tasks : Table("tasks") {
    val id = integer("id").autoIncrement()
    val groupId = integer("group_id").references(Groups.id)
    val title = varchar("title", 100)
    val description = varchar("description", 1000).nullable()
    val assignedTo = integer("assigned_to").references(Users.id).default(-1) // -1 means available to all
    val dueDate = datetime("due_date").nullable()
    val submissionId = integer("submission_id").references(Submissions.id).default(-1) // -1 means no verified submission yet
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime())
    val points = integer("points").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Submissions : Table("submissions") {
    val id = integer("id").autoIncrement()
    val taskId = integer("task_id").references(Tasks.id)
    val submittedBy = integer("submitted_by").references(Users.id)
    val submissionTime = datetime("submission_time").defaultExpression(CurrentDateTime())
    val status = varchar("status", 20).default("pending") // pending, approved, rejected
    val reviewType = varchar("review_type", 20).default("single") // single, peer
    val requiredReviews = integer("required_reviews").default(1) // 1 for single, 2 for peer
    val completedReviews = integer("completed_reviews").default(0)
    override val primaryKey = PrimaryKey(id)
}

object Reviews : Table("reviews") {
    val id = integer("id").autoIncrement()
    val submissionId = integer("submission_id").references(Submissions.id)
    val reviewerId = integer("reviewer_id").references(Users.id)
    val decision = varchar("decision", 20) // approved, rejected
    val reviewTime = datetime("review_time").defaultExpression(CurrentDateTime())
    val comments = varchar("comments", 1000).nullable()
    override val primaryKey = PrimaryKey(id)
}

object Auctions : Table("auctions") {
    val id = integer("id").autoIncrement()
    val groupId = integer("group_id").references(Groups.id)
    val itemName = varchar("item_name", 255)
    val itemDescription = varchar("item_description", 1000)
    val itemPhoto = varchar("item_photo", 255).nullable()
    val startingBid = decimal("starting_bid", 10, 2)
    val bidIncrement = decimal("bid_increment", 10, 2)
    val currentBid = decimal("current_bid", 10, 2).nullable()
    val currentBidderId = integer("current_bidder_id").references(Users.id).nullable()
    val endTime = datetime("end_time")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime())
    val status = varchar("status", 20).default("active") // active, ended, cancelled
    val repeat = bool("repeat").default(false)
    override val primaryKey = PrimaryKey(id)
}

object Bids : Table("bids") {
    val id = integer("id").autoIncrement()
    val auctionId = integer("auction_id").references(Auctions.id)
    val bidderId = integer("bidder_id").references(Users.id)
    val amount = decimal("amount", 10, 2)
    val bidTime = datetime("bid_time").defaultExpression(CurrentDateTime())
    override val primaryKey = PrimaryKey(id)
}

fun initDatabase(url: String, driver: String, user: String, password: String) {
    Database.connect(url = url, driver = driver, user = user, password = password)
    transaction {
        SchemaUtils.create(Users, Groups, Memberships, Tasks, Submissions, Reviews, Auctions, Bids)
    }
}