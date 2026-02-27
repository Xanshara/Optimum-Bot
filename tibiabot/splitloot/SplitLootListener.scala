package com.tibiabot.splitloot

import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal
import com.typesafe.scalalogging.StrictLogging

/**
 * Listener dla komendy /split_loot
 */
class SplitLootListener extends ListenerAdapter with StrictLogging {
  
  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "split_loot") {
      handleSplitLoot(event)
    }
  }
  
  override def onModalInteraction(event: ModalInteractionEvent): Unit = {
    if (event.getModalId == "split_loot_modal") {
      handleModalSubmit(event)
    }
  }
  
  /**
   * Obsługa komendy /split_loot - otwiera modal
   */
  private def handleSplitLoot(event: SlashCommandInteractionEvent): Unit = {
    val textInput = TextInput.create("session_data", "Wklej dane sesji z Tibii", TextInputStyle.PARAGRAPH)
      .setPlaceholder("Session data: From 2025-11-10...\nSession: 01:03h...")
      .setRequired(true)
      .setMaxLength(4000)
      .build()
    
    val modal = Modal.create("split_loot_modal", "Tibia Party Hunt - Split Loot")
      .addActionRow(textInput)
      .build()
    
    event.replyModal(modal).queue()
  }
  
  /**
   * Obsługa submit modala - parsuje dane i generuje wynik
   */
  private def handleModalSubmit(event: ModalInteractionEvent): Unit = {
    event.deferReply().queue()
    
    try {
      // Pobierz dane z modala
      val sessionText = event.getValue("session_data").getAsString
      
      // Parsuj dane
      val sessionData = SessionParser.parse(sessionText)
      
      // Walidacja
      if (sessionData.players.isEmpty) {
        event.getHook.sendMessage("❌ Nie znaleziono danych graczy! Sprawdź format danych.").queue()
        return
      }
      
      if (sessionData.sessionInfo.partyBalance == 0) {
        event.getHook.sendMessage("❌ Nie znaleziono Party Balance! Sprawdź format danych.").queue()
        return
      }
      
      // Generuj embed
      val embed = EmbedGenerator.generateEmbed(sessionData)
      
      // Wyślij wynik
      event.getHook.sendMessageEmbeds(embed).queue()
      
      logger.info(s"Split loot calculated for ${sessionData.players.length} players by ${event.getUser.getName}")
      
    } catch {
      case e: Exception =>
        logger.error("Error processing split loot modal", e)
        event.getHook.sendMessage(s"❌ Wystąpił błąd przy przetwarzaniu danych:\n```${e.getMessage}```").queue()
    }
  }
}
