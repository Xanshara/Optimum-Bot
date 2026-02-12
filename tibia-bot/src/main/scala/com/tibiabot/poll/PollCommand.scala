package com.tibiabot.poll

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.ActionRow

import java.awt.Color
import java.time.ZonedDateTime
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * Komenda /poll z interaktywnym interfejsem
 */
class PollCommand(pollManager: PollManager) extends StrictLogging {

  // Przechowywanie stanu tworzenia ankiety (userId -> stan)
  private val creationStates = mutable.Map[String, PollCreationState]()

  /**
   * Definicja komendy slash (bez parametrów)
   */
  val command = Commands.slash("poll", "Tworzy ankietę - interaktywne menu")

  /**
   * Obsługuje komendę /poll - pokazuje menu konfiguracji
   */
  def handle(event: SlashCommandInteractionEvent): Unit = {
    // Defer reply jako ephemeral (tylko dla użytkownika)
    event.deferReply(true).queue()

    try {
      val userId = event.getUser.getId
      val guildId = event.getGuild.getId
      val channelId = event.getChannel.getId

      // Zapisz początkowy stan
      val state = PollCreationState(
        userId = userId,
        guildId = guildId,
        channelId = channelId,
        pollType = None,
        optionCount = None,
        timestamp = ZonedDateTime.now()
      )
      creationStates.put(userId, state)

      // Czyść stare stany (starsze niż 10 minut)
      cleanOldStates()

      // Utwórz embed z instrukcjami
      val embed = new EmbedBuilder()
        .setTitle("📊 Tworzenie nowej ankiety")
        .setDescription(
          "**Krok 1:** Wybierz typ ankiety i liczbę opcji\n\n" +
          "**Typ ankiety:**\n" +
          "• **Jednokrotny wybór** - każdy może wybrać tylko jedną opcję\n" +
          "• **Wielokrotny wybór** - można zaznaczyć kilka opcji\n\n" +
          "**Liczba opcji:** 2-10"
        )
        .setColor(new Color(59, 130, 246))
        .build()

      // SelectMenu dla typu ankiety
      val typeMenu = StringSelectMenu.create(s"poll_type:$userId")
        .setPlaceholder("Wybierz typ ankiety")
        .addOption("📊 Jednokrotny wybór", "single", "Każdy wybiera tylko jedną opcję")
        .addOption("📊🔢 Wielokrotny wybór", "multiple", "Można zaznaczyć kilka opcji")
        .build()

      // SelectMenu dla liczby opcji
      val countMenu = StringSelectMenu.create(s"poll_count:$userId")
        .setPlaceholder("Wybierz liczbę opcji")
        .addOption("2 opcje", "2")
        .addOption("3 opcje", "3")
        .addOption("4 opcje", "4")
        .addOption("5 opcji", "5")
        .addOption("6 opcji", "6")
        .addOption("7 opcji", "7")
        .addOption("8 opcji", "8")
        .addOption("9 opcji", "9")
        .addOption("10 opcji", "10", "Maximum - 10 odpowiedzi")
        .build()

      // Button do przejścia dalej
      val nextButton = Button.primary(s"poll_next:$userId", "Dalej →")
        .asDisabled() // Zablokowany dopóki nie wybrano obu opcji

      event.getHook.sendMessageEmbeds(embed)
        .addActionRow(typeMenu)
        .addActionRow(countMenu)
        .addActionRow(nextButton)
        .queue()

      logger.info(s"Poll creation started by user $userId")

    } catch {
      case e: Exception =>
        logger.error("Error handling /poll command", e)
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
          .setColor(new Color(239, 68, 68))
          .build()
        event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
    }
  }

  /**
   * Pobiera stan tworzenia ankiety dla użytkownika
   */
  def getCreationState(userId: String): Option[PollCreationState] = {
    creationStates.get(userId)
  }

  /**
   * Aktualizuje stan tworzenia ankiety
   */
  def updateCreationState(userId: String, state: PollCreationState): Unit = {
    creationStates.put(userId, state)
  }

  /**
   * Usuwa stan tworzenia ankiety
   */
  def removeCreationState(userId: String): Unit = {
    creationStates.remove(userId)
  }

  /**
   * Czyści stare stany (starsze niż 10 minut)
   */
  private def cleanOldStates(): Unit = {
    val now = ZonedDateTime.now()
    val toRemove = creationStates.filter { case (_, state) =>
      java.time.Duration.between(state.timestamp, now).toMinutes > 10
    }.keys.toList

    toRemove.foreach(creationStates.remove)
    if (toRemove.nonEmpty) {
      logger.info(s"Cleaned ${toRemove.size} old poll creation states")
    }
  }
}
