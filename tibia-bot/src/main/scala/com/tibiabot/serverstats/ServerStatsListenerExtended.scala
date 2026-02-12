package com.tibiabot.serverstats

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.events.guild.member.{GuildMemberJoinEvent, GuildMemberRemoveEvent}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.events.user.update.UserUpdateOnlineStatusEvent
import net.dv8tion.jda.api.interactions.components.selections.{StringSelectMenu, SelectOption}

import java.awt.Color
import java.sql.{Connection, DriverManager}
import java.time.{Duration, ZoneId, ZonedDateTime}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * Rozszerzona wersja listenera dla statystyk serwera
 * Obsługuje wiele typów statystyk i konfigurację per-serwer
 */
class ServerStatsListenerExtended extends ListenerAdapter with StrictLogging {

  // Typy statystyk
  sealed trait StatType {
    def channelName(guild: Guild): String
    def icon: String
    def category: StatCategory
  }

  // Kategorie statystyk
  sealed trait StatCategory
  case object PublicCategory extends StatCategory
  case object AdvancedCategory extends StatCategory
  case object AdminCategory extends StatCategory

  // Statystyki publiczne (podstawowe, dostępne domyślnie)
  case object MembersStat extends StatType {
    def channelName(guild: Guild): String = s"$icon Members: ${guild.getMemberCount}"
    val icon = "👥"
    val category = PublicCategory
  }

  case object BotsStat extends StatType {
    def channelName(guild: Guild): String = {
      val botCount = guild.getMembers.asScala.count(_.getUser.isBot)
      s"$icon Bots: $botCount"
    }
    val icon = "🤖"
    val category = PublicCategory
  }

  case object UsersStat extends StatType {
    def channelName(guild: Guild): String = {
      val userCount = guild.getMembers.asScala.count(!_.getUser.isBot)
      s"$icon Users: $userCount"
    }
    val icon = "👤"
    val category = PublicCategory
  }

  case object OnlineStat extends StatType {
    def channelName(guild: Guild): String = {
      val members = guild.getMembers.asScala.toList
      logger.info(s"DEBUG OnlineStat: Total members in guild: ${members.size}")
      
      members.foreach { member =>
        logger.info(s"DEBUG: Member ${member.getEffectiveName} - Status: ${member.getOnlineStatus} - IsBot: ${member.getUser.isBot}")
      }
      
      val onlineCount = members.count { member =>
        member.getOnlineStatus != OnlineStatus.OFFLINE && !member.getUser.isBot
      }
      
      logger.info(s"DEBUG OnlineStat: Counted $onlineCount online users")
      
      s"$icon Online: $onlineCount"
    }
    val icon = "🟢"
    val category = PublicCategory
  }

  case object ChannelsStat extends StatType {
    def channelName(guild: Guild): String = {
      val channelCount = guild.getChannels.size()
      s"$icon Channels: $channelCount"
    }
    val icon = "📝"
    val category = PublicCategory
  }

  case object TextChannelsStat extends StatType {
    def channelName(guild: Guild): String = {
      val textCount = guild.getTextChannels.size()
      s"$icon Text: $textCount"
    }
    val icon = "💬"
    val category = PublicCategory
  }

  case object VoiceChannelsStat extends StatType {
    def channelName(guild: Guild): String = {
      val voiceCount = guild.getVoiceChannels.size()
      s"$icon Voice: $voiceCount"
    }
    val icon = "🔊"
    val category = PublicCategory
  }

  case object RolesStat extends StatType {
    def channelName(guild: Guild): String = {
      val roleCount = guild.getRoles.size()
      s"$icon Roles: $roleCount"
    }
    val icon = "🎭"
    val category = PublicCategory
  }

  case object EmojisStat extends StatType {
    def channelName(guild: Guild): String = {
      val emojiCount = guild.getEmojis.size()
      s"$icon Emojis: $emojiCount"
    }
    val icon = "😀"
    val category = PublicCategory
  }

  // Statystyki zaawansowane (advanced) - wymagają ręcznego włączenia
  case object BoostsStat extends StatType {
    def channelName(guild: Guild): String = {
      val boostCount = guild.getBoostCount
      s"$icon Boosts: $boostCount"
    }
    val icon = "🚀"
    val category = AdvancedCategory
  }

