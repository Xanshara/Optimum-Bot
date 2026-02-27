package com.tibiabot.poll

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.component.{StringSelectInteractionEvent, ButtonInteractionEvent}
import net.dv8tion.jda.api.events.interaction.{ModalInteractionEvent}
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal

import java.awt.Color
import java.time.ZonedDateTime
import scala.jdk.CollectionConverters._

/**
 * Obsługuje wszystkie interakcje związane z ankietami
 */
class PollListener(pollManager: PollManager, pollCommand: PollCommand) extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "poll") {
      pollCommand.handle(event)
    }
  }

  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    val componentId = event.getComponentId
    
    if (componentId.startsWith("poll_type:")) {
      handleTypeSelection(event)
    } else if (componentId.startsWith("poll_count:")) {
      handleCountSelection(event)
    } else if (componentId.startsWith("poll_vote:")) {
      handleVote(event)
    }
  }

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId
    
    if (buttonId.startsWith("poll_next:")) {
      handleNextButton(event)
    } else if (buttonId.startsWith("poll_second_modal:")) {
      handleSecondModalButton(event)
    } else if (buttonId.startsWith("poll_third_modal:")) {
      handleThirdModalButton(event)
    } else if (buttonId.startsWith("poll_edit_time:")) {
      handleEditTimeButton(event)
    }
  }

  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    val modalId = event.getModalId
    
    if (modalId.startsWith("poll_create_first:")) {
      handleFirstModalFor6to10(event)
    } else if (modalId.startsWith("poll_create_middle:")) {
      handleMiddleModalFor9to10(event)
    } else if (modalId.startsWith("poll_create_third:")) {
      handleThirdModalFor9to10(event)
    } else if (modalId.startsWith("poll_create_second:")) {
      handleSecondModalFor6to10(event)
    } else if (modalId.startsWith("poll_create:")) {
      handlePollCreation(event)
    } else if (modalId.startsWith("poll_edit_time:")) {
      handleEditTimeModal(event)
    }
  }

  /**
   * Obsługuje kliknięcie buttona "Dalej" po pierwszym modalu - otwiera drugi modal
   */
  private def handleSecondModalButton(event: ButtonInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.question.isDefined && state.firstFiveOptions.isDefined =>
        val optionCount = state.optionCount.get
        val firstFourOptions = state.firstFiveOptions.get
        val remainingCount = optionCount - firstFourOptions.size
        
        // Dla 9-10 opcji: to będzie modal środkowy (2/3)
        // Dla 5-8 opcji: to będzie modal ostatni (2/2)
        val isMiddleModal = optionCount >= 9
        val modalId = if (isMiddleModal) s"poll_create_middle:$userId" else s"poll_create_second:$userId"
        val modalTitle = if (isMiddleModal) "Utwórz ankietę (2/3)" else "Utwórz ankietę (2/2)"
        
        val modalBuilder = Modal.create(modalId, modalTitle)
        
        // Czas trwania (zawsze w drugim modalu)
        modalBuilder.addActionRow(
          TextInput.create("duration", "Czas trwania", TextInputStyle.SHORT)
            .setPlaceholder("np. 60, 2h, 3d (minuty/godziny/dni)")
            .setRequired(false)
            .setMaxLength(10)
            .build()
        )
        
        // Opcje 5-8 (maksymalnie 4 opcje w drugim modalu)
        val maxOptionsInSecondModal = Math.min(remainingCount, 4)
        for (i <- 1 to maxOptionsInSecondModal) {
          val actualIndex = firstFourOptions.size + i
          modalBuilder.addActionRow(
            TextInput.create(s"option$actualIndex", s"Opcja $actualIndex", TextInputStyle.SHORT)
              .setPlaceholder(s"Opcjonalna")
              .setRequired(false)
              .setMaxLength(80)
              .build()
          )
        }
        
        event.replyModal(modalBuilder.build()).queue()
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Parsuje czas z formatu: "60", "60m", "2h", "3d" na minuty
   */
  private def parseDuration(input: String): Option[Int] = {
    try {
      val trimmed = input.trim.toLowerCase
      
      if (trimmed.endsWith("m")) {
        // Minuty: "60m" -> 60
        Some(trimmed.dropRight(1).toInt)
      } else if (trimmed.endsWith("h")) {
        // Godziny: "2h" -> 120
        Some(trimmed.dropRight(1).toInt * 60)
      } else if (trimmed.endsWith("d")) {
        // Dni: "3d" -> 4320
        Some(trimmed.dropRight(1).toInt * 24 * 60)
      } else {
        // Sama liczba: "60" -> 60 (minuty)
        Some(trimmed.toInt)
      }
    } catch {
      case _: Exception => None
    }
  }

  /**
   * Obsługuje wybór typu ankiety
   */
  private def handleTypeSelection(event: StringSelectInteractionEvent): Unit = {
    val userId = event.getUser.getId
    val selected = event.getValues.get(0) // "single" lub "multiple"
    
    pollCommand.getCreationState(userId) match {
      case Some(state) =>
        val updatedState = state.copy(pollType = Some(selected))
        pollCommand.updateCreationState(userId, updatedState)
        
        // Aktualizuj message - odblokuj przycisk jeśli wybrano obie opcje
        updateCreationMessage(event, updatedState)
        
        event.deferEdit().queue()
        
      case None =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje wybór liczby opcji
   */
  private def handleCountSelection(event: StringSelectInteractionEvent): Unit = {
    val userId = event.getUser.getId
    val selected = event.getValues.get(0).toInt // 2, 3, lub 4
    
    pollCommand.getCreationState(userId) match {
      case Some(state) =>
        val updatedState = state.copy(optionCount = Some(selected))
        pollCommand.updateCreationState(userId, updatedState)
        
        // Aktualizuj message - odblokuj przycisk jeśli wybrano obie opcje
        updateCreationMessage(event, updatedState)
        
        event.deferEdit().queue()
        
      case None =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Aktualizuje wiadomość z tworzeniem ankiety
   */
  private def updateCreationMessage(event: StringSelectInteractionEvent, state: PollCreationState): Unit = {
    val userId = state.userId
    
    // Sprawdź czy można odblokować przycisk "Dalej"
    val canProceed = state.pollType.isDefined && state.optionCount.isDefined
    
    // Utwórz komponenty
    val typeMenu = StringSelectMenu.create(s"poll_type:$userId")
      .setPlaceholder(
        state.pollType match {
          case Some("single") => "✓ Jednokrotny wybór"
          case Some("multiple") => "✓ Wielokrotny wybór"
          case _ => "Wybierz typ ankiety"
        }
      )
      .addOption("📊 Jednokrotny wybór", "single", "Każdy wybiera tylko jedną opcję")
      .addOption("📊🔢 Wielokrotny wybór", "multiple", "Można zaznaczyć kilka opcji")
      .setDefaultValues(state.pollType.toList.asJava)
      .build()

    val countMenu = StringSelectMenu.create(s"poll_count:$userId")
      .setPlaceholder(
        state.optionCount match {
          case Some(n) => s"✓ $n opcji"
          case _ => "Wybierz liczbę opcji"
        }
      )
      .addOption("2 opcje", "2")
      .addOption("3 opcje", "3")
      .addOption("4 opcje", "4")
      .addOption("5 opcji", "5")
      .addOption("6 opcji", "6")
      .addOption("7 opcji", "7")
      .addOption("8 opcji", "8")
      .addOption("9 opcji", "9")
      .addOption("10 opcji", "10")
      .setDefaultValues(state.optionCount.map(_.toString).toList.asJava)
      .build()

    val nextButton = if (canProceed) {
      Button.primary(s"poll_next:$userId", "Dalej →")
    } else {
      Button.primary(s"poll_next:$userId", "Dalej →").asDisabled()
    }

    event.getMessage.editMessageComponents(
      ActionRow.of(typeMenu),
      ActionRow.of(countMenu),
      ActionRow.of(nextButton)
    ).queue()
  }

  /**
   * Obsługuje kliknięcie przycisku "Dalej" - otwiera modal(e)
   */
  private def handleNextButton(event: ButtonInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.pollType.isDefined && state.optionCount.isDefined =>
        val optionCount = state.optionCount.get
        
        // Strategia w zależności od liczby opcji:
        // 2-3 opcje: 1 modal (pytanie + czas + opcje)
        // 4 opcje: 1 modal (pytanie + opcje, bez czasu)
        // 5-8 opcji: 2 modale
        // 9-10 opcji: 3 modale
        
        if (optionCount <= 3) {
          // 2-3 opcje: JEDEN modal z wszystkim
          val modalBuilder = Modal.create(s"poll_create:$userId", "Utwórz ankietę")
          
          modalBuilder.addActionRow(
            TextInput.create("question", "Pytanie ankiety", TextInputStyle.SHORT)
              .setPlaceholder("np. Jaki jest twój ulubiony vocation?")
              .setRequired(true)
              .setMaxLength(256)
              .build()
          )
          
          modalBuilder.addActionRow(
            TextInput.create("duration", "Czas trwania", TextInputStyle.SHORT)
              .setPlaceholder("np. 60, 2h, 3d (minuty/godziny/dni)")
              .setRequired(false)
              .setMaxLength(10)
              .build()
          )
          
          for (i <- 1 to optionCount) {
            modalBuilder.addActionRow(
              TextInput.create(s"option$i", s"Opcja $i", TextInputStyle.SHORT)
                .setPlaceholder(s"np. ${if (i == 1) "Knight" else if (i == 2) "Paladin" else "Sorcerer"}")
                .setRequired(i <= 2)
                .setMaxLength(80)
                .build()
            )
          }
          
          event.replyModal(modalBuilder.build()).queue()
          
        } else if (optionCount == 4) {
          // 4 opcje: JEDEN modal (pytanie + 4 opcje, bez czasu)
          val modalBuilder = Modal.create(s"poll_create:$userId", "Utwórz ankietę")
          
          modalBuilder.addActionRow(
            TextInput.create("question", "Pytanie ankiety", TextInputStyle.SHORT)
              .setPlaceholder("np. Jaki jest twój ulubiony vocation?")
              .setRequired(true)
              .setMaxLength(256)
              .build()
          )
          
          for (i <- 1 to 4) {
            modalBuilder.addActionRow(
              TextInput.create(s"option$i", s"Opcja $i", TextInputStyle.SHORT)
                .setPlaceholder(s"np. ${if (i == 1) "Knight" else if (i == 2) "Paladin" else if (i == 3) "Sorcerer" else "Druid"}")
                .setRequired(i <= 2)
                .setMaxLength(80)
                .build()
            )
          }
          
          event.replyModal(modalBuilder.build()).queue()
          
        } else {
          // 5-10 opcji: PIERWSZY modal (pytanie + opcje 1-4)
          val modalBuilder = Modal.create(s"poll_create_first:$userId", s"Utwórz ankietę (1/${if (optionCount <= 8) "2" else "3"})")
          
          modalBuilder.addActionRow(
            TextInput.create("question", "Pytanie ankiety", TextInputStyle.SHORT)
              .setPlaceholder("np. Jaki jest twój ulubiony vocation?")
              .setRequired(true)
              .setMaxLength(256)
              .build()
          )
          
          // Opcje 1-4 (maksymalnie 4 opcje w pierwszym modalu)
          for (i <- 1 to 4) {
            modalBuilder.addActionRow(
              TextInput.create(s"option$i", s"Opcja $i", TextInputStyle.SHORT)
                .setPlaceholder(s"np. ${if (i == 1) "Knight" else if (i == 2) "Paladin" else if (i == 3) "Sorcerer" else "Druid"}")
                .setRequired(i <= 2)
                .setMaxLength(80)
                .build()
            )
          }
          
          event.replyModal(modalBuilder.build()).queue()
        }
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje pierwszy modal dla ankiet 5-10 opcji (pytanie + opcje 1-4)
   */
  private def handleFirstModalFor6to10(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) =>
        try {
          // Pobierz wartości z pierwszego modalu
          val question = event.getValue("question").getAsString
          
          // Pobierz opcje 1-4 (nie 5!)
          val firstFourOptions = (1 to 4).flatMap { i =>
            Option(event.getValue(s"option$i")).map(_.getAsString).filter(_.nonEmpty)
          }.toList
          
          if (firstFourOptions.size < 2) {
            event.reply(s"${Config.noEmoji} Musisz podać przynajmniej 2 opcje!")
              .setEphemeral(true).queue()
            return
          }
          
          // Zaktualizuj stan - zapisz pytanie i pierwsze 4 opcje
          val updatedState = state.copy(
            question = Some(question),
            firstFiveOptions = Some(firstFourOptions) // używamy tego samego pola mimo że to 4
          )
          pollCommand.updateCreationState(userId, updatedState)
          
          // Odpowiedz z buttonem do otwarcia drugiego modalu
          val optionCount = state.optionCount.get
          val remainingCount = optionCount - firstFourOptions.size
          val totalModals = if (optionCount <= 8) 2 else 3
          
          val nextButton = Button.primary(s"poll_second_modal:$userId", s"Dalej → (Krok 2/$totalModals)")
          
          val embed = new EmbedBuilder()
            .setTitle(s"📊 Tworzenie ankiety - Krok 1/$totalModals ukończony")
            .setDescription(
              s"**Pytanie:** $question\n\n" +
              s"**Opcje 1-4:**\n" +
              firstFourOptions.zipWithIndex.map { case (opt, idx) => s"${idx + 1}. $opt" }.mkString("\n") +
              s"\n\n**Pozostało:** $remainingCount opcji + czas trwania\n\n" +
              s"Kliknij **Dalej** aby kontynuować."
            )
            .setColor(new Color(59, 130, 246))
            .build()
          
          event.replyEmbeds(embed)
            .addActionRow(nextButton)
            .setEphemeral(true)
            .queue()
          
        } catch {
          case e: Exception =>
            logger.error("Error handling first modal for 5-10 options", e)
            event.reply(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
              .setEphemeral(true).queue()
        }
        
      case None =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje drugi modal (środkowy) dla ankiet 9-10 opcji (czas + opcje 5-8)
   */
  private def handleMiddleModalFor9to10(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.question.isDefined && state.firstFiveOptions.isDefined =>
        try {
          val question = state.question.get
          val firstFourOptions = state.firstFiveOptions.get
          val optionCount = state.optionCount.get
          
          // Pobierz czas
          val durationStr = Option(event.getValue("duration")).map(_.getAsString).getOrElse("60")
          val duration = parseDuration(durationStr).getOrElse(60)
          
          // Walidacja - maksymalnie 60 dni (86400 minut)
          if (duration < 1 || duration > 86400) {
            event.reply(s"${Config.noEmoji} Czas trwania musi być między 1 minutą a 60 dniami!")
              .setEphemeral(true).queue()
            return
          }
          
          // Pobierz opcje 5-8
          val secondFourOptions = (5 to 8).flatMap { i =>
            Option(event.getValue(s"option$i")).map(_.getAsString).filter(_.nonEmpty)
          }.toList
          
          // Zaktualizuj stan - zapisz czas i opcje 5-8
          val updatedState = state.copy(
            duration = Some(duration),
            secondFourOptions = Some(secondFourOptions)
          )
          pollCommand.updateCreationState(userId, updatedState)
          
          // Ile opcji zostało (9 lub 10)
          val allOptionsCount = firstFourOptions.size + secondFourOptions.size
          val remainingCount = optionCount - allOptionsCount
          
          // Pokaż button do trzeciego modala
          val nextButton = Button.primary(s"poll_third_modal:$userId", s"Dalej → (Krok 3/3)")
          
          val embed = new EmbedBuilder()
            .setTitle("📊 Tworzenie ankiety - Krok 2/3 ukończony")
            .setDescription(
              s"**Pytanie:** $question\n\n" +
              s"**Opcje 1-4:**\n" +
              firstFourOptions.zipWithIndex.map { case (opt, idx) => s"${idx + 1}. $opt" }.mkString("\n") +
              s"\n\n**Opcje 5-8:**\n" +
              secondFourOptions.zipWithIndex.map { case (opt, idx) => s"${idx + 5}. $opt" }.mkString("\n") +
              s"\n\n**Czas:** $duration minut\n\n" +
              s"**Pozostało:** $remainingCount opcji\n\n" +
              s"Kliknij **Dalej** aby uzupełnić ostatnie opcje."
            )
            .setColor(new Color(59, 130, 246))
            .build()
          
          event.replyEmbeds(embed)
            .addActionRow(nextButton)
            .setEphemeral(true)
            .queue()
          
        } catch {
          case e: Exception =>
            logger.error("Error handling middle modal for 9-10 options", e)
            event.reply(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
              .setEphemeral(true).queue()
        }
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje kliknięcie buttona "Dalej" po drugim modalu - otwiera trzeci modal (tylko dla 9-10 opcji)
   */
  private def handleThirdModalButton(event: ButtonInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.question.isDefined && state.firstFiveOptions.isDefined && state.secondFourOptions.isDefined =>
        val optionCount = state.optionCount.get
        val firstFourOptions = state.firstFiveOptions.get
        val secondFourOptions = state.secondFourOptions.get
        val allOptionsCount = firstFourOptions.size + secondFourOptions.size
        val remainingCount = optionCount - allOptionsCount
        
        // Trzeci modal z opcjami 9-10
        val modalBuilder = Modal.create(s"poll_create_third:$userId", "Utwórz ankietę (3/3)")
        
        // Opcje 9-10
        for (i <- 1 to remainingCount) {
          val actualIndex = allOptionsCount + i
          modalBuilder.addActionRow(
            TextInput.create(s"option$actualIndex", s"Opcja $actualIndex", TextInputStyle.SHORT)
              .setPlaceholder(s"Opcjonalna")
              .setRequired(false)
              .setMaxLength(80)
              .build()
          )
        }
        
        event.replyModal(modalBuilder.build()).queue()
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje drugi modal dla ankiet 6-10 opcji (opcje 6-10 + czas)
   */
  private def handleSecondModalFor6to10(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.question.isDefined && state.firstFiveOptions.isDefined =>
        try {
          val question = state.question.get
          val firstFiveOptions = state.firstFiveOptions.get
          val optionCount = state.optionCount.get
          
          // Pobierz czas
          val durationStr = Option(event.getValue("duration")).map(_.getAsString).getOrElse("60")
          val duration = parseDuration(durationStr).getOrElse(60)
          
          // Pobierz pozostałe opcje (6-10)
          val remainingOptions = (firstFiveOptions.size + 1 to optionCount).flatMap { i =>
            Option(event.getValue(s"option$i")).map(_.getAsString).filter(_.nonEmpty)
          }.toList
          
          // Połącz wszystkie opcje
          val allOptions = firstFiveOptions ++ remainingOptions
          
          if (allOptions.size < 2) {
            event.reply(s"${Config.noEmoji} Musisz podać przynajmniej 2 opcje!")
              .setEphemeral(true).queue()
            return
          }
          
          if (duration < 1 || duration > 86400) {
            event.reply(s"${Config.noEmoji} Czas trwania musi być między 1 minutą a 60 dniami (86400 minut)!")
              .setEphemeral(true).queue()
            return
          }
          
          // Informacja o czasie dla użytkownika
          val durationInfo = if (duration < 60) {
            s"$duration minut"
          } else if (duration < 1440) {
            s"${duration / 60}h"
          } else {
            s"${duration / 1440}d"
          }
          
          // Odpowiedz użytkownikowi
          event.reply(s"✅ Tworzę ankietę (czas: $durationInfo)...").setEphemeral(true).queue()
          
          // Utwórz ankietę
          createPollFromData(event, state, question, allOptions, duration)
          
        } catch {
          case e: Exception =>
            logger.error("Error handling second modal", e)
            event.reply(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
              .setEphemeral(true).queue()
        }
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje trzeci modal (ostatni) dla ankiet 9-10 opcji (opcje 9-10)
   */
  private def handleThirdModalFor9to10(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) if state.question.isDefined && state.firstFiveOptions.isDefined && state.secondFourOptions.isDefined && state.duration.isDefined =>
        try {
          val question = state.question.get
          val firstFourOptions = state.firstFiveOptions.get
          val secondFourOptions = state.secondFourOptions.get
          val duration = state.duration.get
          val optionCount = state.optionCount.get
          
          val allOptionsCount = firstFourOptions.size + secondFourOptions.size
          
          // Pobierz ostatnie opcje (9-10)
          val lastOptions = ((allOptionsCount + 1) to optionCount).flatMap { i =>
            Option(event.getValue(s"option$i")).map(_.getAsString).filter(_.nonEmpty)
          }.toList
          
          // Połącz wszystkie opcje
          val allOptions = firstFourOptions ++ secondFourOptions ++ lastOptions
          
          if (allOptions.size < 2) {
            event.reply(s"${Config.noEmoji} Musisz podać przynajmniej 2 opcje!")
              .setEphemeral(true).queue()
            return
          }
          
          // Odpowiedz użytkownikowi
          event.reply("✅ Tworzę ankietę...").setEphemeral(true).queue()
          
          // Utwórz ankietę
          createPollFromData(event, state, question, allOptions, duration)
          
        } catch {
          case e: Exception =>
            logger.error("Error handling third modal", e)
            event.reply(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
              .setEphemeral(true).queue()
        }
        
      case _ =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Obsługuje wysłanie modalu - tworzy ankietę
   */
  private def handlePollCreation(event: ModalInteractionEvent): Unit = {
    val userId = event.getUser.getId
    
    pollCommand.getCreationState(userId) match {
      case Some(state) =>
        try {
          // Pobierz wartości z modalu
          val question = event.getValue("question").getAsString
          
          // Czas - dla 4 opcji użyj domyślnie 60 (nie ma pola w modalu)
          val durationStr = if (state.optionCount.get <= 3) {
            Option(event.getValue("duration")).map(_.getAsString).getOrElse("60")
          } else {
            "60" // Domyślnie 60 minut dla 4 opcji
          }
          val duration = parseDuration(durationStr).getOrElse(60)
          
          // Pobierz opcje (1-4)
          val options = (1 to state.optionCount.get).flatMap { i =>
            Option(event.getValue(s"option$i")).map(_.getAsString).filter(_.nonEmpty)
          }.toList
          
          // Walidacja
          if (options.size < 2) {
            event.reply(s"${Config.noEmoji} Musisz podać przynajmniej 2 opcje!")
              .setEphemeral(true).queue()
            return
          }
          
          if (duration < 1 || duration > 86400) {
            event.reply(s"${Config.noEmoji} Czas trwania musi być między 1 minutą a 60 dniami (86400 minut)!")
              .setEphemeral(true).queue()
            return
          }
          
          // Informacja dla użytkownika o czasie
          val durationInfo = if (duration < 60) {
            s"$duration minut"
          } else if (duration < 1440) {
            s"${duration / 60}h"
          } else {
            s"${duration / 1440}d"
          }
          
          val timeInfo = if (state.optionCount.get == 4) {
            " (czas: 60 minut)"
          } else if (state.optionCount.get <= 3) {
            s" (czas: $durationInfo)"
          } else {
            ""
          }
          
          // Odpowiedz użytkownikowi że ankieta jest tworzona
          event.reply(s"✅ Tworzę ankietę$timeInfo...").setEphemeral(true).queue()
          
          // Użyj wspólnej metody do utworzenia ankiety
          createPollFromData(event, state, question, options, duration)
          
        } catch {
          case e: Exception =>
            logger.error("Error creating poll", e)
            event.reply(s"${Config.noEmoji} Wystąpił błąd: ${e.getMessage}")
              .setEphemeral(true).queue()
        }
        
      case None =>
        event.reply("❌ Sesja wygasła. Użyj `/poll` ponownie.")
          .setEphemeral(true).queue()
    }
  }

  /**
   * Tworzy ankietę i publikuje ją na kanale (wspólna logika dla 1-5 i 6-10 opcji)
   */
  private def createPollFromData(
    event: ModalInteractionEvent,
    state: PollCreationState,
    question: String,
    options: List[String],
    duration: Int
  ): Unit = {
    val userId = event.getUser.getId
    val guild = event.getGuild
    
    // Pobierz kanał - może być TextChannel lub ThreadChannel (forum)
    val guildChannel = guild.getGuildChannelById(state.channelId)
    
    if (guildChannel == null) {
      event.getHook.editOriginal(s"${Config.noEmoji} Nie znaleziono kanału!")
        .queue()
      return
    }
    
    // Sprawdź czy to kanał na którym można wysyłać wiadomości
    val messageChannel = guildChannel match {
      case tc: net.dv8tion.jda.api.entities.channel.concrete.TextChannel => tc
      case tc: net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel => tc
      case _ =>
        event.getHook.editOriginal(s"${Config.noEmoji} Ten typ kanału nie jest obsługiwany!")
          .queue()
        return
    }
    
    val now = ZonedDateTime.now()
    val endsAt = now.plusMinutes(duration)
    val allowMultiple = state.pollType.get == "multiple"
    
    // Utwórz embed dla ankiety
    val embed = createPollEmbed(question, options, endsAt, allowMultiple, 0)
    
    // Utwórz SelectMenu dla głosowania
    val voteMenu = createVoteMenu("temporary", options, allowMultiple)
    
    // Wyślij ankietę publicznie
    messageChannel.sendMessageEmbeds(embed)
      .addActionRow(voteMenu)
      .queue(message => {
        // Zapisz ankietę do bazy
        val pollId = s"${guild.getId}_${message.getId}"
        
        val poll = Poll(
          pollId = pollId,
          guildId = guild.getId,
          channelId = messageChannel.getId,
          messageId = message.getId,
          question = question,
          options = options,
          allowMultiple = allowMultiple,
          createdBy = userId,
          createdAt = now,
          endsAt = endsAt,
          isActive = true
        )
        
        pollManager.createPoll(poll) match {
          case scala.util.Success(_) =>
            // Zaktualizuj menu z prawdziwym pollId
            val updatedMenu = createVoteMenu(pollId, options, allowMultiple)
            
            // Dodaj button "Edytuj czas" (tylko dla adminów)
            val editTimeButton = Button.secondary(s"poll_edit_time:$pollId", "⏰ Edytuj czas")
            
            message.editMessageComponents(
              ActionRow.of(updatedMenu),
              ActionRow.of(editTimeButton)
            ).queue()
            
            val pollType = if (allowMultiple) "wielokrotnego" else "jednokrotnego"
            logger.info(s"✅ Poll ($pollType wyboru) created: $pollId by ${event.getUser.getAsTag}")
            
            event.getHook.editOriginal("✅ Ankieta została utworzona!")
              .queue()
            
          case scala.util.Failure(e) =>
            logger.error(s"Error saving poll to database", e)
            message.delete().queue()
            event.getHook.editOriginal(s"${Config.noEmoji} Błąd podczas tworzenia ankiety: ${e.getMessage}")
              .queue()
        }
        
        // Usuń stan tworzenia
        pollCommand.removeCreationState(userId)
      })
  }

  /**
   * Obsługuje głosowanie w ankiecie
   */
  private def handleVote(event: StringSelectInteractionEvent): Unit = {
    val pollId = event.getComponentId.substring(10) // Usuń "poll_vote:" prefix
    val userId = event.getUser.getId
    val selectedValues = event.getValues.asScala.toList
    val selectedIndices = selectedValues.map(_.toInt)
    
    event.deferReply(true).queue()
    
    pollManager.getPoll(pollId) match {
      case Some(poll) if !poll.isActive =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Ta ankieta już się zakończyła!")
          .setColor(new Color(239, 68, 68))
          .build()
        event.getHook.sendMessageEmbeds(embed).queue()
        
      case Some(poll) =>
        pollManager.vote(pollId, userId, selectedIndices) match {
          case scala.util.Success(_) =>
            val optionsText = selectedIndices.map(poll.options(_)).mkString(", ")
            val message = if (selectedIndices.size == 1) {
              s"${Config.yesEmoji} Zagłosowano na: **$optionsText**"
            } else {
              s"${Config.yesEmoji} Wybrano: **$optionsText**"
            }
            
            val embed = new EmbedBuilder()
              .setDescription(message)
              .setColor(new Color(34, 197, 94))
              .build()
            
            event.getHook.sendMessageEmbeds(embed).queue()
            
            // Aktualizuj wyniki w ankiecie
            updatePollMessage(pollId, event.getMessage)
            
          case scala.util.Failure(e) =>
            val embed = new EmbedBuilder()
              .setDescription(s"${Config.noEmoji} Błąd podczas głosowania: ${e.getMessage}")
              .setColor(new Color(239, 68, 68))
              .build()
            event.getHook.sendMessageEmbeds(embed).queue()
        }
        
      case None =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Nie znaleziono ankiety!")
          .setColor(new Color(239, 68, 68))
          .build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }

  /**
   * Tworzy embed dla ankiety
   */
  private def createPollEmbed(
    question: String,
    options: List[String],
    endsAt: ZonedDateTime,
    allowMultiple: Boolean,
    totalVoters: Int
  ): net.dv8tion.jda.api.entities.MessageEmbed = {
    val builder = new EmbedBuilder()
    
    val titlePrefix = if (allowMultiple) "📊🔢" else "📊"
    builder.setTitle(s"$titlePrefix $question")
    builder.setColor(new Color(59, 130, 246))
    
    // Pokaż opcje z pustymi wynikami
    val optionsText = options.zipWithIndex.map { case (option, idx) =>
      val emoji = idx match {
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
        case _ => "🔘"
      }
      val bar = "░" * 10
      s"$emoji **$option**\n$bar 0 głosów (0.0%)"
    }.mkString("\n\n")
    
    builder.setDescription(optionsText)
    
    // Footer
    val endsAtFormatted = endsAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    val typeInfo = if (allowMultiple) "Wielokrotny wybór" else "Jednokrotny wybór"
    builder.setFooter(s"Zakończenie: $endsAtFormatted | $typeInfo | Głosujących: $totalVoters")
    
    builder.build()
  }

  /**
   * Tworzy SelectMenu dla głosowania
   */
  private def createVoteMenu(pollId: String, options: List[String], allowMultiple: Boolean): StringSelectMenu = {
    val builder = StringSelectMenu.create(s"poll_vote:$pollId")
      .setPlaceholder(
        if (allowMultiple) "Wybierz opcje (można zaznaczyć kilka)"
        else "Wybierz opcję"
      )
    
    options.zipWithIndex.foreach { case (option, idx) =>
      val emoji = idx match {
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
        case _ => "🔘"
      }
      builder.addOption(s"$emoji $option", idx.toString)
    }
    
    if (allowMultiple) {
      builder.setMinValues(1)
      builder.setMaxValues(options.size)
    }
    
    builder.build()
  }

  /**
   * Aktualizuje wiadomość z ankietą pokazując wyniki
   */
  private def updatePollMessage(pollId: String, message: net.dv8tion.jda.api.entities.Message): Unit = {
    try {
      pollManager.getPoll(pollId) match {
        case Some(poll) =>
          val results = pollManager.getResults(pollId)
          val totalVoters = pollManager.getTotalVoters(pollId)
          
          val builder = new EmbedBuilder()
          val titlePrefix = if (poll.allowMultiple) "📊🔢" else "📊"
          builder.setTitle(s"$titlePrefix ${poll.question}")
          builder.setColor(new Color(59, 130, 246))
          
          // Wyniki z progress barami
          val resultsText = results.map { result =>
            val emoji = result.optionIndex match {
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
              case _ => "🔘"
            }
            val bar = createProgressBar(result.percentage)
            val count = result.voteCount
            val percent = f"${result.percentage}%.1f"
            
            val voteWord = if (poll.allowMultiple) {
              if (count == 1) "głos" else "głosów"
            } else {
              if (count == 1) "głos" else "głosów"
            }
            
            s"$emoji **${result.optionText}**\n$bar $count $voteWord ($percent%)"
          }.mkString("\n\n")
          
          builder.setDescription(resultsText)
          
          // Footer
          val endsAtFormatted = poll.endsAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
          val typeInfo = if (poll.allowMultiple) "Wielokrotny wybór" else "Jednokrotny wybór"
          builder.setFooter(s"Zakończenie: $endsAtFormatted | $typeInfo | Głosujących: $totalVoters")
          
          // WAŻNE: Zachowaj istniejące komponenty (menu + button)
          val existingComponents = message.getActionRows
          
          message.editMessageEmbeds(builder.build())
            .setComponents(existingComponents)
            .queue()
          
        case None =>
          logger.warn(s"Poll not found: $pollId")
      }
    } catch {
      case e: Exception =>
        logger.error(s"Error updating poll message: $pollId", e)
    }
  }

  /**
   * Tworzy pasek postępu
   */
  private def createProgressBar(percentage: Double): String = {
    val filledBlocks = (percentage / 10).toInt
    val emptyBlocks = 10 - filledBlocks
    ("█" * filledBlocks) + ("░" * emptyBlocks)
  }

  /**
   * Finalizuje ankietę i pokazuje końcowe wyniki
   */
  def finalizePoll(poll: Poll, jda: net.dv8tion.jda.api.JDA): Unit = {
    try {
      val guild = jda.getGuildById(poll.guildId)
      if (guild == null) {
        logger.warn(s"Guild not found for poll ${poll.pollId}")
        return
      }
      
      // Pobierz kanał - może być TextChannel lub ThreadChannel (forum)
      val guildChannel = guild.getGuildChannelById(poll.channelId)
      if (guildChannel == null) {
        logger.warn(s"Channel not found for poll ${poll.pollId}")
        pollManager.deactivatePoll(poll.pollId)
        return
      }
      
      val messageChannel = guildChannel match {
        case tc: net.dv8tion.jda.api.entities.channel.concrete.TextChannel => tc
        case tc: net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel => tc
        case _ =>
          logger.warn(s"Unsupported channel type for poll ${poll.pollId}")
          pollManager.deactivatePoll(poll.pollId)
          return
      }
      
      messageChannel.retrieveMessageById(poll.messageId).queue(
        message => {
          val results = pollManager.getResults(poll.pollId)
          val totalVoters = pollManager.getTotalVoters(poll.pollId)
          
          // Zaktualizuj wiadomość z ankietą - usuń menu
          val finalEmbed = createFinalPollEmbed(poll, results, totalVoters)
          message.editMessageEmbeds(finalEmbed)
            .setComponents() // Usuń menu
            .queue()
          
          // Wyślij wyniki
          val resultsMessage = createResultsMessage(poll, results, totalVoters)
          messageChannel.sendMessageEmbeds(resultsMessage).queue()
          
          pollManager.deactivatePoll(poll.pollId)
          logger.info(s"✅ Poll ${poll.pollId} finalized with $totalVoters voters")
        },
        error => {
          logger.error(s"Error retrieving poll message ${poll.pollId}", error)
          pollManager.deactivatePoll(poll.pollId)
        }
      )
    } catch {
      case e: Exception =>
        logger.error(s"Error finalizing poll ${poll.pollId}", e)
        pollManager.deactivatePoll(poll.pollId)
    }
  }

  /**
   * Tworzy finalny embed dla zakończonej ankiety
   */
  private def createFinalPollEmbed(poll: Poll, results: List[PollResult], totalVoters: Int): net.dv8tion.jda.api.entities.MessageEmbed = {
    val builder = new EmbedBuilder()
    val titlePrefix = if (poll.allowMultiple) "📊🔢" else "📊"
    builder.setTitle(s"$titlePrefix ${poll.question}")
    builder.setColor(new Color(239, 68, 68)) // red - zakończona
    
    val resultsText = results.map { result =>
      val emoji = result.optionIndex match {
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
        case _ => "🔘"
      }
      val bar = createProgressBar(result.percentage)
      s"$emoji **${result.optionText}**\n$bar ${result.voteCount} głosów (${f"${result.percentage}%.1f"}%)"
    }.mkString("\n\n")
    
    builder.setDescription(resultsText)
    
    val typeInfo = if (poll.allowMultiple) "Wielokrotny wybór" else "Jednokrotny wybór"
    builder.setFooter(s"Ankieta zakończona | $typeInfo | Głosujących: $totalVoters")
    
    builder.build()
  }

  /**
   * Tworzy wiadomość z wynikami
   */
  private def createResultsMessage(poll: Poll, results: List[PollResult], totalVoters: Int): net.dv8tion.jda.api.entities.MessageEmbed = {
    val builder = new EmbedBuilder()
      .setTitle("🏆 Wyniki ankiety")
      .setColor(new Color(34, 197, 94))
    
    val maxVotes = results.map(_.voteCount).maxOption.getOrElse(0)
    val winners = results.filter(_.voteCount == maxVotes)
    
    val winnerText = if (totalVoters == 0) {
      "Nikt nie zagłosował w tej ankiecie."
    } else if (winners.size > 1) {
      s"**Remis!** Następujące opcje otrzymały po $maxVotes głosów:\n" +
        winners.map(w => s"• ${w.optionText}").mkString("\n")
    } else {
      s"**${winners.head.optionText}** z ${winners.head.voteCount} głosami (${f"${winners.head.percentage}%.1f"}%)"
    }
    
    builder.addField("❓ Pytanie", poll.question, false)
    val pollType = if (poll.allowMultiple) "🔢 Wielokrotny wybór" else "1️⃣ Jednokrotny wybór"
    builder.addField("📋 Typ ankiety", pollType, false)
    builder.addField("🥇 Zwycięzca", winnerText, false)
    
    val allResultsText = results.sortBy(-_.voteCount).map { result =>
      val bar = createProgressBar(result.percentage)
      s"**${result.optionText}**\n$bar ${result.voteCount} głosów (${f"${result.percentage}%.1f"}%)"
    }.mkString("\n\n")
    
    builder.addField("📊 Wszystkie wyniki", allResultsText, false)
    builder.setFooter(s"Łączna liczba głosujących: $totalVoters")
    
    builder.build()
  }

  /**
   * Obsługuje kliknięcie buttona "Edytuj czas"
   */
  private def handleEditTimeButton(event: ButtonInteractionEvent): Unit = {
    val pollId = event.getComponentId.substring(15) // Usuń "poll_edit_time:" prefix
    
    // Sprawdź uprawnienia - tylko MANAGE_SERVER lub ADMINISTRATOR
    val member = event.getMember
    if (member == null ||
        !(member.hasPermission(net.dv8tion.jda.api.Permission.MANAGE_SERVER) ||
          member.hasPermission(net.dv8tion.jda.api.Permission.ADMINISTRATOR))) {
      event.reply(s"${Config.noEmoji} Tylko administratorzy mogą edytować czas ankiety!")
        .setEphemeral(true)
        .queue()
      return
    }

    // Sprawdź czy ankieta istnieje i jest aktywna
    pollManager.getPoll(pollId) match {
      case Some(poll) if !poll.isActive =>
        event.reply(s"${Config.noEmoji} Ta ankieta jest już nieaktywna!")
          .setEphemeral(true)
          .queue()

      case Some(poll) =>
        // Pokaż modal z polem na nowy czas
        val timeInput = TextInput.create("new_duration", "Nowy czas trwania", TextInputStyle.SHORT)
          .setPlaceholder("np. 60, 2h, 7d, 30d, 60d")
          .setRequired(true)
          .setMinLength(1)
          .setMaxLength(10)
          .build()

        val modal = Modal.create(s"poll_edit_time:$pollId", "⏰ Edytuj czas trwania ankiety")
          .addActionRow(timeInput)
          .build()

        event.replyModal(modal).queue()

      case None =>
        event.reply(s"${Config.noEmoji} Nie znaleziono ankiety!")
          .setEphemeral(true)
          .queue()
    }
  }

  /**
   * Obsługuje modal edycji czasu ankiety
   */
  private def handleEditTimeModal(event: ModalInteractionEvent): Unit = {
    val pollId = event.getModalId.substring(15) // Usuń "poll_edit_time:" prefix
    val newDurationStr = event.getValue("new_duration").getAsString

    event.deferReply(true).queue()

    // Parsuj nowy czas
    val newDurationMinutes: Int = parseDuration(newDurationStr) match {
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
          event.getHook.editOriginal(s"${Config.noEmoji} Nowy czas zakończenia jest w przeszłości! Ankieta została utworzona ${poll.createdAt}.")
            .queue()
          return
        }

        // Aktualizuj w bazie
        pollManager.updatePollEndTime(pollId, newEndsAt) match {
          case scala.util.Success(_) =>
            logger.info(s"✅ Database updated: poll $pollId end time changed to $newEndsAt via button")

            // Pobierz poll ponownie z bazy (ze zaktualizowanym czasem)
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
                      // Aktualizuj embed z nowym czasem (zachowaj komponenty!)
                      val results = pollManager.getResults(pollId)
                      val totalVoters = pollManager.getTotalVoters(pollId)

                      val builder = new EmbedBuilder()
                      val titlePrefix = if (updatedPoll.allowMultiple) "📊🔢" else "📊"
                      builder.setTitle(s"$titlePrefix ${updatedPoll.question}")
                      builder.setColor(new Color(59, 130, 246))

                      // Wyniki z progress barami
                      val resultsText = results.map { result =>
                        val emoji = result.optionIndex match {
                          case 0 => "🇦"; case 1 => "🇧"; case 2 => "🇨"; case 3 => "🇩"; case 4 => "🇪"
                          case 5 => "🇫"; case 6 => "🇬"; case 7 => "🇭"; case 8 => "🇮"; case 9 => "🇯"
                          case _ => "🔘"
                        }
                        val bar = createProgressBar(result.percentage)
                        val count = result.voteCount
                        val percent = f"${result.percentage}%.1f"
                        val voteWord = if (count == 1) "głos" else "głosów"
                        s"$emoji **${result.optionText}**\n$bar $count $voteWord ($percent%)"
                      }.mkString("\n\n")

                      builder.setDescription(resultsText)

                      // Footer z nowym czasem
                      val endsAtFormatted = updatedPoll.endsAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
                      val typeInfo = if (updatedPoll.allowMultiple) "Wielokrotny wybór" else "Jednokrotny wybór"
                      builder.setFooter(s"Zakończenie: $endsAtFormatted | $typeInfo | Głosujących: $totalVoters")

                      // WAŻNE: Zachowaj istniejące komponenty (menu + button)
                      val existingComponents = message.getActionRows

                      message.editMessageEmbeds(builder.build())
                        .setComponents(existingComponents)
                        .queue(
                          _ => logger.info(s"✅ Updated poll embed for $pollId with new end time: ${updatedPoll.endsAt}"),
                          error => logger.error(s"❌ Failed to update poll embed for $pollId", error)
                        )
                    },
                    error => logger.error(s"❌ Failed to retrieve message for poll $pollId", error)
                  )
                }
              }
            }

            val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val formattedEndsAt = newEndsAt.format(formatter)

            event.getHook.editOriginal(
              s"✅ Czas trwania ankiety został zmieniony!\n\n" +
              s"**Nowy czas:** ${formatDuration(newDurationMinutes)}\n" +
              s"**Koniec:** $formattedEndsAt\n" +
              s"*(liczony od momentu utworzenia ankiety: ${poll.createdAt.format(formatter)})*"
            ).queue()

            logger.info(s"✅ Poll $pollId end time updated from ${poll.endsAt} to $newEndsAt by ${event.getUser.getAsTag} via button")

          case scala.util.Failure(e) =>
            logger.error(s"Error updating poll end time $pollId", e)
            event.getHook.editOriginal(s"${Config.noEmoji} Błąd podczas aktualizacji ankiety: ${e.getMessage}")
              .queue()
        }

      case None =>
        event.getHook.editOriginal(s"${Config.noEmoji} Nie znaleziono ankiety!")
          .queue()
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
}
