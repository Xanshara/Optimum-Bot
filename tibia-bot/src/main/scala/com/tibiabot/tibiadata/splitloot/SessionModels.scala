package com.tibiabot.splitloot

import java.time.ZonedDateTime

/**
 * Model danych dla party hunt session
 */
case class SessionInfo(
  startTime: Option[String] = None,
  endTime: Option[String] = None,
  duration: Option[String] = None,
  lootType: Option[String] = None,
  loot: Int = 0,
  supplies: Int = 0,
  partyBalance: Int = 0
)

case class Player(
  name: String,
  loot: Int = 0,
  supplies: Int = 0,
  balance: Int = 0,
  damage: Int = 0,
  healing: Int = 0
)

case class Transfer(
  from: String,
  to: String,
  amount: Int
)

case class SessionData(
  sessionInfo: SessionInfo,
  players: List[Player]
)
