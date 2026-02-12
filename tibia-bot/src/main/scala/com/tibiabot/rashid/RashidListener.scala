package com.tibiabot.rashid

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import java.awt.Color

/**
 * Listener dla komendy /rashid
 */
class RashidListener extends ListenerAdapter with StrictLogging {
  
  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "rashid") {
      handleRashid(event)
    }
  }
  
  /**
   * Obsługa komendy /rashid
   */
  private def handleRashid(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply().queue()
    
    try {
      RashidData.getTodayLocation() match {
        case Some(location) =>
          val embed = createRashidEmbed(location)
          event.getHook.sendMessageEmbeds(embed).queue()
          
        case None =>
          val errorEmbed = new EmbedBuilder()
            .setDescription("❌ Unable to determine Rashid's location today.")
            .setColor(Color.RED)
            .build()
          event.getHook.sendMessageEmbeds(errorEmbed).queue()
      }
    } catch {
      case e: Exception =>
        logger.error("Error in /rashid command", e)
        val errorEmbed = new EmbedBuilder()
          .setDescription("❌ An error occurred while fetching Rashid's location.")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }
  
  /**
   * Tworzy embed z informacjami o Rashidzie
   */
  private def createRashidEmbed(location: RashidLocation): net.dv8tion.jda.api.entities.MessageEmbed = {
    val embed = new EmbedBuilder()
      .setTitle("Informations:")
      .setColor(new Color(0, 255, 0)) // Zielony
      .addField(
        "",
        s"[Rashid](https://tibiopedia.pl/npcs/Rashid)\n\n${location.description}",
        true
      )
      .setThumbnail(location.imageUrl)
      .setImage(location.mapImageUrl)
      .build()
    
    embed
  }
}
