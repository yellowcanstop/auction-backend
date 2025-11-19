package com.example.services

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.AuctionWinners
import com.example.models.Users
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.update

object NotificationService {
    suspend fun notifySubmissionRejected(userId: Int, groupId: Int, submissionId: Int) {
        val fcmToken = dbQuery {
            Users.select(Users.fcmToken)
                .where { (Users.id eq userId) and (Users.fcmToken neq null) }
                .singleOrNull()
                ?.get(Users.fcmToken)
        } ?: return

        // fcm payload is 4kb max
        val message = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                Notification.builder()
                    .setTitle("Submission Graded")
                    .setBody("Unfortunately, your submission was not accepted.")
                    .build()
            )
            .putData("type", "REJECT")
            .putData("groupId", groupId.toString())
            .putData("submissionId", submissionId.toString())
            .build()

        FirebaseMessaging.getInstance().send(message)
    }

    suspend fun notifySubmissionAccepted(userId: Int, groupId: Int, submissionId: Int, pointBalance: Int, awarded: Int) {
        val fcmToken = dbQuery {
            Users.select(Users.fcmToken)
                .where { (Users.id eq userId) and (Users.fcmToken neq null) }
                .singleOrNull()
                ?.get(Users.fcmToken)
        } ?: return

        // fcm payload is 4kb max
        val message = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                Notification.builder()
                    .setTitle("Submission Graded")
                    .setBody("Congratulations, you have earned $awarded points!")
                    .build()
            )
            .putData("type", "ACCEPT")
            .putData("groupId", groupId.toString())
            .putData("submissionId", submissionId.toString())
            .putData("pointBalance", pointBalance.toString())
            .putData("awarded", awarded.toString())
            .build()

        FirebaseMessaging.getInstance().send(message)
    }

    suspend fun notifyAuctionWon(userId: Int, groupId: Int, auctionId: Int, winningBid: Int) {
        val fcmToken = dbQuery {
            Users.select(Users.fcmToken)
                .where { (Users.id eq userId) and (Users.fcmToken neq null) }
                .singleOrNull()
                ?.get(Users.fcmToken)
        } ?: return

        val message = Message.builder()
            .setToken(fcmToken)
            .setNotification(
                Notification.builder()
                    .setTitle("Auction Won")
                    .setBody("Congratulations, your bid of $winningBid points is a winning bid!")
                    .build()
            )
            .putData("type", "WON")
            .putData("groupId", groupId.toString())
            .putData("auctionId", auctionId.toString())
            .putData("winningBid", winningBid.toString())
            .build()

        FirebaseMessaging.getInstance().send(message)

        dbQuery {
            AuctionWinners.update({
                (AuctionWinners.auctionId eq auctionId) and
                (AuctionWinners.winnerId eq userId)
            }) {
                it[notified] = true
            }
        }
    }

    suspend fun notifyOverdueTasks(claimants: List<Int>) {
        val fcmTokens = if (claimants.isNotEmpty()) {
            dbQuery {
                Users.select(Users.fcmToken)
                    .where { (Users.id inList claimants) and (Users.fcmToken neq null) }
                    .toList()
                    .map { it[Users.fcmToken] }
            }
        } else emptyList()

        val message = MulticastMessage.builder()
            .setNotification(
                Notification.builder()
                    .setTitle("Missed Deadline")
                    .setBody("You have overdue tasks that need your attention.")
                    .build()
            )
            .putData("type", "OVERDUE")
            .addAllTokens(fcmTokens)
            .build()

        FirebaseMessaging.getInstance().sendEachForMulticast(message)
    }


}
