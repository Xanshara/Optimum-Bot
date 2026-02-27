package com.tibiabot.poll

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands

import java.awt.Color
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Komenda /polledit do edycji czasu trwania ankiety
 */
class PollEditCommand(pollManager: PollManager) extends StrictLogging {

  /**
   * Definicja komendy slash
   */
  val command = Commands.slash("polledit", "Zmień czas trwania ankiety (tylko dla adminów)")
    .addOption(OptionType.STRING, "message_id", "ID wiadomości z ankietą", true)
    .addOption(OptionType.STRING, "new_duration", "Nowy czas (np. 60, 2h, 7d, 30d)", true)

  /**
   * Parsuje czas z formatu: "60", "60m", "2h", "3d" na minuty
   */
  private def parseDuration(input: String): Option[Int] = {
    try {
      val trimmed = input.trim.toLowerCase
      
      if (trimmed.endsWith("m")) {
        Some(trimmed.dropRight(1).toInt)
      } else if (trimmed.endsWith("h")) {
        Some(trimmed.dropRight(1).toInt * 60)
      } else if (trimmed.endsWith("d")) {
        Some(trimmed.dropRight(1).toInt * 24 * 60)
      } else {
        Some(trimmed.toInt)
      }
    } catch {
      case _: Exception => None
    }
  }

  /**
   * Formatuje czas do wyświetlenia
   */
  private def formatDuration(minutes: Int): String = {
    if (minutes < 60) {
      s"$minutes minut"
    } else if (minutes < 1440) {
      s"${minutes / 60}h"
    } else {
      s"${minutes / 1440}d"
    }
  }

