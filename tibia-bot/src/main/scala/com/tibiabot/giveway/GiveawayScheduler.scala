package com.tibiabot.giveaway

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.interactions.components.ActionRow

import java.time.Instant
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.Try

/**
 * Scheduler sprawdzający co 5 sekund czy jakiś giveaway się skończył.
 * Automatycznie losuje zwycięzców i edytuje wiadomość na Discord.
 */
class GiveawayScheduler(manager: GiveawayManager, jda: JDA, actorSystem: ActorSystem)
                       (implicit ec: ExecutionContext) extends StrictLogging {

  def start(): Unit = {
    actorSystem.scheduler.scheduleWithFixedDelay(10.seconds, 5.seconds) { () =>
      try {
        val expired = manager.getExpiredGiveaways()
        expired.foreach(g => endGiveaway(g))
      } catch {
        case e: Exception =>
          logger.error(s"Błąd w schedulerze giveaway: ${e.getMessage}", e)
      }
    }
    logger.info("✅ Scheduler giveaway uruchomiony (interwał: 5s)")
  }

  /** Kończy giveaway: losuje zwycięzców, edytuje embed, wysyła wiadomość z wynikami. */
  def endGiveaway(g: Giveaway): Unit = {
    logger.info(s"🎉 Kończę giveaway ${g.messageId} – '${g.prize}' na guild ${g.guildId}")

    // Oznacz jako zakończony w DB zanim cokolwiek zrobisz na Discord (guard przed podwójnym wyzwalaniem)
    manager.markEnded(g.messageId)

    val entries   = manager.getEntries(g.messageId)
    val winners   = manager.pickWinners(entries, g.winners)
    val numEntries = entries.size

    Try {
      val guild = jda.getGuildById(g.guildId)
      if (guild == null) {
        logger.warn(s"Guild ${g.guildId} nie znaleziony dla giveaway ${g.messageId}")
        return
      }

      val channel = Option(guild.getTextChannelById(g.channelId))
        .orElse(Option(guild.getThreadChannelById(g.channelId)))

      channel match {
        case None =>
          logger.warn(s"Kanał ${g.channelId} nie znaleziony dla giveaway ${g.messageId}")

        case Some(ch) =>
          val msgChannel = ch.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel]

          // Edytuj oryginalną wiadomość – zmień embed na "zakończony" i usuń przycisk
          val endedEmbed = manager.buildEndedEmbed(g, numEntries, winners).build()
          msgChannel.editMessageEmbedsById(g.messageId, endedEmbed)
            .setComponents()  // usuwa przyciski
            .queue(
              _ => logger.debug(s"Zaktualizowano wiadomość giveaway ${g.messageId}"),
              err => logger.warn(s"Nie można edytować wiadomości giveaway ${g.messageId}: ${err.getMessage}")
            )

          // Wyślij wiadomość z wynikami
          val resultMsg = buildResultMessage(g, winners)
          msgChannel.sendMessage(resultMsg)
            .setMessageReference(g.messageId)
            .queue(
              _ => logger.info(s"✅ Giveaway ${g.messageId} zakończony, zwycięzcy: ${winners.map(_._2).mkString(", ")}"),
              err => logger.warn(s"Nie można wysłać wyniku giveaway ${g.messageId}: ${err.getMessage}")
            )
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Błąd podczas kończenia giveaway ${g.messageId}: ${e.getMessage}", e)
    }
  }

  private def buildResultMessage(g: Giveaway, winners: List[(Long, String)]): String = {
    if (winners.isEmpty) {
      s"🎉 **Giveaway zakończony!**\nNiestety nikt nie wziął udziału w losowaniu **${g.prize}**. 😔"
    } else {
      val mentions = manager.formatWinnerMention(winners)
      s"🎊 Gratulacje $mentions! Wygrałeś/wygrałaś **${g.prize}**! 🎉\nSkontaktuj się z <@${g.userId}> po nagrodę."
    }
  }
}
