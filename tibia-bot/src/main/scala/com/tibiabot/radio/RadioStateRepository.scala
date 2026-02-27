package com.tibiabot.radio

import com.typesafe.scalalogging.LazyLogging
import com.tibiabot.Config
import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.util.{Try, Success, Failure}

/**
 * RadioStateRepository - Zarządzanie stanem radia w bazie danych
 * Przechowuje informacje o aktywnych sesjach radia dla guild'ów
 */
object RadioStateRepository extends LazyLogging {
  
  private val jdbcUrl = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
  private val dbUser = "postgres"
  private val dbPassword = Config.postgresPassword
  
  /**
   * Tworzy tabelę radio_state jeśli nie istnieje
   */
  def createTableIfNotExists(): Unit = {
    val createTableSQL = """
      CREATE TABLE IF NOT EXISTS radio_state (
        guild_id BIGINT PRIMARY KEY,
        channel_id BIGINT NOT NULL,
        stream_url TEXT NOT NULL,
        enabled BOOLEAN DEFAULT true,
        updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
      )
    """
    
    Try {
      val conn = getConnection()
      try {
        val stmt = conn.createStatement()
        stmt.execute(createTableSQL)
        logger.info("Radio state table created or already exists")
      } finally {
        conn.close()
      }
    } match {
      case Success(_) => 
        logger.info("✅ Radio state table initialized")
      case Failure(e) => 
        logger.error(s"❌ Failed to create radio state table: ${e.getMessage}")
    }
  }
  
  /**
   * Zapisuje stan radia dla guild
   */
  def saveRadioState(guildId: Long, channelId: Long, streamUrl: String): Try[Unit] = {
    val sql = """
      INSERT INTO radio_state (guild_id, channel_id, stream_url, enabled, updated_at)
      VALUES (?, ?, ?, true, CURRENT_TIMESTAMP)
      ON CONFLICT (guild_id) 
      DO UPDATE SET 
        channel_id = EXCLUDED.channel_id,
        stream_url = EXCLUDED.stream_url,
        enabled = true,
        updated_at = CURRENT_TIMESTAMP
    """
    
    Try {
      val conn = getConnection()
      try {
        val stmt = conn.prepareStatement(sql)
        stmt.setLong(1, guildId)
        stmt.setLong(2, channelId)
        stmt.setString(3, streamUrl)
        stmt.executeUpdate()
        logger.info(s"💾 Saved radio state for guild $guildId (channel: $channelId, url: $streamUrl)")
      } finally {
        conn.close()
      }
    }
  }
  
  /**
   * Usuwa stan radia dla guild (gdy użytkownik wyłącza radio)
   */
  def removeRadioState(guildId: Long): Try[Unit] = {
    val sql = "DELETE FROM radio_state WHERE guild_id = ?"
    
    Try {
      val conn = getConnection()
      try {
        val stmt = conn.prepareStatement(sql)
        stmt.setLong(1, guildId)
        stmt.executeUpdate()
        logger.info(s"🗑️ Removed radio state for guild $guildId")
      } finally {
        conn.close()
      }
    }
  }
  
  /**
   * Pobiera wszystkie aktywne sesje radia
   */
  def getAllActiveRadioStates(): List[RadioState] = {
    val sql = "SELECT guild_id, channel_id, stream_url FROM radio_state WHERE enabled = true"
    
    Try {
      val conn = getConnection()
      try {
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(sql)
        
        val states = scala.collection.mutable.ListBuffer.empty[RadioState]
        while (rs.next()) {
          states += RadioState(
            guildId = rs.getLong("guild_id"),
            channelId = rs.getLong("channel_id"),
            streamUrl = rs.getString("stream_url")
          )
        }
        states.toList
      } finally {
        conn.close()
      }
    } match {
      case Success(states) => 
        logger.info(s"📋 Loaded ${states.size} active radio state(s)")
        states
      case Failure(e) => 
        logger.error(s"❌ Failed to load radio states: ${e.getMessage}")
        List.empty
    }
  }
  
  /**
   * Pobiera stan radia dla konkretnego guild
   */
  def getRadioState(guildId: Long): Option[RadioState] = {
    val sql = "SELECT guild_id, channel_id, stream_url FROM radio_state WHERE guild_id = ? AND enabled = true"
    
    Try {
      val conn = getConnection()
      try {
        val stmt = conn.prepareStatement(sql)
        stmt.setLong(1, guildId)
        val rs = stmt.executeQuery()
        
        if (rs.next()) {
          Some(RadioState(
            guildId = rs.getLong("guild_id"),
            channelId = rs.getLong("channel_id"),
            streamUrl = rs.getString("stream_url")
          ))
        } else {
          None
        }
      } finally {
        conn.close()
      }
    } match {
      case Success(state) => state
      case Failure(e) => 
        logger.error(s"❌ Failed to get radio state for guild $guildId: ${e.getMessage}")
        None
    }
  }
  
  private def getConnection(): Connection = {
    DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
  }
}

/**
 * Case class reprezentujący stan radia
 */
case class RadioState(
  guildId: Long,
  channelId: Long,
  streamUrl: String
)