  case object BoostLevelStat extends StatType {
    def channelName(guild: Guild): String = {
      val boostLevel = guild.getBoostTier.getKey
      s"$icon Level: $boostLevel"
    }
    val icon = "📊"
    val category = AdvancedCategory
  }

  // Statystyki adminowe (tylko dla administratorów)
  case object ServerAgeStat extends StatType {
    def channelName(guild: Guild): String = {
      val created = guild.getTimeCreated
      val now = ZonedDateTime.now(ZoneId.systemDefault())
      val days = Duration.between(created.toInstant, now.toInstant).toDays
      s"$icon Age: ${days}d"
    }
    val icon = "📅"
    val category = AdminCategory
  }

  case object VoiceActivityStat extends StatType {
    def channelName(guild: Guild): String = {
      val voiceUsers = guild.getVoiceChannels.asScala.map(_.getMembers.size()).sum
      s"$icon In Voice: $voiceUsers"
    }
    val icon = "🎤"
    val category = AdminCategory
  }

  case object VerificationLevelStat extends StatType {
    def channelName(guild: Guild): String = {
      val level = guild.getVerificationLevel.name
      s"$icon Security: $level"
    }
    val icon = "🔐"
    val category = AdminCategory
  }

  case object CreatedDateStat extends StatType {
    def channelName(guild: Guild): String = {
      val created = guild.getTimeCreated.toLocalDate
      s"$icon Since: ${created.getYear}"
    }
    val icon = "🗓️"
    val category = AdminCategory
  }

  // Mapa wszystkich dostępnych statystyk
  val allStats: Map[String, StatType] = Map(
    "members" -> MembersStat,
    "bots" -> BotsStat,
    "users" -> UsersStat,
    "online" -> OnlineStat,
    "channels" -> ChannelsStat,
    "text_channels" -> TextChannelsStat,
    "voice_channels" -> VoiceChannelsStat,
    "roles" -> RolesStat,
    "emojis" -> EmojisStat,
    "boosts" -> BoostsStat,
    "boost_level" -> BoostLevelStat,
    "server_age" -> ServerAgeStat,
    "voice_activity" -> VoiceActivityStat,
    "verification" -> VerificationLevelStat,
    "created_date" -> CreatedDateStat
  )

  val publicStats: Map[String, StatType] = allStats.filter(_._2.category == PublicCategory)
  val advancedStats: Map[String, StatType] = allStats.filter(_._2.category == AdvancedCategory)
  val adminStats: Map[String, StatType] = allStats.filter(_._2.category == AdminCategory)

