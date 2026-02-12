package com.tibiabot.reactionrole

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.{Guild, Role, Message}
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.emoji.Emoji

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.util.{Try, Success, Failure}

/**
 * Reprezentuje konfigurację Reaction Role
 */
case class ReactionRoleConfig(
  guildId: String,
  channelId: String,
  messageId: String,
  emoji: String,           // Unicode emoji lub custom emoji format: "name:id"
  roleId: String,
  mode: String = "normal", // normal, unique, verify
  description: String = ""
)

/**
 * Manager zarządzający systemem Reaction Roles
 */
class ReactionRoleManager(jda: JDA) extends StrictLogging {

  /**
   * Tworzy tabelę reaction_roles jeśli nie istnieje
   */
  def initializeTable(guildId: String): Unit = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val username = "postgres"
    val password = Config.postgresPassword
    
    Try {
      val conn = DriverManager.getConnection(url, username, password)
      try {
        val statement = conn.createStatement()
        statement.execute(
          """CREATE TABLE IF NOT EXISTS reaction_roles (
            |  guild_id VARCHAR(100) NOT NULL,
            |  channel_id VARCHAR(100) NOT NULL,
            |  message_id VARCHAR(100) NOT NULL,
            |  emoji VARCHAR(255) NOT NULL,
            |  role_id VARCHAR(100) NOT NULL,
            |  mode VARCHAR(50) DEFAULT 'normal',
            |  description TEXT,
            |  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            |  PRIMARY KEY (guild_id, message_id, emoji)
            |)""".stripMargin
        )
        statement.close()
        logger.info(s"Reaction roles table initialized for guild $guildId")
      } finally {
        conn.close()
      }
    } match {
      case Success(_) => ()
      case Failure(ex) => logger.error(s"Failed to initialize reaction roles table for guild $guildId", ex)
    }
  }

  /**
   * Dodaje nową konfigurację reaction role
   */
  def addReactionRole(config: ReactionRoleConfig): Try[Unit] = Try {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_${config.guildId}"
    val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
    
    try {
      val stmt = conn.prepareStatement(
        """INSERT INTO reaction_roles (guild_id, channel_id, message_id, emoji, role_id, mode, description)
          |VALUES (?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (guild_id, message_id, emoji) 
          |DO UPDATE SET role_id = EXCLUDED.role_id, mode = EXCLUDED.mode, description = EXCLUDED.description
          |""".stripMargin
      )
      stmt.setString(1, config.guildId)
      stmt.setString(2, config.channelId)
      stmt.setString(3, config.messageId)
      stmt.setString(4, config.emoji)
      stmt.setString(5, config.roleId)
      stmt.setString(6, config.mode)
      stmt.setString(7, config.description)
      
      stmt.executeUpdate()
      stmt.close()
      logger.info(s"Added reaction role: ${config.emoji} -> ${config.roleId} in message ${config.messageId}")
    } finally {
      conn.close()
    }
  }

  /**
   * Rejestruje nową wiadomość (bez ról) w bazie - placeholder
   * Używane po Create Message żeby wiadomość pojawiła się w Add Role dropdown
   */
  def registerMessage(guildId: String, channelId: String, messageId: String, title: String = ""): Try[Unit] = Try {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
    
    try {
      // Wstaw placeholder rekord
      val stmt = conn.prepareStatement(
        """INSERT INTO reaction_roles (guild_id, channel_id, message_id, emoji, role_id, mode, description)
          |VALUES (?, ?, ?, ?, ?, ?, ?)
          |ON CONFLICT (guild_id, message_id, emoji) DO NOTHING
          |""".stripMargin
      )
      stmt.setString(1, guildId)
      stmt.setString(2, channelId)
      stmt.setString(3, messageId)
      stmt.setString(4, "_PLACEHOLDER_")  // Specjalna wartość
      stmt.setString(5, "0")
      stmt.setString(6, "normal")
      stmt.setString(7, title)
      
      stmt.executeUpdate()
      stmt.close()
      logger.info(s"Registered message $messageId in database (placeholder)")
    } finally {
      conn.close()
    }
  }

  /**
   * Usuwa konfigurację reaction role dla danego emoji
   */
  def removeReactionRole(guildId: String, messageId: String, emoji: String): Try[Boolean] = Try {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
    
    try {
      val stmt = conn.prepareStatement(
        "DELETE FROM reaction_roles WHERE guild_id = ? AND message_id = ? AND emoji = ?"
      )
      stmt.setString(1, guildId)
      stmt.setString(2, messageId)
      stmt.setString(3, emoji)
      
      val deleted = stmt.executeUpdate() > 0
      stmt.close()
      
      if (deleted) {
        logger.info(s"Removed reaction role: $emoji from message $messageId")
      }
      deleted
    } finally {
      conn.close()
    }
  }

  /**
   * Usuwa wszystkie konfiguracje dla danej wiadomości
   */
  def removeAllReactionRolesForMessage(guildId: String, messageId: String): Try[Int] = Try {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
    
    try {
      val stmt = conn.prepareStatement(
        "DELETE FROM reaction_roles WHERE guild_id = ? AND message_id = ?"
      )
      stmt.setString(1, guildId)
      stmt.setString(2, messageId)
      
      val deleted = stmt.executeUpdate()
      stmt.close()
      
      logger.info(s"Removed $deleted reaction roles from message $messageId")
      deleted
    } finally {
      conn.close()
    }
  }

  /**
   * Pobiera wszystkie konfiguracje dla danej wiadomości
   */
  def getReactionRolesForMessage(guildId: String, messageId: String): List[ReactionRoleConfig] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val configs = ListBuffer[ReactionRoleConfig]()
    
    Try {
      val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
      try {
        val stmt = conn.prepareStatement(
          "SELECT * FROM reaction_roles WHERE guild_id = ? AND message_id = ?"
        )
        stmt.setString(1, guildId)
        stmt.setString(2, messageId)
        
        val rs = stmt.executeQuery()
        while (rs.next()) {
          configs += ReactionRoleConfig(
            guildId = rs.getString("guild_id"),
            channelId = rs.getString("channel_id"),
            messageId = rs.getString("message_id"),
            emoji = rs.getString("emoji"),
            roleId = rs.getString("role_id"),
            mode = rs.getString("mode"),
            description = rs.getString("description")
          )
        }
        
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    } match {
      case Success(_) => ()
      case Failure(ex) => 
        logger.error(s"Failed to get reaction roles for message $messageId", ex)
    }
    
    configs.toList
  }

  /**
   * Pobiera wszystkie konfiguracje dla danego serwera
   */
  /**
   * Pobiera wszystkie reaction roles dla guild (FILTRUJE placeholdery)
   */
  def getAllReactionRoles(guildId: String): List[ReactionRoleConfig] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val configs = ListBuffer[ReactionRoleConfig]()
    
    Try {
      val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
      try {
        val stmt = conn.prepareStatement(
          "SELECT * FROM reaction_roles WHERE guild_id = ? AND emoji != '_PLACEHOLDER_' ORDER BY message_id, emoji"
        )
        stmt.setString(1, guildId)
        
        val rs = stmt.executeQuery()
        while (rs.next()) {
          configs += ReactionRoleConfig(
            guildId = rs.getString("guild_id"),
            channelId = rs.getString("channel_id"),
            messageId = rs.getString("message_id"),
            emoji = rs.getString("emoji"),
            roleId = rs.getString("role_id"),
            mode = rs.getString("mode"),
            description = rs.getString("description")
          )
        }
        
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    } match {
      case Success(_) => ()
      case Failure(ex) => 
        logger.error(s"Failed to get all reaction roles for guild $guildId", ex)
    }
    
    configs.toList
  }

  /**
   * Pobiera wszystkie message_id z bazy (WŁĄCZNIE z placeholderami)
   * Używane w Add Role dropdown żeby pokazać wszystkie wiadomości
   */
  def getAllMessageIds(guildId: String): List[(String, String, String)] = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val messages = ListBuffer[(String, String, String)]() // (messageId, channelId, title)
    
    Try {
      val conn = DriverManager.getConnection(url, "postgres", Config.postgresPassword)
      try {
        val stmt = conn.prepareStatement(
          """SELECT DISTINCT message_id, channel_id, 
            |  COALESCE(MAX(CASE WHEN emoji = '_PLACEHOLDER_' THEN description END), '') as title
            |FROM reaction_roles 
            |WHERE guild_id = ? 
            |GROUP BY message_id, channel_id
            |ORDER BY message_id""".stripMargin
        )
        stmt.setString(1, guildId)
        
        val rs = stmt.executeQuery()
        while (rs.next()) {
          messages += ((
            rs.getString("message_id"),
            rs.getString("channel_id"),
            rs.getString("title")
          ))
        }
        
        rs.close()
        stmt.close()
      } finally {
        conn.close()
      }
    } match {
      case Success(_) => ()
      case Failure(ex) => 
        logger.error(s"Failed to get all message IDs for guild $guildId", ex)
    }
    
    messages.toList
  }

  /**
   * Znajduje konfigurację dla danego emoji na wiadomości
   */
  def findReactionRole(guildId: String, messageId: String, emoji: String): Option[ReactionRoleConfig] = {
    getReactionRolesForMessage(guildId, messageId).find(_.emoji == emoji)
  }

  /**
   * Dodaje automatycznie wszystkie potrzebne reakcje do wiadomości
   */
  def addReactionsToMessage(guild: Guild, messageId: String): Try[Unit] = Try {
    val configs = getReactionRolesForMessage(guild.getId, messageId)
    
    if (configs.isEmpty) {
      logger.warn(s"No reaction role configs found for message $messageId")
      return Success(())
    }

    val channelId = configs.head.channelId
    val channel = guild.getTextChannelById(channelId)
    
    if (channel == null) {
      logger.error(s"Channel $channelId not found")
      return Failure(new IllegalStateException(s"Channel $channelId not found"))
    }

    val message = channel.retrieveMessageById(messageId).complete()
    
    configs.foreach { config =>
      Try {
        val emoji = parseEmoji(guild, config.emoji)
        message.addReaction(emoji).queue()
        logger.info(s"Added reaction ${config.emoji} to message $messageId")
      } match {
        case Failure(ex) => 
          logger.error(s"Failed to add reaction ${config.emoji} to message $messageId", ex)
        case _ => ()
      }
    }
  }

  /**
   * Parsuje string emoji do obiektu Emoji
   * Format: "👍" dla unicode lub "name:id" dla custom emoji
   */
  def parseEmoji(guild: Guild, emojiString: String): Emoji = {
    if (emojiString.contains(":")) {
      // Custom emoji format: "name:id" lub ":name:"
      val parts = emojiString.split(":")
      if (parts.length >= 2) {
        // Jeśli format to "name:id" (np. "tibia:123456")
        if (parts.length == 2 && parts(1).forall(_.isDigit)) {
          val name = parts(0)
          val id = parts(1).toLong
          Emoji.fromCustom(name, id, false)
        } else {
          // Jeśli format to ":name:" szukamy emoji na serwerze
          val name = if (parts.length == 3) parts(1) else parts(0)
          val customEmoji = guild.getEmojisByName(name, true).asScala.headOption
          customEmoji match {
            case Some(guildEmoji) => Emoji.fromCustom(guildEmoji)
            case None => 
              logger.warn(s"Custom emoji '$name' not found on guild, using as unicode")
              Emoji.fromUnicode(emojiString)
          }
        }
      } else {
        Emoji.fromUnicode(emojiString)
      }
    } else {
      // Unicode emoji
      Emoji.fromUnicode(emojiString)
    }
  }

  /**
   * Konwertuje Emoji obiekt do string format dla zapisu w bazie
   */
  def emojiToString(emoji: Emoji): String = {
    emoji match {
      case custom if emoji.getType == Emoji.Type.CUSTOM =>
        // Format: "name:id" dla custom emoji
        val formatted = emoji.getFormatted // Zwraca "<:name:id>" lub "<a:name:id>"
        // Wyciągnij name:id z formatu "<:name:id>"
        val pattern = """<a?:(.+):(\d+)>""".r
        formatted match {
          case pattern(name, id) => s"$name:$id"
          case _ => emoji.getName
        }
      case _ =>
        // Unicode emoji
        emoji.getName
    }
  }
}