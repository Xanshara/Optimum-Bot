package com.tibiabot.events

import java.sql.Timestamp

/**
 * Model eventu
 */
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
  mentionRoleId: Option[Long] = None,      // NOWE: ID roli do oznaczenia
  reminderMinutes: Int = 15,                // NOWE: ile minut przed przypomnieniem
  reminderSent: Boolean = false,            // NOWE: czy przypomnienie zostało wysłane
  active: Boolean = true,
  createdBy: Long,
  createdAt: Timestamp = new Timestamp(System.currentTimeMillis())
)
