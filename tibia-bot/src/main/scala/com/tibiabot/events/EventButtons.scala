package com.tibiabot.events

import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.interactions.components.ActionRow

/**
 * Helper dla przycisków eventów
 */
object EventButtons {
  
  def createEventButtons(eventId: Int, active: Boolean): List[ActionRow] = {
    if (!active) {
      // Event zamknięty - tylko informacja
      List(
        ActionRow.of(
          Button.secondary(s"event:$eventId:closed", "Event Closed").asDisabled()
        ).asInstanceOf[ActionRow]
      )
    } else {
      // Event otwarty - przyciski zapisów + zarządzanie
      List(
        // Rząd 1: Zapisy na role
        ActionRow.of(
          Button.primary(s"event:$eventId:tank", "🛡 Tank"),
          Button.success(s"event:$eventId:healer", "💚 Healer"),
          Button.danger(s"event:$eventId:dps", "⚔ Damage"),
          Button.secondary(s"event:$eventId:waitlist", "⏳ Waitlist")
        ).asInstanceOf[ActionRow],
        
        // Rząd 2: Zarządzanie eventem (Leave + Manage + Edit + Delete)
        ActionRow.of(
          Button.secondary(s"event:$eventId:leave", "Leave"),
          Button.primary(s"event:$eventId:manage", "👥 Manage"),
          Button.primary(s"event:$eventId:edit", "✏️ Edit")
        ).asInstanceOf[ActionRow],
        
        // Rząd 3: Delete (osobno dla bezpieczeństwa)
        ActionRow.of(
          Button.danger(s"event:$eventId:delete", "🗑️ Delete")
        ).asInstanceOf[ActionRow]
      )
    }
  }
  
  /**
   * Sprawdza czy button ID to przycisk eventu
   */
  def isEventButton(buttonId: String): Boolean = {
    buttonId.startsWith("event:")
  }
  
  /**
   * Parsuje button ID i zwraca (eventId, action)
   */
  def parseButtonId(buttonId: String): Option[(Int, String)] = {
    val parts = buttonId.split(":")
    if (parts.length >= 3 && parts(0) == "event") {
      scala.util.Try {
        val eventId = parts(1).toInt
        val action = parts(2)
        (eventId, action)
      }.toOption
    } else {
      None
    }
  }
}