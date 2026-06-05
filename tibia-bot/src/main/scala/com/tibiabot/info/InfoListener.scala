package com.tibiabot.info

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.StrictLogging
import java.awt.Color

/**
 * Listener dla komendy /info
 */
class InfoListener extends ListenerAdapter with StrictLogging {

  // ENV VARIABLES (do użycia globalnie w projekcie)
  private val websiteUrl = sys.env.get("OPTIMUM_WEBSITE")
  private val discordUrl = sys.env.get("OPTIMUM_DISCORD")
  private val donateUrl  = sys.env.get("OPTIMUM_DONATE")

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "info") {
      handleInfo(event)
    }
  }

  /**
   * Obsługa komendy /info
   */
  private def handleInfo(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply().queue()

    try {
      val embed = createInfoEmbed()
      event.getHook.sendMessageEmbeds(embed).queue()
    } catch {
      case e: Exception =>
        logger.error("Error in /info command", e)
        val errorEmbed = new EmbedBuilder()
          .setDescription("❌ Wystąpił błąd podczas pobierania informacji o bocie.")
          .setColor(Color.RED)
          .build()
        event.getHook.sendMessageEmbeds(errorEmbed).queue()
    }
  }

  /**
   * Tworzy embed z informacjami o bocie
   */
  private def createInfoEmbed(): net.dv8tion.jda.api.entities.MessageEmbed = {

    val footerText = buildFooterText()

    val embedBuilder = new EmbedBuilder()
      .setTitle("ℹ️ Informacje o Optimum Bot")
      .setColor(new Color(255, 102, 0)) // #FF6600

      .addField(
        "👑 Właściciel",
        "Optimum Bot został stworzony i jest rozwijany przez **Sinrac**.\n" +
        "Autor posiada pełne prawa do bota oraz jego kodu źródłowego.",
        false
      )

      .addField(
        "⚖️ Informacje prawne",
        "Wszystkie prawa zastrzeżone.\n" +
        "Kopiowanie, modyfikowanie lub rozpowszechnianie bota lub jego części\n" +
        "bez zgody autora jest zabronione.",
        false
      )

      .addField(
        "🛠️ Wersja",
        "Aktualna wersja bota: **v2.3e**",
        false
      )

      .addField(
        "📅 Uruchomienie",
        "Bot działa nieprzerwanie od:\n**7 stycznia 2025**",
        false
      )

      .addField(
        "🤖 O bocie",
        "Optimum Bot to zaawansowany bot Discord do monitorowania i zarządzania\n" +
        "aktywnością w grze **Tibia MMORPG**.\n\n" +
        "Zaprojektowany z myślą o czytelności, automatyzacji i minimum spamu.",
        false
      )

      .addField(
        "📜 Dostępne komendy",
        "🔧 **/setup** – konfiguracja bota dla wybranego świata\n" +
        "⚔️ **/hunted** – zarządzanie listą wrogów\n" +
        "🤝 **/allies** – zarządzanie sojusznikami\n" +
        "⚖️ **/neutral** – lista graczy neutralnych\n" +
        "🟢 **/online** – konfiguracja kanałów online\n" +
        "💰 **/split_loot** – podział łupu z party\n" +
        "🧙 **/rashid** – aktualna lokalizacja Rashida\n" +
        "ℹ️ **/info** – informacje o bocie\n\n" +
        "📌 Wpisz `/`, aby zobaczyć wszystkie dostępne komendy.",
        false
      )

    footerText.foreach(embedBuilder.setFooter)

    embedBuilder.build()
  }

  /**
   * Składa footer na podstawie ENV
   */
  private def buildFooterText(): Option[String] = {
    val parts = Seq(
      websiteUrl.map(url => s"🌐 Website: $url"),
      discordUrl.map(url => s"💬 Discord: $url"),
      donateUrl.map(url  => s"❤️ Donate: $url")
    ).flatten

    if (parts.nonEmpty) Some(parts.mkString(" | ")) else None
  }
}
