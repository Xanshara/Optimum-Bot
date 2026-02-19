package com.tibiabot.poll

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands

import java.awt.Color

/**
 * Komenda /pollvotes do sprawdzania kto jak zagłosował w ankiecie
 */
class PollVotesCommand(pollManager: PollManager) extends StrictLogging {

  /**
   * Definicja komendy slash
   */
  val command = Commands.slash("pollvotes", "Pokaż szczegóły głosowania w ankiecie (tylko dla adminów)")
    .addOption(OptionType.STRING, "message_id", "ID wiadomości z ankietą", true)

  /**
   * Obsługuje komendę /pollvotes
   */
  def handle(event: SlashCommandInteractionEvent): Unit = {
    logger.info(s"📊 /pollvotes command called by ${event.getUser.getAsTag}")
    event.deferReply(true).queue()

    try {
      // Sprawdź uprawnienia - tylko MANAGE_SERVER lub ADMINISTRATOR
      val member = event.getMember
      if (member == null || 
          !(member.hasPermission(Permission.MANAGE_SERVER) || 
            member.hasPermission(Permission.ADMINISTRATOR))) {
        logger.warn(s"❌ Unauthorized pollvotes attempt by ${event.getUser.getAsTag}")
        event.getHook.editOriginal(s"${Config.noEmoji} Tylko administratorzy mogą zobaczyć szczegóły głosowania!")
          .queue()
        return
      }

      val messageId = event.getOption("message_id").getAsString
      val guildId = event.getGuild.getId
      val pollId = s"${guildId}_$messageId"

      // Pobierz ankietę
      pollManager.getPoll(pollId) match {
        case Some(poll) =>
          val startTime = System.currentTimeMillis()
          logger.info(s"✅ Found poll $pollId, fetching votes...")
          
          // Pobierz szczegółowe głosy
          val detailedVotes = pollManager.getDetailedVotes(pollId)
          val afterVotesTime = System.currentTimeMillis()
          logger.info(s"📊 Retrieved ${detailedVotes.size} votes for poll $pollId (took ${afterVotesTime - startTime}ms)")

          if (detailedVotes.isEmpty) {
            event.getHook.editOriginal("📊 Nikt jeszcze nie zagłosował w tej ankiecie.")
              .queue()
            return
          }

          // Przygotuj embed z głosami
          val votesEmbed = new EmbedBuilder()
            .setTitle(s"👁️ Szczegóły głosowania")
            .setDescription(s"**Pytanie:** ${poll.question}\n\n")
            .setColor(new Color(59, 130, 246))

          // Pogrupuj głosy dla każdego użytkownika
          val resolveStartTime = System.currentTimeMillis()
          logger.info(s"🔍 Resolving ${detailedVotes.size} usernames...")
          val guild = event.getGuild
          val jda = event.getJDA
          
          val votesList = detailedVotes.map { case (userId, optionIndices) =>
            // Użyj TYLKO cache (bez API calls) - szybko!
            val username = try {
              // Najpierw spróbuj member (nick serwerowy)
              val member = guild.getMemberById(userId)
              if (member != null) {
                member.getEffectiveName  // Nick serwerowy lub nazwa użytkownika
              } else {
                // Fallback: user z cache (globalna nazwa)
                val user = jda.getUserById(userId)
                if (user != null) {
                  user.getName  // Nazwa bez #0000
                } else {
                  // Jeśli nie ma w cache - użyj mention (Discord pokaże sam)
                  s"<@$userId>"
                }
              }
            } catch {
              case e: Exception =>
                logger.warn(s"⚠️ Failed to resolve username for $userId: ${e.getMessage}")
                s"<@$userId>"  // Fallback na mention
            }
            
            val votes = optionIndices.map { idx =>
              if (idx < poll.options.size) {
                val emoji = getEmojiForOption(idx)
                s"$emoji ${poll.options(idx)}"
              } else {
                s"Opcja ${idx + 1}"
              }
            }.mkString(", ")

            s"**${username}:** $votes"
          }.mkString("\n")
          
          val resolveEndTime = System.currentTimeMillis()
          logger.info(s"✅ Resolved all usernames (took ${resolveEndTime - resolveStartTime}ms), preparing embed...")

          votesEmbed.addField("Głosy:", votesList, false)
          votesEmbed.setFooter(s"Łącznie głosów: ${detailedVotes.size}")

          event.getHook.editOriginalEmbeds(votesEmbed.build()).queue()
          val totalTime = System.currentTimeMillis() - startTime
          logger.info(s"✅ Sent vote details for poll $pollId to ${event.getUser.getAsTag} (total time: ${totalTime}ms)")

        case None =>
          logger.warn(s"❌ Poll not found: $pollId")
          event.getHook.editOriginal(s"${Config.noEmoji} Nie znaleziono ankiety o ID: `$pollId`\n\nUpewnij się że:\n- Podałeś prawidłowy ID wiadomości\n- Ankieta została utworzona na tym serwerze")
            .queue()
      }

    } catch {
      case e: Exception =>
        logger.error("Error handling /pollvotes command", e)
        event.getHook.editOriginal(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
          .queue()
    }
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
