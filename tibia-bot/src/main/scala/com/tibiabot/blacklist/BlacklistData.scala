package com.tibiabot.blacklist

import java.time.ZonedDateTime

/**
 * Model danych dla gracza na blackliscie
 */
case class BlacklistPlayer(
  name: String,
  reason: String,
  addedBy: String,
  isOnline: Boolean,
  lastChecked: ZonedDateTime,
  world: String
)

/**
 * Model konfiguracji blacklist dla gildii
 */
case class BlacklistConfig(
  enabled: Boolean
)

/**
 * Manager przechowywania ID wiadomosci z blacklista dla kazdej gildii i swiata
 */
object BlacklistMessages {
  // guildId -> (world -> messageId)
  private var messages: Map[String, Map[String, String]] = Map.empty
  
  def get(guildId: String, world: String): Option[String] = {
    messages.get(guildId).flatMap(_.get(world))
  }
  
  def set(guildId: String, world: String, messageId: String): Unit = {
    val guildMessages = messages.getOrElse(guildId, Map.empty)
    messages = messages + (guildId -> (guildMessages + (world -> messageId)))
  }
  
  def remove(guildId: String): Unit = {
    messages = messages - guildId
  }
  
  def getAll: Map[String, Map[String, String]] = messages
}