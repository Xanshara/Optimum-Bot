package com.tibiabot.serverstats

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.events.guild.member.{GuildMemberJoinEvent, GuildMemberRemoveEvent}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

import java.awt.Color
import java.sql.{Connection, DriverManager}
import scala.util.{Failure, Success, Try}

/**
 * Listener dla funkcji statystyk serwera
 * Obsługuje komendy /serverstats oraz automatyczną aktualizację liczby członków
 */
class ServerStatsListener extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "serverstats") {
      val subCommand = event.getSubcommandName
      
      // Sprawdź uprawnienia
      val member = event.getMember
      if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
        event.deferReply(true).queue()
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Nie masz uprawnień do użycia tej komendy.")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
        return
      }
      
      event.deferReply().queue()
      
      subCommand match {
        case "on" => handleServerStatsOn(event)
        case "off" => handleServerStatsOff(event)
        case _ =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Nieznana podkomenda: $subCommand")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    }
  }
  
  override def onGuildMemberJoin(event: GuildMemberJoinEvent): Unit = {
    updateMemberCount(event.getGuild)
  }
  
  override def onGuildMemberRemove(event: GuildMemberRemoveEvent): Unit = {
    updateMemberCount(event.getGuild)
  }
  
  /**
   * Obsługa komendy /serverstats on
   */
  private def handleServerStatsOn(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    
    Try {
      // Sprawdź czy już istnieje konfiguracja
      if (isServerStatsEnabled(guild)) {
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Statystyki serwera są już włączone!")
          .setColor(Color.ORANGE)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
        return
      }
      
      // Stwórz kategorię
      val category = guild.createCategory("📊 SERVER STATS")
        .complete()
      
      // Stwórz kanał głosowy z liczbą członków
      val memberCount = guild.getMemberCount
      val voiceChannel = category.createVoiceChannel(s"👥 Members: $memberCount")
        .complete()
      
      // Zablokuj dołączanie do kanału (tylko wyświetlanie)
      voiceChannel.getManager
        .putRolePermissionOverride(
          guild.getPublicRole.getIdLong,
          0L,
          Permission.VOICE_CONNECT.getRawValue
        )
        .complete()
      
      // Zapisz konfigurację do bazy danych
      saveServerStatsConfig(guild, category.getId, voiceChannel.getId)
      
      logger.info(s"Server stats enabled for guild: ${guild.getName} (${guild.getId})")
      
      val successEmbed = new EmbedBuilder()
        .setTitle("✅ Statystyki Serwera Włączone")
        .setDescription(
          s"Kategoria i kanał zostały utworzone!\n\n" +
          s"**Kategoria:** ${category.getName}\n" +
          s"**Kanał:** ${voiceChannel.getName}\n\n" +
          s"Liczba członków będzie automatycznie aktualizowana."
        )
        .setColor(new Color(0, 255, 0))
        .build()
      
      event.getHook.sendMessageEmbeds(successEmbed).queue()
      
    } match {
      case Success(_) => // Sukces obsłużony w Try block
      case Failure(exception) =>
        logger.error(s"Error enabling server stats for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd podczas włączania statystyk serwera:\n```${exception.getMessage}```")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }
  
  /**
   * Obsługa komendy /serverstats off
   */
  private def handleServerStatsOff(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    
    Try {
      // Pobierz konfigurację
      getServerStatsConfig(guild) match {
        case Some((categoryId, channelId)) =>
          // Usuń kanał
          Option(guild.getVoiceChannelById(channelId)).foreach(_.delete().queue())
          
          // Usuń kategorię
          Option(guild.getCategoryById(categoryId)).foreach(_.delete().queue())
          
          // Usuń konfigurację z bazy danych
          deleteServerStatsConfig(guild)
          
          logger.info(s"Server stats disabled for guild: ${guild.getName} (${guild.getId})")
          
          val successEmbed = new EmbedBuilder()
            .setTitle("✅ Statystyki Serwera Wyłączone")
            .setDescription("Kategoria i kanał zostały usunięte.")
            .setColor(new Color(0, 255, 0))
            .build()
          
          event.getHook.sendMessageEmbeds(successEmbed).queue()
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Statystyki serwera nie są włączone!")
            .setColor(Color.ORANGE)
            .build()
          
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
      
    } match {
      case Success(_) => // Sukces obsłużony w Try block
      case Failure(exception) =>
        logger.error(s"Error disabling server stats for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd podczas wyłączania statystyk serwera:\n```${exception.getMessage}```")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }
  
  /**
   * Aktualizuje liczbę członków w kanale głosowym
   */
  private def updateMemberCount(guild: Guild): Unit = {
    Try {
      getServerStatsConfig(guild) match {
        case Some((_, channelId)) =>
          Option(guild.getVoiceChannelById(channelId)).foreach { channel =>
            val memberCount = guild.getMemberCount
            val newName = s"👥 Members: $memberCount"
            
            // Aktualizuj nazwę kanału tylko jeśli się zmieniła
            if (channel.getName != newName) {
              channel.getManager.setName(newName).queue(
                _ => logger.debug(s"Updated member count for guild ${guild.getId}: $memberCount"),
                error => logger.error(s"Failed to update member count for guild ${guild.getId}", error)
              )
            }
          }
        case None => // Statystyki nie są włączone dla tego serwera
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error updating member count for guild ${guild.getId}", exception)
    }
  }
  
  /**
   * Sprawdza czy statystyki serwera są włączone dla danego guild
   */
  private def isServerStatsEnabled(guild: Guild): Boolean = {
    getServerStatsConfig(guild).isDefined
  }
  
  /**
   * Pobiera konfigurację statystyk serwera z bazy danych
   * @return Option zawierający (categoryId, channelId) lub None jeśli nie znaleziono
   */
  private def getServerStatsConfig(guild: Guild): Option[(String, String)] = {
    Try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      
      // Sprawdź czy tabela istnieje
      val tableExists = statement.executeQuery(
        "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'server_stats')"
      )
      
      if (!tableExists.next() || !tableExists.getBoolean(1)) {
        statement.close()
        conn.close()
        return None
      }
      
      val result = statement.executeQuery("SELECT category_id, channel_id FROM server_stats LIMIT 1")
      
      val config = if (result.next()) {
        Some((result.getString("category_id"), result.getString("channel_id")))
      } else {
        None
      }
      
      statement.close()
      conn.close()
      
      config
    } match {
      case Success(value) => value
      case Failure(exception) =>
        logger.error(s"Error getting server stats config for guild ${guild.getId}", exception)
        None
    }
  }
  
  /**
   * Zapisuje konfigurację statystyk serwera do bazy danych
   */
  private def saveServerStatsConfig(guild: Guild, categoryId: String, channelId: String): Unit = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    
    // Utwórz tabelę jeśli nie istnieje
    statement.execute(
      """CREATE TABLE IF NOT EXISTS server_stats (
        |  id SERIAL PRIMARY KEY,
        |  category_id VARCHAR(255) NOT NULL,
        |  channel_id VARCHAR(255) NOT NULL,
        |  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        |)""".stripMargin
    )
    
    // Usuń starą konfigurację (powinna być tylko jedna)
    statement.execute("DELETE FROM server_stats")
    
    // Wstaw nową konfigurację
    val insertStatement = conn.prepareStatement(
      "INSERT INTO server_stats (category_id, channel_id) VALUES (?, ?)"
    )
    insertStatement.setString(1, categoryId)
    insertStatement.setString(2, channelId)
    insertStatement.executeUpdate()
    
    insertStatement.close()
    statement.close()
    conn.close()
  }
  
  /**
   * Usuwa konfigurację statystyk serwera z bazy danych
   */
  private def deleteServerStatsConfig(guild: Guild): Unit = {
    Try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      
      statement.execute("DELETE FROM server_stats")
      
      statement.close()
      conn.close()
    } match {
      case Success(_) =>
        logger.info(s"Server stats config deleted for guild ${guild.getId}")
      case Failure(exception) =>
        logger.error(s"Error deleting server stats config for guild ${guild.getId}", exception)
    }
  }
  
  /**
   * Pobiera połączenie do bazy danych dla danego guild
   */
  private def getConnection(guild: Guild): Connection = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_${guild.getId}"
    val username = "postgres"
    val password = Config.postgresPassword
    DriverManager.getConnection(url, username, password)
  }
}