  /**
   * Obsługuje komendę /polledit
   */
  def handle(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()

    try {
      // Sprawdź uprawnienia - tylko MANAGE_SERVER lub ADMINISTRATOR
      val member = event.getMember
      if (member == null || 
          !(member.hasPermission(Permission.MANAGE_SERVER) || 
            member.hasPermission(Permission.ADMINISTRATOR))) {
        event.getHook.editOriginal(s"${Config.noEmoji} Tylko administratorzy mogą edytować ankiety!")
          .queue()
        return
      }

      val messageId = event.getOption("message_id").getAsString
      val newDurationStr = event.getOption("new_duration").getAsString
      val guildId = event.getGuild.getId
      val pollId = s"${guildId}_$messageId"

      // Parsuj nowy czas
      val newDurationMinutes = parseDuration(newDurationStr) match {
        case Some(minutes) => minutes
        case None =>
          event.getHook.editOriginal(s"${Config.noEmoji} Nieprawidłowy format czasu! Użyj np. 60, 2h, 7d, 30d")
            .queue()
          return
      }

      // Walidacja - maksymalnie 60 dni (86400 minut)
      if (newDurationMinutes < 1 || newDurationMinutes > 86400) {
        event.getHook.editOriginal(s"${Config.noEmoji} Czas trwania musi być między 1 minutą a 60 dniami!")
          .queue()
        return
      }

      // Pobierz ankietę
      pollManager.getPoll(pollId) match {
        case Some(poll) =>
          // Sprawdź czy ankieta jest aktywna
          if (!poll.isActive) {
            event.getHook.editOriginal(s"${Config.noEmoji} Ta ankieta jest już nieaktywna!")
              .queue()
            return
          }

          // Oblicz nowy czas zakończenia: created_at + new_duration
          val newEndsAt = poll.createdAt.plusMinutes(newDurationMinutes.toLong)
          val now = ZonedDateTime.now()

          // Sprawdź czy nowy czas nie jest w przeszłości
          if (newEndsAt.isBefore(now)) {
            event.getHook.editOriginal(s"${Config.noEmoji} Nowy czas zakończenia ($newEndsAt) jest w przeszłości! Ankieta została utworzona ${poll.createdAt}.")
              .queue()
            return
          }

          // Aktualizuj w bazie
          pollManager.updatePollEndTime(pollId, newEndsAt) match {
            case scala.util.Success(_) =>
              logger.info(s"✅ Database updated: poll $pollId end time changed to $newEndsAt")
              
              // WAŻNE: Pobierz poll PONOWNIE z bazy (ze zaktualizowanym czasem)
              val updatedPoll = pollManager.getPoll(pollId).getOrElse(poll.copy(endsAt = newEndsAt))
              
              // Aktualizuj wiadomość Discord
              val guild = event.getJDA.getGuildById(poll.guildId)
              if (guild != null) {
                val guildChannel = guild.getGuildChannelById(poll.channelId)
                if (guildChannel != null) {
                  val messageChannel = guildChannel match {
                    case tc: net.dv8tion.jda.api.entities.channel.concrete.TextChannel => tc
                    case tc: net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel => tc
                    case _ => null
                  }

                  if (messageChannel != null) {
                    messageChannel.retrieveMessageById(poll.messageId).queue(
                      message => {
                        logger.info(s"📝 Updating poll embed for $pollId - OLD: ${poll.endsAt}, NEW: ${updatedPoll.endsAt}")
                        
                        // Pobierz aktualne wyniki
                        val results = pollManager.getResults(pollId)
                        val totalVoters = pollManager.getTotalVoters(pollId)

                        // Zaktualizuj embed z nowym czasem (używaj updatedPoll!)
                        val updatedEmbed = createUpdatedEmbed(updatedPoll, results, totalVoters)
                        
                        logger.info(s"📤 Sending updated embed with footer: ${updatedEmbed.getFooter.getText}")
                        
                        // WAŻNE: Zachowaj istniejące komponenty (menu głosowania)
                        val existingComponents = message.getActionRows
                        
                        message.editMessageEmbeds(updatedEmbed)
                          .setComponents(existingComponents)
                          .queue(
                            _ => logger.info(s"✅ Updated poll embed for $pollId with new end time: ${updatedPoll.endsAt}"),
                            error => logger.error(s"❌ Failed to update poll embed for $pollId", error)
                          )
                      },
                      error => logger.error(s"❌ Failed to retrieve message for poll $pollId", error)
                    )
                  } else {
                    logger.warn(s"⚠️ Message channel is null for poll $pollId")
                  }
                }
              }

              val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
              val formattedEndsAt = newEndsAt.format(formatter)
              
              event.getHook.editOriginal(
                s"✅ Czas trwania ankiety został zmieniony!\n\n" +
                s"**Nowy czas:** ${formatDuration(newDurationMinutes)}\n" +
                s"**Koniec:** $formattedEndsAt\n" +
                s"*(liczony od momentu utworzenia ankiety: ${poll.createdAt.format(formatter)})*"
              ).queue()

              logger.info(s"✅ Poll $pollId end time updated from ${poll.endsAt} to $newEndsAt by ${event.getUser.getAsTag}")

            case scala.util.Failure(e) =>
              logger.error(s"Error updating poll end time $pollId", e)
              event.getHook.editOriginal(s"${Config.noEmoji} Błąd podczas aktualizacji ankiety: ${e.getMessage}")
                .queue()
          }

        case None =>
          event.getHook.editOriginal(s"${Config.noEmoji} Nie znaleziono ankiety o ID: `$pollId`\n\nUpewnij się że:\n- Podałeś prawidłowy ID wiadomości\n- Ankieta została utworzona na tym serwerze")
            .queue()
      }

    } catch {
      case e: Exception =>
        logger.error("Error handling /polledit command", e)
        event.getHook.editOriginal(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
          .queue()
    }
  }

  /**
   * Tworzy zaktualizowany embed dla ankiety
   */
  private def createUpdatedEmbed(poll: Poll, results: List[PollResult], totalVoters: Int): net.dv8tion.jda.api.entities.MessageEmbed = {
    val embedBuilder = new EmbedBuilder()
      .setTitle(s"📊 ${poll.question}")
      .setColor(new Color(59, 130, 246))

    // Dodaj opcje z wynikami
    poll.options.zipWithIndex.foreach { case (option, idx) =>
      val emoji = getEmojiForOption(idx)
      val result = results.find(_.optionIndex == idx)
      val votes = result.map(_.voteCount).getOrElse(0)
      val percentage = if (totalVoters > 0) (votes * 100.0 / totalVoters).toInt else 0
      
      val bar = if (totalVoters > 0) {
        val barLength = (percentage / 10).toInt
        "█" * barLength + "░" * (10 - barLength)
      } else {
        "░" * 10
      }

      embedBuilder.addField(
        s"$emoji $option",
        s"$bar `$percentage%` ($votes głosów)",
        false
      )
    }

    val pollType = if (poll.allowMultiple) "wielokrotnego wyboru" else "jednokrotnego wyboru"
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val formattedEndsAt = poll.endsAt.format(formatter)  // ← Używa poll.endsAt (już zaktualizowany!)

    embedBuilder.setFooter(s"Ankieta $pollType • Koniec: $formattedEndsAt • Głosów: $totalVoters")
    embedBuilder.build()
  }

  /**
   * Zwraca emoji dla opcji na podstawie indeksu
   */
  private def getEmojiForOption(index: Int): String = {
    index match {
      case 0 => "🇦"
      case 1 => "🇧"
      case 2 => "🇨"
      case 3 => "🇩"
      case 4 => "🇪"
      case 5 => "🇫"
      case 6 => "🇬"
      case 7 => "🇭"
      case 8 => "🇮"
      case 9 => "🇯"
      case _ => "❓"
    }
  }
}
