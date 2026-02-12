package com.tibiabot.poll

import java.time.ZonedDateTime

/**
 * Model reprezentujący ankietę
 */
case class Poll(
  pollId: String,
  guildId: String,
  channelId: String,
  messageId: String,
  question: String,
  options: List[String],
  allowMultiple: Boolean,
  createdBy: String,
  createdAt: ZonedDateTime,
  endsAt: ZonedDateTime,
  isActive: Boolean
)

/**
 * Model reprezentujący głos w ankiecie
 */
case class PollVote(
  pollId: String,
  userId: String,
  optionIndices: List[Int],
  votedAt: ZonedDateTime
)

/**
 * Model reprezentujący wyniki ankiety
 */
case class PollResult(
  optionIndex: Int,
  optionText: String,
  voteCount: Int,
  percentage: Double
)

/**
 * Tymczasowe dane podczas tworzenia ankiety
 */
case class PollCreationState(
  userId: String,
  guildId: String,
  channelId: String,
  pollType: Option[String], // "single" lub "multiple"
  optionCount: Option[Int], // 2-10
  question: Option[String] = None, // Dla drugiego/trzeciego modala
  firstFiveOptions: Option[List[String]] = None, // Opcje 1-4 z pierwszego modala
  secondFourOptions: Option[List[String]] = None, // Opcje 5-8 z drugiego modala (dla 9-10)
  duration: Option[Int] = None, // Czas z drugiego modala (dla 9-10)
  timestamp: ZonedDateTime
)
