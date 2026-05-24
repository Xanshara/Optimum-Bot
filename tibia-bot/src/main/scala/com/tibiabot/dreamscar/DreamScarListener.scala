package com.tibiabot.dreamscar

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.Permission
import com.typesafe.scalalogging.StrictLogging

/**
 * Listener dla komendy /dreamscar
 * Wyświetla Dream Court boss na boosted_channel
 * Tylko dla adminów
 */
class DreamScarListener extends ListenerAdapter with StrictLogging {

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "dreamscar") {
      handleDreamScar(event)
    }
  }

  /**
   * Obsługa komendy /dreamscar
   */
  private def handleDreamScar(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()  // Defer - invisible reply

    try {
      val guild = event.getGuild
      if (guild == null) {
        event.getHook.sendMessage("❌ This command can only be used in a server.").queue()
        return
      }

      // Sprawdź uprawnienia (musi być ADMIN)
      val member = event.getMember
      if (member == null || !member.hasPermission(Permission.ADMINISTRATOR)) {
        event.getHook.sendMessage("❌ You need **Administrator** permission to use this command.").queue()
        return
      }

      // Pobierz dream scar boss z BotApp
      val dreamBoss = DreamScarState.getDreamBoss(guild.getId)
      
      if (dreamBoss.isEmpty || dreamBoss == "World not found") {
        event.getHook.sendMessage("⚠️ Dream Scar boss data not available. Try again in a moment.").queue()
        return
      }

      // Pobierz boosted_channel z bazy danych (live config)
      val discordConfig = DreamScarState.getDiscordConfig(guild)
      val boostedChannelId = discordConfig.getOrElse("boosted_channel", "0")
      
      if (boostedChannelId == "0" || boostedChannelId.isEmpty) {
        event.getHook.sendMessage("❌ Boosted channel not configured. Use `/setup` first.").queue()
        return
      }

      val boostedChannel = guild.getTextChannelById(boostedChannelId)
      if (boostedChannel == null) {
        event.getHook.sendMessage("❌ Boosted channel not found or deleted.").queue()
        return
      }

      if (!boostedChannel.canTalk()) {
        event.getHook.sendMessage("❌ Bot doesn't have permission to send messages in the boosted channel.").queue()
        return
      }

      // Stwórz embed
      val dreamScarEmbed = new EmbedBuilder()
        .setDescription(s"The Dream Courts boss for today is:\n### ${DreamScarState.getIndentEmoji()}${DreamScarState.getDreamScarEmoji()} **[${dreamBoss}](https://tibia.fandom.com/wiki/Dream_Scar/Boss_of_the_Day)**")
        .setThumbnail(DreamScarState.getCreatureImageUrl(dreamBoss))
        .setColor(3092790)
        .build()

      // Wyślij na boosted_channel
      boostedChannel.sendMessageEmbeds(dreamScarEmbed)
        .queue(
          _ => {
            event.getHook.sendMessage(s"✅ Dream Scar embed posted to <#{boostedChannelId}>").queue()
          },
          error => {
            logger.error(s"Failed to send Dream Scar embed: ${error.getMessage}")
            event.getHook.sendMessage(s"❌ Failed to send embed: ${error.getMessage}").queue()
          }
        )

    } catch {
      case e: Exception =>
        logger.error("Error in /dreamscar command", e)
        event.getHook.sendMessage(s"❌ An error occurred: ${e.getMessage}").queue()
    }
  }

  /**
   * Pobiera slash command definition
   */
  def getCommand(): SlashCommandData = {
    Commands.slash("dreamscar", "Display Dream Courts boss on boosted channel")
      .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR))
      .setGuildOnly(true)
  }
}

/**
 * Holder dla danych Dream Scar - będzie ustawiony z BotApp
 */
object DreamScarState {
  var dreamScarMap: Map[String, String] = Map.empty
  var indentEmoji: String = "　"
  var dreamScarEmoji: String = "🌙"
  var creatureImageUrlFunc: String => String = _ => ""
  var retrieveConfigFunc: net.dv8tion.jda.api.entities.Guild => Map[String, String] = _ => Map.empty

  def setDreamScarMap(map: Map[String, String]): Unit = {
    dreamScarMap = map
  }

  def setEmojis(indent: String, dreamScar: String): Unit = {
    indentEmoji = indent
    dreamScarEmoji = dreamScar
  }

  def setCreatureImageUrlFunc(func: String => String): Unit = {
    creatureImageUrlFunc = func
  }

  def setRetrieveConfigFunc(func: net.dv8tion.jda.api.entities.Guild => Map[String, String]): Unit = {
    retrieveConfigFunc = func
  }

  def getDreamBoss(guildId: String): String = {
    dreamScarMap.getOrElse("Antica", "World not found")  // Default to Antica
  }

  def getDiscordConfig(guild: net.dv8tion.jda.api.entities.Guild): Map[String, String] = {
    retrieveConfigFunc(guild)
  }

  def getIndentEmoji(): String = indentEmoji
  def getDreamScarEmoji(): String = dreamScarEmoji
  def getCreatureImageUrl(creature: String): String = creatureImageUrlFunc(creature)
}