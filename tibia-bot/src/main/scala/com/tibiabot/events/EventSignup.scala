package com.tibiabot.events

import java.sql.Timestamp

/**
 * Model reprezentujący zapis użytkownika na event
 */
case class EventSignup(
  eventId: Int,
  userId: Long,
  role: EventRole,
  joinedAt: Timestamp
)

/**
 * Role w evencie
 */
sealed trait EventRole {
  def emoji: String
  def name: String
}

object EventRole {
  case object Tank extends EventRole {
    override def emoji: String = "🛡"
    override def name: String = "Tank"
  }
  
  case object Healer extends EventRole {
    override def emoji: String = "💚"
    override def name: String = "Healer"
  }
  
  case object DPS extends EventRole {
    override def emoji: String = "⚔"
    override def name: String = "Damage"
  }
  
  case object Waitlist extends EventRole {
    override def emoji: String = "⏳"
    override def name: String = "Waitlist"
  }
  
  def fromString(str: String): Option[EventRole] = str.toLowerCase match {
    case "tank" => Some(Tank)
    case "healer" => Some(Healer)
    case "dps" => Some(DPS)
    case "waitlist" => Some(Waitlist)
    case _ => None
  }
  
  def toString(role: EventRole): String = role match {
    case Tank => "tank"
    case Healer => "healer"
    case DPS => "dps"
    case Waitlist => "waitlist"
  }
}
