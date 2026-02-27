package com.tibiabot.imbue

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.{Commands, SlashCommandData, OptionData}
import net.dv8tion.jda.api.interactions.commands.OptionType
import java.awt.Color
import scala.jdk.CollectionConverters._

object ImbueCommand {

  val command: SlashCommandData = {
    val option = new OptionData(
      OptionType.STRING,
      "rodzaj",
      "Wybierz typ imbu",
      true
    )

    ImbueData.data.keys.toList.sorted.foreach { k =>
      option.addChoice(k, k)
    }

    Commands.slash("imbue", "Wyświetla informacje o imbue w Tibii")
      .addOptions(option)
  }

  def handle(event: SlashCommandInteractionEvent): Unit = {
    val rodzaj = event.getOption("rodzaj").getAsString.toLowerCase

    ImbueData.data.get(rodzaj) match {
      case Some(imbue) =>
        val embed = new EmbedBuilder()
          .setTitle(imbue.title)
          .setDescription(imbue.description)
          .setThumbnail(imbue.thumbnail)
          .setColor(new Color(0x90EE90))
          .setFooter("Last Chance")
          .build()

        event.getHook.sendMessageEmbeds(embed).queue()

      case None =>
        val embed = new EmbedBuilder()
          .setTitle("❌ Błąd")
          .setDescription("Nie rozpoznano typu imbu. Użyj `/imbue` i wybierz z listy.")
          .setColor(Color.RED)
          .build()

        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}
