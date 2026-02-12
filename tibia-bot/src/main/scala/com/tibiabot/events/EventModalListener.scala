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
        
      case id if id.startsWith("event_edit:") =>
        val eventId = id.split(":").last.toInt
        eventCommand.handleModalEdit(event, eventId)
        
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