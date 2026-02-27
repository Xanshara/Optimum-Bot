package com.tibiabot.postac_info

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import com.tibiabot.tibiadata.TibiaDataClient
import com.tibiabot.Config
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}
import java.awt.Color
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.net.URLEncoder
import java.time.{ZonedDateTime, LocalDateTime}
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import scala.jdk.CollectionConverters._

class CharacterInfoListener(tibiaDataClient: TibiaDataClient)(implicit ec: ExecutionContext) 
  extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "postac_info") {
      event.deferReply(false).queue()
      handleCharacterInfo(event)
    }
  }

  private def handleCharacterInfo(event: SlashCommandInteractionEvent): Unit = {
    val characterNameRaw = event.getOption("nick").getAsString
    // Kapitalizuj pierwszą literę każdego słowa (np. "exxon" → "Exxon")
    val characterName = characterNameRaw.split(" ").map(_.capitalize).mkString(" ")
    
    logger.info(s"Fetching character info for: $characterName (original: $characterNameRaw)")

    tibiaDataClient.getCharacter(characterName).onComplete {
      case Success(Right(charResponse)) =>
        val character = charResponse.character.character
        
        // Określ emoji na podstawie profesji
        val emoji = getVocationEmoji(character.vocation)
        
        // Sformatuj nick z emoji: ⚔️ Draya ⚔️
        val formattedName = s"$emoji ${character.name} $emoji"
        val characterUrl = s"https://www.tibia.com/community/?name=${URLEncoder.encode(character.name, "UTF-8")}"
        
        val embed = new EmbedBuilder()
          .setAuthor(formattedName, characterUrl, null)
          .setColor(getVocationColor(character.vocation))
          .setThumbnail("https://static.tibia.com/images/global/header/tibia-logo.gif")
          
        // Pierwszy wiersz - World, Level, Vocation
        var worldField = "**World**\n"
        worldField += character.world
        embed.addField(worldField, "", true)
        
        var levelField = "**Level**\n"
        // NAPRAWIONE - usuń część dziesiętną - zawsze wyświetla jako integer (np. 615 zamiast 615.0)
        val levelInt = try {
          character.level.toString.toDouble.toInt
        } catch {
          case _: Exception => 0
        }
        levelField += levelInt.toString
        embed.addField(levelField, "", true)
        
        var vocationField = "**Vocation**\n"
        vocationField += character.vocation
        embed.addField(vocationField, "", true)
        
        // Drugi wiersz - Guild, Last Login, Residence
        var guildField = "**Guild**\n"
        character.guild match {
          case Some(guild) => guildField += guild.name
          case None => guildField += "None"
        }
        embed.addField(guildField, "", true)
        
        var lastLoginField = "**Last Login**\n"
        character.last_login match {
          case Some(loginTime) =>
            lastLoginField += formatDateTime(loginTime)
          case None =>
            lastLoginField += "Unknown"
        }
        embed.addField(lastLoginField, "", true)
        
        var residenceField = "**Residence**\n"
        residenceField += character.residence
        embed.addField(residenceField, "", true)
        
        // Former Names (jeśli istnieją)
        character.former_names match {
          case Some(names) if names.nonEmpty =>
            var formerNamesField = "**Former Names**\n"
            formerNamesField += names.mkString(", ")
            embed.addField(formerNamesField, "", false)
          case _ => // Nie dodawaj pola jeśli brak former names
        }
        
        // Footer - "Optimum Bot - Player checked: • Dziś o 09:50"
        val currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        embed.setFooter(s"Optimum Bot - Player checked: • Dziś o $currentTime", Config.webHookAvatar)
        
        // Przyciski z linkami
        val buttons = scala.collection.mutable.ListBuffer[net.dv8tion.jda.api.interactions.components.buttons.Button]()
        
        // Button do profilu postaci
        buttons += net.dv8tion.jda.api.interactions.components.buttons.Button.link(
          characterUrl,
          "View Character Profile"
        )
        
        // Button do world
        val worldUrl = s"https://www.tibia.com/community/?subtopic=worlds&world=${character.world}"
        buttons += net.dv8tion.jda.api.interactions.components.buttons.Button.link(
          worldUrl,
          s"World: ${character.world}"
        )
        
        // Button do guild (jeśli istnieje)
        character.guild.foreach { guild =>
          val guildNameEncoded = URLEncoder.encode(guild.name, "UTF-8")
          val guildUrl = s"https://www.tibia.com/community/?subtopic=guilds&page=view&GuildName=$guildNameEncoded"
          buttons += net.dv8tion.jda.api.interactions.components.buttons.Button.link(
            guildUrl,
            s"Guild: ${guild.name}"
          )
        }
        
        event.getHook.sendMessageEmbeds(embed.build())
          .addActionRow(buttons.asJava)
          .queue()
        
      case Success(Left(error)) =>
        logger.warn(s"Character not found: $characterName - Error: $error")
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Nie znaleziono postaci o nazwie **$characterName**\n\nSprawdź czy nazwa jest poprawna (wielkie/małe litery).")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(embed).queue()
        
      case Failure(exception) =>
        logger.error(s"Exception fetching character $characterName: ${exception.getMessage}", exception)
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd podczas pobierania informacji o postaci\n\n**Błąd:** ${exception.getMessage}")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  /**
   * Zwraca emoji dla danej profesji
   * Knight: 🛡️
   * Paladin: 🏹
   * Sorcerer: 🔥
   * Druid: ⚔️
   * Monk: 👊
   * None: ⭐
   */
  private def getVocationEmoji(vocation: String): String = {
    val voc = vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => "🛡️"
      case "paladin" => "🏹"
      case "sorcerer" => "🔥"
      case "druid" => "⚔️"
      case "monk" => "👊"
      case _ => "⭐" // None (postać bez profesji)
    }
  }

  /**
   * Zwraca kolor dla danej profesji (kolor paska bocznego embed)
   */
  private def getVocationColor(vocation: String): Color = {
    val voc = vocation.toLowerCase.split(' ').last
    voc match {
      case "knight" => new Color(102, 153, 204) // Niebieski
      case "paladin" => new Color(102, 204, 102) // Zielony
      case "sorcerer" => new Color(204, 102, 102) // Czerwony
      case "druid" => new Color(102, 153, 204) // Niebieski
      case "monk" => new Color(204, 153, 102) // Pomarańczowy/brązowy
      case _ => new Color(128, 128, 128) // Szary dla None
    }
  }
  
  /**
   * Formatuje datę z ISO 8601 na czytelny format
   * "2026-01-23T17:04:38Z" → "Jan 23 2026, 17:04"
   */
  private def formatDateTime(dateTimeString: String): String = {
    try {
      val zonedDateTime = ZonedDateTime.parse(dateTimeString)
      val formatter = DateTimeFormatter.ofPattern("MMM dd yyyy, HH:mm", Locale.ENGLISH)
      zonedDateTime.format(formatter)
    } catch {
      case _: Exception => dateTimeString // Jeśli parsing się nie uda, zwróć oryginalny string
    }
  }
}