  // Domyślne statystyki do wyświetlania (BEZ BOOSTS!)
  val defaultPublicStats = List("members", "bots", "online")
  val defaultAdminStats = List[String]() // Puste domyślnie
  val defaultAdvancedStats = List[String]() // Puste domyślnie

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
        case "configure" => handleConfigure(event)
        case "admin" => handleAdminStats(event)
        case "advanced" => handleAdvanced(event)
        case "list" => handleList(event)
        case _ =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Nieznana podkomenda: $subCommand")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    }
  }

  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    if (event.getComponentId.startsWith("advanced_stats_")) {
      val guild = event.getGuild
      
      // Sprawdź uprawnienia
      val member = event.getMember
      if (member == null || !member.hasPermission(Permission.MANAGE_SERVER)) {
        event.deferReply(true).queue()
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Nie masz uprawnień do użycia tej opcji.")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
        return
      }
      
      event.deferReply().queue()
      handleAdvancedSelection(event)
    }
  }
  
  override def onGuildMemberJoin(event: GuildMemberJoinEvent): Unit = {
    updateAllStatChannels(event.getGuild)
  }
  
  override def onGuildMemberRemove(event: GuildMemberRemoveEvent): Unit = {
    updateAllStatChannels(event.getGuild)
  }

  override def onUserUpdateOnlineStatus(event: UserUpdateOnlineStatusEvent): Unit = {
    // Event jest na poziomie usera, nie guild, więc musimy zaktualizować wszystkie guildy gdzie user jest członkiem
    event.getJDA.getGuilds.asScala.foreach { guild =>
      if (guild.isMember(event.getUser)) {
        updateAllStatChannels(guild)
      }
    }
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
      
      // Stwórz domyślne kanały statystyk
      val channelIds = createDefaultStatChannels(guild, category.getId)
      
      // Zapisz konfigurację do bazy danych (używając już utworzonych channelIds)
      updateServerStatsConfig(guild, category.getId, defaultPublicStats, List(), List(), channelIds)
      
      logger.info(s"Server stats enabled for guild: ${guild.getName} (${guild.getId})")
      
      val successEmbed = new EmbedBuilder()
        .setTitle("✅ Statystyki Serwera Włączone")
        .setDescription(
          s"Kategoria i kanały zostały utworzone!\n\n" +
          s"**Kategoria:** ${category.getName}\n" +
          s"**Kanały:** ${defaultPublicStats.length} podstawowych statystyk\n\n" +
          s"📝 Użyj `/serverstats configure` aby dodać/usunąć statystyki publiczne\n" +
          s"🔧 Użyj `/serverstats advanced` aby zarządzać statystykami zaawansowanymi (np. Boosts)\n" +
          s"👑 Użyj `/serverstats admin` aby włączyć statystyki adminowe\n" +
          s"📋 Użyj `/serverstats list` aby zobaczyć dostępne statystyki"
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
      getServerStatsConfig(guild) match {
        case Some(config) =>
          // KROK 1: Usuń WSZYSTKIE kanały voice z kategorii (nie tylko te w config)
          // To zapewnia że żadne kanały nie pozostaną bez kategorii
          val category = Option(guild.getCategoryById(config.categoryId))
          category.foreach { cat =>
            // Usuń wszystkie kanały voice w kategorii
            cat.getVoiceChannels.asScala.foreach { channel =>
              Try(channel.delete().complete()) match {
                case Success(_) => logger.debug(s"Deleted voice channel: ${channel.getName}")
                case Failure(ex) => logger.warn(s"Failed to delete channel ${channel.getName}", ex)
              }
            }
          }
          
          // KROK 2: Usuń kategorię (już jest pusta)
          category.foreach { cat =>
            Try(cat.delete().complete()) match {
              case Success(_) => logger.debug(s"Deleted category: ${cat.getName}")
              case Failure(ex) => logger.warn(s"Failed to delete category", ex)
            }
          }
          
          // KROK 3: Usuń konfigurację z bazy danych
          deleteServerStatsConfig(guild)
          
          logger.info(s"Server stats disabled for guild: ${guild.getName} (${guild.getId})")
          
          val successEmbed = new EmbedBuilder()
            .setTitle("✅ Statystyki Serwera Wyłączone")
            .setDescription("Kategoria i wszystkie kanały zostały usunięte.")
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
   * Obsługa komendy /serverstats configure <add/remove> <stat_name>
   */
  private def handleConfigure(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    val action = event.getOption("action").getAsString
    val statName = event.getOption("stat").getAsString
    
    Try {
      getServerStatsConfig(guild) match {
        case Some(config) =>
          // Sprawdź czy statystyka istnieje
          if (!publicStats.contains(statName)) {
            val errorEmbed = new EmbedBuilder()
              .setDescription(s"${Config.noEmoji} Nieznana statystyka: `$statName`\nUżyj `/serverstats list` aby zobaczyć dostępne statystyki.")
              .setColor(Color.RED)
              .build()
            event.getHook.sendMessageEmbeds(errorEmbed).queue()
            return
          }
          
          action match {
            case "add" =>
              if (config.enabledStats.contains(statName)) {
                val errorEmbed = new EmbedBuilder()
                  .setDescription(s"${Config.noEmoji} Statystyka `$statName` jest już włączona!")
                  .setColor(Color.ORANGE)
                  .build()
                event.getHook.sendMessageEmbeds(errorEmbed).queue()
              } else {
                // Dodaj nowy kanał
                val stat = publicStats(statName)
                val category = guild.getCategoryById(config.categoryId)
                val channel = category.createVoiceChannel(stat.channelName(guild)).complete()
                
                // Zablokuj dołączanie
                channel.getManager
                  .putRolePermissionOverride(
                    guild.getPublicRole.getIdLong,
                    0L,
                    Permission.VOICE_CONNECT.getRawValue
                  )
                  .complete()
                
                // Zaktualizuj konfigurację
                updateServerStatsConfig(guild, config.categoryId, config.enabledStats :+ statName, config.advancedStats, config.adminStats, config.channelIds + (statName -> channel.getId))
                
                val successEmbed = new EmbedBuilder()
                  .setDescription(s"✅ Dodano statystykę: ${stat.icon} `$statName`")
                  .setColor(new Color(0, 255, 0))
                  .build()
                event.getHook.sendMessageEmbeds(successEmbed).queue()
              }
              
            case "remove" =>
              if (!config.enabledStats.contains(statName)) {
                val errorEmbed = new EmbedBuilder()
                  .setDescription(s"${Config.noEmoji} Statystyka `$statName` nie jest włączona!")
                  .setColor(Color.ORANGE)
                  .build()
                event.getHook.sendMessageEmbeds(errorEmbed).queue()
              } else {
                // Usuń kanał
                config.channelIds.get(statName).foreach { channelId =>
                  Option(guild.getVoiceChannelById(channelId)).foreach(_.delete().complete())
                }
                
                // Zaktualizuj konfigurację
                updateServerStatsConfig(guild, config.categoryId, config.enabledStats.filter(_ != statName), config.advancedStats, config.adminStats, config.channelIds - statName)
                
                val stat = publicStats(statName)
                val successEmbed = new EmbedBuilder()
                  .setDescription(s"✅ Usunięto statystykę: ${stat.icon} `$statName`")
                  .setColor(new Color(0, 255, 0))
                  .build()
                event.getHook.sendMessageEmbeds(successEmbed).queue()
              }
          }
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Statystyki serwera nie są włączone! Użyj `/serverstats on` najpierw.")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error configuring server stats for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd: ${exception.getMessage}")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }

  /**
   * Obsługa komendy /serverstats admin <add/remove> <stat_name>
   */
  private def handleAdminStats(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    val action = event.getOption("action").getAsString
    val statName = event.getOption("stat").getAsString
    
    Try {
      getServerStatsConfig(guild) match {
        case Some(config) =>
          // Sprawdź czy statystyka istnieje
          if (!adminStats.contains(statName)) {
            val errorEmbed = new EmbedBuilder()
              .setDescription(s"${Config.noEmoji} Nieznana statystyka adminowa: `$statName`\nUżyj `/serverstats list` aby zobaczyć dostępne statystyki.")
              .setColor(Color.RED)
              .build()
            event.getHook.sendMessageEmbeds(errorEmbed).queue()
            return
          }
          
          action match {
            case "add" =>
              if (config.adminStats.contains(statName)) {
                val errorEmbed = new EmbedBuilder()
                  .setDescription(s"${Config.noEmoji} Statystyka adminowa `$statName` jest już włączona!")
                  .setColor(Color.ORANGE)
                  .build()
                event.getHook.sendMessageEmbeds(errorEmbed).queue()
              } else {
                // Dodaj nowy kanał
                val stat = adminStats(statName)
                val category = guild.getCategoryById(config.categoryId)
                val channel = category.createVoiceChannel(stat.channelName(guild)).complete()
                
                // Zablokuj dołączanie
                channel.getManager
                  .putRolePermissionOverride(
                    guild.getPublicRole.getIdLong,
                    0L,
                    Permission.VOICE_CONNECT.getRawValue
                  )
                  .complete()
                
                // Zaktualizuj konfigurację
                updateServerStatsConfig(guild, config.categoryId, config.enabledStats, config.advancedStats, config.adminStats :+ statName, config.channelIds + (statName -> channel.getId))
                
                val successEmbed = new EmbedBuilder()
                  .setDescription(s"✅ Dodano statystykę adminową: ${stat.icon} `$statName`")
                  .setColor(new Color(0, 255, 0))
                  .build()
                event.getHook.sendMessageEmbeds(successEmbed).queue()
              }
              
            case "remove" =>
              if (!config.adminStats.contains(statName)) {
                val errorEmbed = new EmbedBuilder()
                  .setDescription(s"${Config.noEmoji} Statystyka adminowa `$statName` nie jest włączona!")
                  .setColor(Color.ORANGE)
                  .build()
                event.getHook.sendMessageEmbeds(errorEmbed).queue()
              } else {
                // Usuń kanał
                config.channelIds.get(statName).foreach { channelId =>
                  Option(guild.getVoiceChannelById(channelId)).foreach(_.delete().complete())
                }
                
                // Zaktualizuj konfigurację
                updateServerStatsConfig(guild, config.categoryId, config.enabledStats, config.advancedStats, config.adminStats.filter(_ != statName), config.channelIds - statName)
                
                val stat = adminStats(statName)
                val successEmbed = new EmbedBuilder()
                  .setDescription(s"✅ Usunięto statystykę adminową: ${stat.icon} `$statName`")
                  .setColor(new Color(0, 255, 0))
                  .build()
                event.getHook.sendMessageEmbeds(successEmbed).queue()
              }
          }
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Statystyki serwera nie są włączone! Użyj `/serverstats on` najpierw.")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error configuring admin stats for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd: ${exception.getMessage}")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }

  /**
   * Obsługa komendy /serverstats advanced
   * Wyświetla dropdown menu do zarządzania statystykami zaawansowanymi
   */
  private def handleAdvanced(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    
    Try {
      getServerStatsConfig(guild) match {
        case Some(config) =>
          // Stwórz dropdown menu z dostępnymi statystykami zaawansowanymi
          val options = advancedStats.map { case (name, stat) =>
            val isEnabled = config.advancedStats.contains(name)
            val label = s"${stat.icon} ${name.replace("_", " ").capitalize}"
            val description = if (isEnabled) "Obecnie włączona" else "Obecnie wyłączona"
            
            SelectOption.of(label, name)
              .withDescription(description)
              .withDefault(isEnabled)
          }.toSeq
          
          val menu = StringSelectMenu.create(s"advanced_stats_${guild.getId}")
            .setPlaceholder("Wybierz statystykę zaawansowaną...")
            .setMinValues(0)
            .setMaxValues(advancedStats.size)
            .addOptions(options: _*)
            .build()
          
          val currentAdvanced = if (config.advancedStats.isEmpty) {
            "*Brak włączonych statystyk zaawansowanych*"
          } else {
            config.advancedStats.map(s => s"`$s`").mkString(", ")
          }
          
          val embed = new EmbedBuilder()
            .setTitle("🔧 Statystyki Zaawansowane")
            .setDescription(
              "Wybierz statystyki zaawansowane, które chcesz włączyć lub wyłączyć.\n\n" +
              "**Dostępne statystyki:**\n" +
              advancedStats.map { case (name, stat) => 
                s"${stat.icon} **${name}** - ${stat.channelName(guild)}"
              }.mkString("\n") +
              s"\n\n**Obecnie włączone:**\n$currentAdvanced"
            )
            .setColor(new Color(255, 165, 0))
            .setFooter("Zaznacz statystyki które chcesz mieć włączone", null)
            .build()
          
          event.getHook
            .sendMessageEmbeds(embed)
            .addActionRow(menu)
            .queue()
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Statystyki serwera nie są włączone! Użyj `/serverstats on` najpierw.")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error showing advanced stats menu for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd: ${exception.getMessage}")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }

  /**
   * Obsługa wyboru z dropdown menu statystyk zaawansowanych
   */
  private def handleAdvancedSelection(event: StringSelectInteractionEvent): Unit = {
    val guild = event.getGuild
    val selectedStats = event.getValues.asScala.toList
    
    Try {
      getServerStatsConfig(guild) match {
        case Some(config) =>
          val currentAdvanced = config.advancedStats
          
          // Określ które statystyki dodać i które usunąć
          val toAdd = selectedStats.filterNot(currentAdvanced.contains)
          val toRemove = currentAdvanced.filterNot(selectedStats.contains)
          
          // Usuń kanały dla wyłączonych statystyk
          toRemove.foreach { statName =>
            config.channelIds.get(statName).foreach { channelId =>
              Option(guild.getVoiceChannelById(channelId)).foreach(_.delete().complete())
            }
          }
          
          // Dodaj kanały dla nowych statystyk
          var updatedChannelIds = config.channelIds -- toRemove
          toAdd.foreach { statName =>
            advancedStats.get(statName).foreach { stat =>
              val category = guild.getCategoryById(config.categoryId)
              val channel = category.createVoiceChannel(stat.channelName(guild)).complete()
              
              // Zablokuj dołączanie
              channel.getManager
                .putRolePermissionOverride(
                  guild.getPublicRole.getIdLong,
                  0L,
                  Permission.VOICE_CONNECT.getRawValue
                )
                .complete()
              
              updatedChannelIds += (statName -> channel.getId)
            }
          }
          
          // Zaktualizuj konfigurację
          updateServerStatsConfig(guild, config.categoryId, config.enabledStats, selectedStats, config.adminStats, updatedChannelIds)
          
          val changesText = if (toAdd.isEmpty && toRemove.isEmpty) {
            "Nie wprowadzono żadnych zmian."
          } else {
            val addText = if (toAdd.nonEmpty) s"**Dodano:** ${toAdd.mkString(", ")}" else ""
            val removeText = if (toRemove.nonEmpty) s"**Usunięto:** ${toRemove.mkString(", ")}" else ""
            List(addText, removeText).filter(_.nonEmpty).mkString("\n")
          }
          
          val successEmbed = new EmbedBuilder()
            .setTitle("✅ Zaktualizowano Statystyki Zaawansowane")
            .setDescription(changesText)
            .setColor(new Color(0, 255, 0))
            .build()
          
          event.getHook.sendMessageEmbeds(successEmbed).queue()
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Statystyki serwera nie są włączone!")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error updating advanced stats for guild ${guild.getId}", exception)
        val errorEmbed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd: ${exception.getMessage}")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }

  /**
   * Obsługa komendy /serverstats list
   */
  private def handleList(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    
    val publicStatsText = publicStats.map { case (name, stat) =>
      s"${stat.icon} `$name` - ${stat.channelName(guild)}"
    }.mkString("\n")
    
    val advancedStatsText = advancedStats.map { case (name, stat) =>
      s"${stat.icon} `$name` - ${stat.channelName(guild)}"
    }.mkString("\n")
    
    val adminStatsText = adminStats.map { case (name, stat) =>
      s"${stat.icon} `$name` - ${stat.channelName(guild)}"
    }.mkString("\n")
    
    val currentConfig = getServerStatsConfig(guild) match {
      case Some(config) =>
        val enabled = config.enabledStats.map(s => s"`$s`").mkString(", ")
        val advanced = config.advancedStats.map(s => s"`$s`").mkString(", ")
        val admin = config.adminStats.map(s => s"`$s`").mkString(", ")
        s"\n\n**🟢 Włączone Publiczne:**\n$enabled\n\n" +
        s"**🔧 Włączone Zaawansowane:**\n${if (advanced.isEmpty) "*brak*" else advanced}\n\n" +
        s"**👑 Włączone Adminowe:**\n${if (admin.isEmpty) "*brak*" else admin}"
      case None =>
        "\n\n*Statystyki nie są włączone na tym serwerze*"
    }
    
    val embed = new EmbedBuilder()
      .setTitle("📊 Dostępne Statystyki")
      .setDescription(
        s"**📝 Statystyki Publiczne (podstawowe):**\n$publicStatsText\n\n" +
        s"**🔧 Statystyki Zaawansowane (wymagają ręcznego włączenia):**\n$advancedStatsText\n\n" +
        s"**👑 Statystyki Adminowe:**\n$adminStatsText" +
        currentConfig
      )
      .setColor(new Color(255, 165, 0))
      .setFooter("Użyj /serverstats configure|advanced|admin aby zarządzać", null)
      .build()
    
    event.getHook.sendMessageEmbeds(embed).queue()
  }

  /**
   * Tworzy domyślne kanały statystyk
   */
  private def createDefaultStatChannels(guild: Guild, categoryId: String): Map[String, String] = {
    val category = guild.getCategoryById(categoryId)
    var channelIds = Map[String, String]()
    
    defaultPublicStats.foreach { statName =>
      val stat = publicStats(statName)
      val channel = category.createVoiceChannel(stat.channelName(guild)).complete()
      
      // Zablokuj dołączanie
      channel.getManager
        .putRolePermissionOverride(
          guild.getPublicRole.getIdLong,
          0L,
          Permission.VOICE_CONNECT.getRawValue
        )
        .complete()
      
      channelIds += (statName -> channel.getId)
    }
    
    channelIds
  }

  /**
   * Aktualizuje wszystkie kanały statystyk
   */
  private def updateAllStatChannels(guild: Guild): Unit = {
    Try {
      getServerStatsConfig(guild) match {
        case Some(config) =>
          // Aktualizuj statystyki publiczne
          config.enabledStats.foreach { statName =>
            publicStats.get(statName).foreach { stat =>
              config.channelIds.get(statName).foreach { channelId =>
                Option(guild.getVoiceChannelById(channelId)).foreach { channel =>
                  val newName = stat.channelName(guild)
                  if (channel.getName != newName) {
                    channel.getManager.setName(newName).queue(
                      _ => logger.debug(s"Updated stat $statName for guild ${guild.getId}"),
                      error => logger.error(s"Failed to update stat $statName for guild ${guild.getId}", error)
                    )
                  }
                }
              }
            }
          }
          
          // Aktualizuj statystyki zaawansowane
          config.advancedStats.foreach { statName =>
            advancedStats.get(statName).foreach { stat =>
              config.channelIds.get(statName).foreach { channelId =>
                Option(guild.getVoiceChannelById(channelId)).foreach { channel =>
                  val newName = stat.channelName(guild)
                  if (channel.getName != newName) {
                    channel.getManager.setName(newName).queue(
                      _ => logger.debug(s"Updated advanced stat $statName for guild ${guild.getId}"),
                      error => logger.error(s"Failed to update advanced stat $statName for guild ${guild.getId}", error)
                    )
                  }
                }
              }
            }
          }
          
          // Aktualizuj statystyki adminowe
          config.adminStats.foreach { statName =>
            adminStats.get(statName).foreach { stat =>
              config.channelIds.get(statName).foreach { channelId =>
                Option(guild.getVoiceChannelById(channelId)).foreach { channel =>
                  val newName = stat.channelName(guild)
                  if (channel.getName != newName) {
                    channel.getManager.setName(newName).queue(
                      _ => logger.debug(s"Updated admin stat $statName for guild ${guild.getId}"),
                      error => logger.error(s"Failed to update admin stat $statName for guild ${guild.getId}", error)
                    )
                  }
                }
              }
            }
          }
          
        case None => // Statystyki nie są włączone
      }
    } match {
      case Success(_) => // Sukces
      case Failure(exception) =>
        logger.error(s"Error updating stat channels for guild ${guild.getId}", exception)
    }
  }

  // Case class dla konfiguracji (dodano pole advancedStats)
  case class ServerStatsConfig(
    categoryId: String,
    enabledStats: List[String],
    advancedStats: List[String],
    adminStats: List[String],
    channelIds: Map[String, String]
  )

  /**
   * Sprawdza czy statystyki serwera są włączone
   */
  private def isServerStatsEnabled(guild: Guild): Boolean = {
    getServerStatsConfig(guild).isDefined
  }

  /**
   * Pobiera konfigurację statystyk serwera
   */
  private def getServerStatsConfig(guild: Guild): Option[ServerStatsConfig] = {
    Try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      
      // Sprawdź czy tabela istnieje
      val tableExists = statement.executeQuery(
        "SELECT EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'server_stats_config')"
      )
      
      if (!tableExists.next() || !tableExists.getBoolean(1)) {
        statement.close()
        conn.close()
        return None
      }
      
      val result = statement.executeQuery("SELECT * FROM server_stats_config LIMIT 1")
      
      val config = if (result.next()) {
        val categoryId = result.getString("category_id")
        val enabledStatsJson = result.getString("enabled_stats")
        val advancedStatsJson = result.getString("advanced_stats")
        val adminStatsJson = result.getString("admin_stats")
        val channelIdsJson = result.getString("channel_ids")
        
        // Parse JSON (prosty parsing, możesz użyć biblioteki JSON)
        val enabledStats = parseJsonArray(enabledStatsJson)
        val advancedStats = parseJsonArray(advancedStatsJson)
        val adminStats = parseJsonArray(adminStatsJson)
        val channelIds = parseJsonMap(channelIdsJson)
        
        Some(ServerStatsConfig(categoryId, enabledStats, advancedStats, adminStats, channelIds))
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
   * Zapisuje konfigurację statystyk serwera
   */
  private def saveServerStatsConfig(guild: Guild, categoryId: String, enabledStats: List[String], advancedStats: List[String], adminStats: List[String]): Unit = {
    val channelIds = createDefaultStatChannels(guild, categoryId)
    updateServerStatsConfig(guild, categoryId, enabledStats, advancedStats, adminStats, channelIds)
  }

  /**
   * Aktualizuje konfigurację statystyk serwera
   */
  private def updateServerStatsConfig(guild: Guild, categoryId: String, enabledStats: List[String], advancedStats: List[String], adminStats: List[String], channelIds: Map[String, String]): Unit = {
    val conn = getConnection(guild)
    val statement = conn.createStatement()
    
    // Utwórz tabelę jeśli nie istnieje
    statement.execute(
      """CREATE TABLE IF NOT EXISTS server_stats_config (
        |  id SERIAL PRIMARY KEY,
        |  category_id VARCHAR(255) NOT NULL,
        |  enabled_stats TEXT NOT NULL,
        |  advanced_stats TEXT NOT NULL,
        |  admin_stats TEXT NOT NULL,
        |  channel_ids TEXT NOT NULL,
        |  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        |)""".stripMargin
    )
    
    // Usuń starą konfigurację
    statement.execute("DELETE FROM server_stats_config")
    
    // Konwertuj listy i mapy do JSON (prosty format)
    val enabledStatsJson = if (enabledStats.isEmpty) "[]" else enabledStats.mkString("[\"", "\",\"", "\"]")
    val advancedStatsJson = if (advancedStats.isEmpty) "[]" else advancedStats.mkString("[\"", "\",\"", "\"]")
    val adminStatsJson = if (adminStats.isEmpty) "[]" else adminStats.mkString("[\"", "\",\"", "\"]")
    val channelIdsJson = if (channelIds.isEmpty) "{}" else channelIds.map { case (k, v) => s"\"$k\":\"$v\"" }.mkString("{", ",", "}")
    
    // Wstaw nową konfigurację
    val insertStatement = conn.prepareStatement(
      "INSERT INTO server_stats_config (category_id, enabled_stats, advanced_stats, admin_stats, channel_ids) VALUES (?, ?, ?, ?, ?)"
    )
    insertStatement.setString(1, categoryId)
    insertStatement.setString(2, enabledStatsJson)
    insertStatement.setString(3, advancedStatsJson)
    insertStatement.setString(4, adminStatsJson)
    insertStatement.setString(5, channelIdsJson)
    insertStatement.executeUpdate()
    
    insertStatement.close()
    statement.close()
    conn.close()
  }

  /**
   * Usuwa konfigurację statystyk serwera
   */
  private def deleteServerStatsConfig(guild: Guild): Unit = {
    Try {
      val conn = getConnection(guild)
      val statement = conn.createStatement()
      statement.execute("DELETE FROM server_stats_config")
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
   * Pomocnicze funkcje do parsowania JSON (prosty format)
   */
  private def parseJsonArray(json: String): List[String] = {
    if (json == "[]") List()
    else json.stripPrefix("[\"").stripSuffix("\"]").split("\",\"").toList
  }

  private def parseJsonMap(json: String): Map[String, String] = {
    if (json == "{}") Map()
    else {
      json.stripPrefix("{").stripSuffix("}").split(",").map { pair =>
        val Array(key, value) = pair.split(":")
        key.stripPrefix("\"").stripSuffix("\"") -> value.stripPrefix("\"").stripSuffix("\"")
      }.toMap
    }
  }

  /**
   * Pobiera połączenie do bazy danych
   */
  private def getConnection(guild: Guild): Connection = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_${guild.getId}"
    val username = "postgres"
    val password = Config.postgresPassword
    DriverManager.getConnection(url, username, password)
  }
}