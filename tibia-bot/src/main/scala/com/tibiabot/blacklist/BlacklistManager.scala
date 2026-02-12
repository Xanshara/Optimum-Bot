package com.tibiabot.blacklist

import com.tibiabot.{BotApp, Config}
import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.{Guild, MessageEmbed}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.JDA

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.{Connection, DriverManager, Timestamp}
import java.time.format.DateTimeFormatter
import java.time.{ZoneOffset, ZonedDateTime}
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Manager dla funkcjonalności blacklisty graczy
 * 
 * @param tibiaDataClient Client do komunikacji z TibiaData API
 * @param jda JDA instance do wysyłania wiadomości Discord
 * @param blacklistChannelIds Lista ID kanałów Discord dla blacklisty (z Config)
 */
class BlacklistManager(
  tibiaDataClient: TibiaDataClient,
  jda: JDA,
  blacklistChannelIds: List[String]
)(implicit ec: ExecutionContext) extends StrictLogging {
  
  // Cache blacklist data: guildId -> List[BlacklistPlayer]
  private var blacklistCache: Map[String, List[BlacklistPlayer]] = Map.empty
  
  // Blacklist config cache: guildId -> BlacklistConfig
  private var blacklistConfigCache: Map[String, BlacklistConfig] = Map.empty
  
  // Channel message IDs: channelId -> messageId
  private var channelMessages: Map[String, String] = Map.empty

  /**
   * Włącza/wyłącza blacklist dla gildii
   */
  def toggleBlacklist(event: SlashCommandInteractionEvent, enable: Boolean): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)

    if (!checkConfigDatabase(guild)) {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
      return embedBuild.build()
    }

    try {
      val conn = getConnection(guild)
      
      // Check if config table exists
      val statement = conn.createStatement()
      val tableExistsQuery = statement.executeQuery(
        "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_config'"
      )
      val tableExists = tableExistsQuery.next()
      tableExistsQuery.close()
      
      // Create config table if doesn't exist
      if (!tableExists) {
        val createConfigTable =
          s"""CREATE TABLE blacklist_config (
             |guild_id VARCHAR(255) NOT NULL,
             |enabled BOOLEAN NOT NULL DEFAULT false,
             |PRIMARY KEY (guild_id)
             |);""".stripMargin
        statement.executeUpdate(createConfigTable)
        logger.info(s"Created blacklist_config table for guild ${guild.getName}")
      }
      
      // Check if messages table exists
      val messagesTableQuery = statement.executeQuery(
        "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_messages'"
      )
      val messagesTableExists = messagesTableQuery.next()
      messagesTableQuery.close()
      
      // Create messages table if doesn't exist
      if (!messagesTableExists) {
        val createMessagesTable =
          s"""CREATE TABLE blacklist_messages (
             |channel_id VARCHAR(255) NOT NULL,
             |message_id VARCHAR(255) NOT NULL,
             |world VARCHAR(255) NOT NULL,
             |PRIMARY KEY (channel_id)
             |);""".stripMargin
        statement.executeUpdate(createMessagesTable)
        logger.info(s"Created blacklist_messages table for guild ${guild.getName}")
      }
      
      if (enable) {
        // Check if players table exists
        val playersTableQuery = statement.executeQuery(
          "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_players'"
        )
        val playersTableExists = playersTableQuery.next()
        playersTableQuery.close()
        
        // Create players table if doesn't exist
        if (!playersTableExists) {
          val createPlayersTable =
            s"""CREATE TABLE blacklist_players (
               |name VARCHAR(255) NOT NULL,
               |reason VARCHAR(500) NOT NULL,
               |added_by VARCHAR(255) NOT NULL,
               |added_at TIMESTAMP NOT NULL,
               |is_online BOOLEAN DEFAULT false,
               |last_checked TIMESTAMP,
               |world VARCHAR(255) NOT NULL,
               |PRIMARY KEY (name, world)
               |);""".stripMargin
          statement.executeUpdate(createPlayersTable)
          logger.info(s"Created blacklist_players table for guild ${guild.getName}")
        }
      }
      
      // Update or insert config
      val checkConfig = conn.prepareStatement("SELECT * FROM blacklist_config WHERE guild_id = ?")
      checkConfig.setString(1, guildId)
      val configExists = checkConfig.executeQuery().next()
      checkConfig.close()
      
      if (configExists) {
        val updateStmt = conn.prepareStatement("UPDATE blacklist_config SET enabled = ? WHERE guild_id = ?")
        updateStmt.setBoolean(1, enable)
        updateStmt.setString(2, guildId)
        updateStmt.executeUpdate()
        updateStmt.close()
      } else {
        val insertStmt = conn.prepareStatement("INSERT INTO blacklist_config (guild_id, enabled) VALUES (?, ?)")
        insertStmt.setString(1, guildId)
        insertStmt.setBoolean(2, enable)
        insertStmt.executeUpdate()
        insertStmt.close()
      }
      
      statement.close()
      conn.close()
      
      // Update cache
      blacklistConfigCache = blacklistConfigCache + (guildId -> BlacklistConfig(enable))
      
      val status = if (enable) "enabled" else "disabled"
      embedBuild.setDescription(s":gear: Blacklist has been **$status** for this server.")
      logger.info(s"Blacklist $status for guild ${guild.getName} (${guild.getId})")
      
      // If enabled and there's data, show messages
      if (enable) {
        val world = getGuildWorld(guildId)
        if (world.isDefined && blacklistCache.get(guildId).exists(_.nonEmpty)) {
          updateAllBlacklistMessages(guildId, world.get)
        }
      } else {
        // If disabled, clear messages from channels
        clearBlacklistMessages(guildId)
      }
      
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to toggle blacklist", ex)
        embedBuild.setDescription(s"${Config.noEmoji} Failed to toggle blacklist.")
    }

    embedBuild.build()
  }

  /**
   * Sprawdza czy blacklist jest włączony dla danej gildii
   */
  def isBlacklistEnabled(guildId: String): Boolean = {
    blacklistConfigCache.get(guildId).exists(_.enabled)
  }

  /**
   * Czyści wiadomości blacklist z kanałów
   */
  private def clearBlacklistMessages(guildId: String): Unit = {
    if (blacklistChannelIds.isEmpty || blacklistChannelIds.head == "0") {
      return
    }

    blacklistChannelIds.foreach { channelId =>
      try {
        val channel = jda.getTextChannelById(channelId)
        
        if (channel != null && channel.canTalk()) {
          channelMessages.get(channelId).foreach { messageId =>
            channel.retrieveMessageById(messageId).queue(
              message => message.delete().queue(
                _ => {
                  channelMessages = channelMessages - channelId
                  logger.info(s"Deleted blacklist message from channel $channelId")
                },
                _ => logger.warn(s"Could not delete blacklist message from channel $channelId")
              ),
              _ => logger.warn(s"Blacklist message not found in channel $channelId")
            )
          }
        }
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to clear blacklist message in channel $channelId", ex)
      }
    }
  }

  /**
   * Saves message ID to database
   */
  private def saveMessageId(guild: Guild, channelId: String, messageId: String, world: String): Unit = {
    try {
      val conn = getConnection(guild)
      
      // Check if entry exists
      val checkStmt = conn.prepareStatement("SELECT * FROM blacklist_messages WHERE channel_id = ?")
      checkStmt.setString(1, channelId)
      val exists = checkStmt.executeQuery().next()
      checkStmt.close()
      
      if (exists) {
        // Update existing
        val updateStmt = conn.prepareStatement(
          "UPDATE blacklist_messages SET message_id = ?, world = ? WHERE channel_id = ?"
        )
        updateStmt.setString(1, messageId)
        updateStmt.setString(2, world)
        updateStmt.setString(3, channelId)
        updateStmt.executeUpdate()
        updateStmt.close()
      } else {
        // Insert new
        val insertStmt = conn.prepareStatement(
          "INSERT INTO blacklist_messages (channel_id, message_id, world) VALUES (?, ?, ?)"
        )
        insertStmt.setString(1, channelId)
        insertStmt.setString(2, messageId)
        insertStmt.setString(3, world)
        insertStmt.executeUpdate()
        insertStmt.close()
      }
      
      conn.close()
      logger.debug(s"Saved message ID $messageId for channel $channelId")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to save message ID for channel $channelId", ex)
    }
  }

  /**
   * Loads message IDs from database
   */
  private def loadMessageIds(guild: Guild): Unit = {
    try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      
      // Check if table exists
      val tableExistsQuery = statement.executeQuery(
        "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_messages'"
      )
      val tableExists = tableExistsQuery.next()
      tableExistsQuery.close()
      
      // Create table if doesn't exist
      if (!tableExists) {
        val createMessagesTable =
          s"""CREATE TABLE blacklist_messages (
             |channel_id VARCHAR(255) NOT NULL,
             |message_id VARCHAR(255) NOT NULL,
             |world VARCHAR(255) NOT NULL,
             |PRIMARY KEY (channel_id)
             |);""".stripMargin
        statement.executeUpdate(createMessagesTable)
        logger.info(s"Created blacklist_messages table for guild ${guild.getName}")
      }
      
      val result = statement.executeQuery("SELECT * FROM blacklist_messages")
      
      while (result.next()) {
        val channelId = result.getString("channel_id")
        val messageId = result.getString("message_id")
        channelMessages = channelMessages + (channelId -> messageId)
      }
      
      result.close()
      logger.info(s"Loaded ${channelMessages.size} blacklist message IDs for guild ${guild.getName}")
      
      statement.close()
      conn.close()
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to load message IDs for guild ${guild.getName}", ex)
    }
  }

  /**
   * Dodaje gracza do blacklisty
   */
  def addPlayer(event: SlashCommandInteractionEvent, nick: String, reason: String): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val commandUser = event.getUser.getId
    val nickLower = nick.toLowerCase
    val nickFormal = nick.split(" ").map(_.capitalize).mkString(" ")
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)

    if (!checkConfigDatabase(guild)) {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
      return embedBuild.build()
    }

    // Check if blacklist is enabled
    if (!isBlacklistEnabled(guildId)) {
      embedBuild.setDescription(s"${Config.noEmoji} Blacklist is not enabled. Use `/blacklist on` first.")
      return embedBuild.build()
    }

    // Get world from guild config
    val world = getGuildWorld(guildId) match {
      case Some(w) => w
      case None =>
        embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
        return embedBuild.build()
    }

    // Check if already blacklisted
    val existingBlacklist = blacklistCache.getOrElse(guildId, List())
    if (existingBlacklist.exists(p => p.name.toLowerCase == nickLower && p.world == world)) {
      embedBuild.setDescription(s"${Config.noEmoji} **$nickFormal** is already on the blacklist for world **$world**.")
      return embedBuild.build()
    }

    // Add to database
    try {
      val conn = getConnection(guild)
      val statement = conn.prepareStatement(
        "INSERT INTO blacklist_players (name, reason, added_by, added_at, is_online, last_checked, world) VALUES (?, ?, ?, ?, ?, ?, ?)"
      )
      statement.setString(1, nickLower)
      statement.setString(2, reason)
      statement.setString(3, commandUser)
      statement.setTimestamp(4, Timestamp.from(ZonedDateTime.now().toInstant))
      statement.setBoolean(5, false)
      statement.setTimestamp(6, Timestamp.from(ZonedDateTime.now().toInstant))
      statement.setString(7, world)
      statement.executeUpdate()
      statement.close()
      conn.close()

      // Add to cache
      val newPlayer = BlacklistPlayer(nickLower, reason, commandUser, isOnline = false, ZonedDateTime.now(), world)
      blacklistCache = blacklistCache + (guildId -> (existingBlacklist :+ newPlayer))

      // Update blacklist messages in all channels
      updateAllBlacklistMessages(guildId, world)

      // Send to admin channel
      sendAdminNotification(guild, commandUser, nickFormal, reason, isAdd = true)

      embedBuild.setDescription(s":gear: Added **[$nickFormal](${charUrl(nickFormal)})** to the blacklist for world **$world**.")
      logger.info(s"Added $nickFormal to blacklist for guild ${guild.getName} (${guild.getId})")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to add $nick to blacklist", ex)
        embedBuild.setDescription(s"${Config.noEmoji} Failed to add **$nickFormal** to the blacklist.")
    }

    embedBuild.build()
  }

  /**
   * Usuwa gracza z blacklisty
   */
  def removePlayer(event: SlashCommandInteractionEvent, nick: String): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val commandUser = event.getUser.getId
    val nickLower = nick.toLowerCase
    val nickFormal = nick.split(" ").map(_.capitalize).mkString(" ")
    val embedBuild = new EmbedBuilder()
    embedBuild.setColor(3092790)

    if (!checkConfigDatabase(guild)) {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
      return embedBuild.build()
    }

    // Check if blacklist is enabled
    if (!isBlacklistEnabled(guildId)) {
      embedBuild.setDescription(s"${Config.noEmoji} Blacklist is not enabled. Use `/blacklist on` first.")
      return embedBuild.build()
    }

    // Get world
    val world = getGuildWorld(guildId) match {
      case Some(w) => w
      case None =>
        embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
        return embedBuild.build()
    }

    // Check if exists
    val existingBlacklist = blacklistCache.getOrElse(guildId, List())
    if (!existingBlacklist.exists(p => p.name.toLowerCase == nickLower && p.world == world)) {
      embedBuild.setDescription(s"${Config.noEmoji} **$nickFormal** is not on the blacklist.")
      return embedBuild.build()
    }

    // Remove from database
    try {
      val conn = getConnection(guild)
      val statement = conn.prepareStatement("DELETE FROM blacklist_players WHERE name = ? AND world = ?")
      statement.setString(1, nickLower)
      statement.setString(2, world)
      statement.executeUpdate()
      statement.close()
      conn.close()

      // Remove from cache
      blacklistCache = blacklistCache + (guildId -> existingBlacklist.filterNot(p => p.name.toLowerCase == nickLower && p.world == world))

      // Update blacklist messages in all channels
      updateAllBlacklistMessages(guildId, world)

      // Send to admin channel
      sendAdminNotification(guild, commandUser, nickFormal, "", isAdd = false)

      embedBuild.setDescription(s":gear: Removed **[$nickFormal](${charUrl(nickFormal)})** from the blacklist.")
      logger.info(s"Removed $nickFormal from blacklist for guild ${guild.getName} (${guild.getId})")
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to remove $nick from blacklist", ex)
        embedBuild.setDescription(s"${Config.noEmoji} Failed to remove **$nickFormal** from the blacklist.")
    }

    embedBuild.build()
  }

  /**
   * Wyświetla listę blacklistowanych graczy
   */
  def showBlacklist(event: SlashCommandInteractionEvent): MessageEmbed = {
    val guild = event.getGuild
    val guildId = guild.getId
    val embedBuild = new EmbedBuilder()

    if (!checkConfigDatabase(guild)) {
      embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
      embedBuild.setColor(3092790)
      return embedBuild.build()
    }

    // Check if blacklist is enabled
    if (!isBlacklistEnabled(guildId)) {
      embedBuild.setDescription(s"${Config.noEmoji} Blacklist is not enabled. Use `/blacklist on` first.")
      embedBuild.setColor(3092790)
      return embedBuild.build()
    }

    val world = getGuildWorld(guildId) match {
      case Some(w) => w
      case None =>
        embedBuild.setDescription(s"${Config.noEmoji} You need to run `/setup` and add a world first.")
        embedBuild.setColor(3092790)
        return embedBuild.build()
    }

    createBlacklistEmbed(guildId, world)
  }

  /**
   * Updates online/offline status for all blacklisted players
   * Called by scheduler every 2 minutes
   */
  def updateAllStatuses(): Unit = {
    logger.info("updateAllStatuses called by scheduler")
    
    if (blacklistChannelIds.isEmpty || blacklistChannelIds.head == "0") {
      logger.debug("Blacklist channels not configured, skipping status update")
      return
    }

    logger.info(s"Checking ${blacklistCache.size} guilds for blacklist updates")
    
    blacklistCache.foreach { case (guildId, players) =>
      // Check if blacklist is enabled for this guild
      if (!isBlacklistEnabled(guildId)) {
        logger.debug(s"Blacklist disabled for guild $guildId, skipping update")
      } else if (players.nonEmpty) {
        logger.info(s"Updating ${players.size} players for guild $guildId")
        val guild = jda.getGuildById(guildId)
        if (guild != null) {
          players.groupBy(_.world).foreach { case (world, worldPlayers) =>
            logger.info(s"Fetching world data for $world (${worldPlayers.size} players)")
            updateWorldPlayers(guild, world, worldPlayers)
          }
        } else {
          logger.warn(s"Guild $guildId not found in JDA")
        }
      } else {
        logger.debug(s"No players to update for guild $guildId")
      }
    }
    
    logger.info("updateAllStatuses completed")
  }

  /**
   * Aktualizuje status graczy dla konkretnego świata
   */
  /**
   * Updates online/offline status for players in specific world
   */
  private def updateWorldPlayers(guild: Guild, world: String, players: List[BlacklistPlayer]): Unit = {
    tibiaDataClient.getWorld(world).onComplete {
      case Success(Right(worldResponse)) =>
        logger.info(s"Successfully fetched world data for $world")
        
        // online_players is Option[List[OnlinePlayers]] where OnlinePlayers has name: String
        val onlinePlayers = worldResponse.world.online_players
          .getOrElse(List.empty)
          .map(_.name.toLowerCase)
          .toSet
        
        logger.info(s"World $world has ${onlinePlayers.size} players online")
        
        var updated = false
        val updatedPlayers = players.map { player =>
          val newOnlineStatus = onlinePlayers.contains(player.name.toLowerCase)
          if (player.isOnline != newOnlineStatus) {
            updated = true
            logger.info(s"Player ${player.name} status changed: ${player.isOnline} -> $newOnlineStatus")
            player.copy(isOnline = newOnlineStatus, lastChecked = ZonedDateTime.now())
          } else {
            player.copy(lastChecked = ZonedDateTime.now())
          }
        }

        // Update cache
        val guildId = guild.getId
        val allPlayers = blacklistCache.getOrElse(guildId, List())
        val otherPlayers = allPlayers.filterNot(p => p.world == world)
        blacklistCache = blacklistCache + (guildId -> (otherPlayers ++ updatedPlayers))

        // Update database
        updatedPlayers.foreach { player =>
          updatePlayerInDatabase(guild, player)
        }

        // Always update messages to show "Last updated" timestamp
        if (updated) {
          logger.info(s"Status changed for world $world, updating messages")
        } else {
          logger.info(s"No status changes for world $world, but updating timestamp")
        }
        updateAllBlacklistMessages(guildId, world)

      case Success(Left(error)) =>
        logger.warn(s"Failed to fetch world $world for blacklist update: $error")
      case Failure(ex) =>
        logger.warn(s"Failed to fetch world $world for blacklist update", ex)
    }
  }

  /**
   * Aktualizuje wszystkie wiadomości blacklisty we wszystkich skonfigurowanych kanałach
   */
  private def updateAllBlacklistMessages(guildId: String, world: String): Unit = {
    // Check if blacklist is enabled
    if (!isBlacklistEnabled(guildId)) {
      logger.debug(s"Blacklist disabled for guild $guildId, skipping message update")
      return
    }
    
    if (blacklistChannelIds.isEmpty || blacklistChannelIds.head == "0") {
      logger.debug("Blacklist channels not configured, skipping message update")
      return
    }

    val embed = createBlacklistEmbed(guildId, world)

    blacklistChannelIds.foreach { channelId =>
      try {
        val channel = jda.getTextChannelById(channelId)
        
        if (channel != null && channel.canTalk()) {
          // Check if we have a message ID for this channel
          channelMessages.get(channelId) match {
            case Some(messageId) =>
              // Update existing message
              channel.retrieveMessageById(messageId).queue(
                message => message.editMessageEmbeds(embed).queue(),
                _ => {
                  // Message not found, create new one
                  channel.sendMessageEmbeds(embed).queue(msg => {
                    channelMessages = channelMessages + (channelId -> msg.getId)
                    // Save to database
                    val guild = jda.getGuildById(guildId)
                    if (guild != null) {
                      saveMessageId(guild, channelId, msg.getId, world)
                    }
                    logger.info(s"Created new blacklist message in channel $channelId")
                  })
                }
              )
            case None =>
              // Create new message
              channel.sendMessageEmbeds(embed).queue(msg => {
                channelMessages = channelMessages + (channelId -> msg.getId)
                // Save to database
                val guild = jda.getGuildById(guildId)
                if (guild != null) {
                  saveMessageId(guild, channelId, msg.getId, world)
                }
                logger.info(s"Created new blacklist message in channel $channelId")
              })
          }
        } else {
          logger.warn(s"Cannot update blacklist in channel $channelId - channel not found or no permissions")
        }
      } catch {
        case ex: Exception =>
          logger.error(s"Failed to update blacklist message in channel $channelId", ex)
      }
    }
  }

  /**
   * Tworzy embed z blacklistą
   */
  private def createBlacklistEmbed(guildId: String, world: String): MessageEmbed = {
    val blacklist = blacklistCache.getOrElse(guildId, List()).filter(_.world == world)
    
    if (blacklist.isEmpty) {
      new EmbedBuilder()
        .setTitle(s"🚫 Blacklist - $world")
        .setDescription("*The blacklist is empty.*")
        .setColor(16711680)
        .build()
    } else {
      val sortedList = blacklist.sortBy(p => (!p.isOnline, p.name))
      val listText = sortedList.map { player =>
        val status = if (player.isOnline) "🟢" else "🔴"
        val nameFormal = player.name.split(" ").map(_.capitalize).mkString(" ")
        s"$status **[$nameFormal](${charUrl(nameFormal)})** - ${player.reason}"
      }.mkString("\n")

      new EmbedBuilder()
        .setTitle(s"🚫 Blacklist - $world")
        .setDescription(listText)
        .setFooter(s"Last updated: ${ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
        .setColor(16711680)
        .build()
    }
  }

  /**
   * Wysyła powiadomienie do kanału admina
   */
  private def sendAdminNotification(guild: Guild, userId: String, playerName: String, reason: String, isAdd: Boolean): Unit = {
    val discordInfo = BotApp.discordRetrieveConfig(guild)
    val adminChannel = guild.getTextChannelById(discordInfo.getOrElse("admin_channel", "0"))
    
    if (adminChannel != null && (adminChannel.canTalk() || !Config.prod)) {
      val adminEmbed = new EmbedBuilder()
      adminEmbed.setTitle(":gear: Blacklist Updated")
      
      if (isAdd) {
        adminEmbed.setDescription(s"<@$userId> added **[$playerName](${charUrl(playerName)})** to the blacklist.\n**Reason:** $reason")
      } else {
        adminEmbed.setDescription(s"<@$userId> removed **[$playerName](${charUrl(playerName)})** from the blacklist.")
      }
      
      adminEmbed.setThumbnail("https://tibia.fandom.com/wiki/Special:Redirect/file/Dead_Tree.gif")
      adminEmbed.setColor(if (isAdd) 16711680 else 3092790) // Red for add, blue for remove
      adminChannel.sendMessageEmbeds(adminEmbed.build()).queue()
    }
  }

  /**
   * Ładuje blacklistę z bazy danych
   */
  def loadFromDatabase(guild: Guild): Unit = {
    val guildId = guild.getId
    try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      
      // Load config first
      val configTableQuery = statement.executeQuery(
        "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_config'"
      )
      val configTableExists = configTableQuery.next()
      configTableQuery.close()
      
      if (configTableExists) {
        val configResult = statement.executeQuery(s"SELECT * FROM blacklist_config WHERE guild_id = '$guildId'")
        if (configResult.next()) {
          val enabled = configResult.getBoolean("enabled")
          blacklistConfigCache = blacklistConfigCache + (guildId -> BlacklistConfig(enabled))
          logger.info(s"Loaded blacklist config for guild ${guild.getName}: enabled=$enabled")
        }
        configResult.close()
      } else {
        // Default to disabled if no config
        blacklistConfigCache = blacklistConfigCache + (guildId -> BlacklistConfig(false))
        logger.info(s"No blacklist config found for guild ${guild.getName}, defaulting to disabled")
      }
      
      // Check if players table exists
      val tableExistsQuery = statement.executeQuery(
        "SELECT * FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'blacklist_players'"
      )
      val tableExists = tableExistsQuery.next()
      tableExistsQuery.close()

      if (!tableExists) {
        // Table will be created when user runs /blacklist on
        statement.close()
        conn.close()
        logger.info(s"Blacklist table doesn't exist yet for guild ${guild.getName}, will be created when enabled")
        return
      }

      val result = statement.executeQuery("SELECT * FROM blacklist_players")
      val blacklistBuffer = ListBuffer[BlacklistPlayer]()

      while (result.next()) {
        val name = result.getString("name")
        val reason = result.getString("reason")
        val addedBy = result.getString("added_by")
        val isOnline = result.getBoolean("is_online")
        val lastChecked = ZonedDateTime.ofInstant(
          result.getTimestamp("last_checked").toInstant,
          ZoneOffset.UTC
        )
        val world = result.getString("world")

        blacklistBuffer += BlacklistPlayer(name, reason, addedBy, isOnline, lastChecked, world)
      }

      blacklistCache = blacklistCache + (guildId -> blacklistBuffer.toList)
      
      statement.close()
      conn.close()
      
      logger.info(s"Loaded ${blacklistBuffer.size} blacklisted players for guild ${guild.getName}")
      
      // Load message IDs from database
      loadMessageIds(guild)
      
      // Initialize messages in all channels ONLY if enabled
      if (isBlacklistEnabled(guildId) && blacklistBuffer.nonEmpty && blacklistChannelIds.nonEmpty && blacklistChannelIds.head != "0") {
        val world = blacklistBuffer.headOption.map(_.world).getOrElse("")
        if (world.nonEmpty) {
          updateAllBlacklistMessages(guildId, world)
        }
      }
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to load blacklist for guild ${guild.getName}", ex)
    }
  }

  /**
   * Aktualizuje gracza w bazie danych
   */
  private def updatePlayerInDatabase(guild: Guild, player: BlacklistPlayer): Unit = {
    try {
      val conn = getConnection(guild)
      val statement = conn.prepareStatement(
        "UPDATE blacklist_players SET is_online = ?, last_checked = ? WHERE name = ? AND world = ?"
      )
      statement.setBoolean(1, player.isOnline)
      statement.setTimestamp(2, Timestamp.from(player.lastChecked.toInstant))
      statement.setString(3, player.name)
      statement.setString(4, player.world)
      statement.executeUpdate()
      statement.close()
      conn.close()
    } catch {
      case ex: Exception =>
        logger.error(s"Failed to update blacklist player ${player.name} in database", ex)
    }
  }

  // Helper methods
  
  private def getConnection(guild: Guild): Connection = {
    val guildId = guild.getId
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    val username = "postgres"
    val password = Config.postgresPassword
    DriverManager.getConnection(url, username, password)
  }

  private def checkConfigDatabase(guild: Guild): Boolean = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/postgres"
    val username = "postgres"
    val password = Config.postgresPassword
    val guildId = guild.getId

    val conn = DriverManager.getConnection(url, username, password)
    val statement = conn.createStatement()
    val result = statement.executeQuery(s"SELECT datname FROM pg_database WHERE datname = '_$guildId'")
    val exist = result.next()

    statement.close()
    conn.close()

    exist
  }

  private def getGuildWorld(guildId: String): Option[String] = {
    BotApp.worldsData.get(guildId).flatMap(_.headOption.map(_.name))
  }

  private def charUrl(char: String): String = {
    val encodedString = URLEncoder.encode(char, StandardCharsets.UTF_8.toString)
    s"https://www.tibia.com/community/?name=$encodedString"
  }
}