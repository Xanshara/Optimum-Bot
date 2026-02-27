package com.tibiabot.events

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import scala.util.{Try, Success, Failure}

/**
 * Listener dla modal interactions eventów
 */
class EventModalListener(eventCommand: EventCommand) extends ListenerAdapter {

  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    val modalId = event.getModalId

    // GUARD: Ignoruj jeśli interaction już acknowledged
    if (event.isAcknowledged) {
      return
    }

    // GUARD: Obsługuj tylko event modals
    if (!modalId.startsWith("event_")) {
      return
    }

    modalId match {
      case "event_create_step1" =>
        eventCommand.handleModalStep1(event)

      case "event_create_step2" =>
        eventCommand.handleModalStep2(event)

      case "event_create_step3" =>
        eventCommand.handleModalStep3(event)

      // Multi-step edit — Step 1 (title + description + datetime)
      case id if id.startsWith("event_edit_step1:") =>
        val eventId = id.split(":").last.toInt
        eventCommand.handleModalEditStep1(event, eventId)

      // Multi-step edit — Step 2 (tank + healer + dps)
      case id if id.startsWith("event_edit_step2:") =>
        val eventId = id.split(":").last.toInt
        eventCommand.handleModalEditStep2(event, eventId)

      case id if id.startsWith("event_manage:") =>
        val eventId = id.split(":").last.toInt
        eventCommand.handleModalManage(event, eventId)

      case id if id.startsWith("event_manage_add:") =>
        val eventId = id.split(":").last.toInt
        eventCommand.handleModalManageAdd(event, eventId)

      case _ =>
        // Not an event modal we handle
    }
  }
}
