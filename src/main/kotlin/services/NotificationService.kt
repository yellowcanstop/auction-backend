package com.example.services

import com.example.config.DatabaseFactory.dbQuery
import com.example.models.Users
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification

object NotificationService {
    suspend fun notifySubmissionRejected(userId: Int, groupId: Int, submissionId: Int) {
        val fcmToken = dbQuery {
            Users.select(Users.fcmToken)
                .where { Users.id eq userId }
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
                .where { Users.id eq userId }
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

}
