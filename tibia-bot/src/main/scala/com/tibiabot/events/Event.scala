package com.tibiabot.events

import java.sql.Timestamp

case class Event(
  id: Int = 0,
  guildId: Long,
  channelId: Long,
  messageId: Long,
  title: String,
  description: Option[String],
  eventTime: Timestamp,
  tankLimit: Int,
  healerLimit: Int,
  dpsLimit: Int,
  mentionRoleId: Option[Long] = None,
  reminderMinutes: Int = 15,
  reminderSent: Boolean = false,
  active: Boolean = true,
  createdBy: Long,
  createdAt: Timestamp = new Timestamp(System.currentTimeMillis()),
  // Cykliczne eventy
  isRecurring: Boolean = false,
  recurringIntervalDays: Option[Int] = None,
  nextEventCreated: Boolean = false,
  fixedReminderSent: Boolean = false
)
