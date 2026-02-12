package com.tibiabot.events

import java.sql.Timestamp

/**
 * Przechowuje dane eventu w trakcie tworzenia (multi-step)
 */
case class EventCreationState(
  userId: Long,
  guildId: Long,
  channelId: Long,
  
  // Step 1
  title: Option[String] = None,
  description: Option[String] = None,
  
  // Step 1.5
  mentionRoleId: Option[Long] = None,
  
  // Step 2
  eventTime: Option[Timestamp] = None,
  reminderMinutes: Option[Int] = None,
  
  // Step 3
  tankLimit: Option[Int] = None,
  healerLimit: Option[Int] = None,
  dpsLimit: Option[Int] = None,
  
  // Meta
  currentStep: Int = 1,
  lastInteractionMessageId: Option[Long] = None
) {
  
  def isComplete: Boolean = {
    title.isDefined &&
    eventTime.isDefined &&
    tankLimit.isDefined &&
    healerLimit.isDefined &&
    dpsLimit.isDefined
  }
  
  def toEvent(messageId: Long, createdBy: Long): Event = {
    Event(
      guildId = guildId,
      channelId = channelId,
      messageId = messageId,
      title = title.get,
      description = description,
      eventTime = eventTime.get,
      tankLimit = tankLimit.get,
      healerLimit = healerLimit.get,
      dpsLimit = dpsLimit.get,
      mentionRoleId = mentionRoleId,
      reminderMinutes = reminderMinutes.getOrElse(15),
      createdBy = createdBy
    )
  }
}
