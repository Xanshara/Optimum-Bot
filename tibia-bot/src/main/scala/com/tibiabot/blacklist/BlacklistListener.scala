package com.tibiabot.blacklist

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData, SubcommandData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}
import net.dv8tion.jda.api.Permission

import scala.jdk.CollectionConverters._

/**
 * Listener for /blacklist commands
 * Note: BlacklistManager is created in BotApp and passed here
 */
class BlacklistListener(blacklistManager: BlacklistManager) extends ListenerAdapter with StrictLogging {

  /**
   * Command definition for /blacklist
   */
  def command: SlashCommandData = {
    Commands.slash("blacklist", "Manage the blacklist of players")
      .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
      .addSubcommands(
        new SubcommandData("on", "Enable blacklist for this server"),
        new SubcommandData("off", "Disable blacklist for this server"),
        new SubcommandData("add", "Add a player to the blacklist")
          .addOptions(
            new OptionData(OptionType.STRING, "nick", "The player name you want to blacklist").setRequired(true),
            new OptionData(OptionType.STRING, "reason", "The reason for blacklisting this player").setRequired(true)
          ),
        new SubcommandData("delete", "Remove a player from the blacklist")
          .addOptions(
            new OptionData(OptionType.STRING, "nick", "The player name you want to remove").setRequired(true)
          ),
        new SubcommandData("list", "Show all blacklisted players with their online status")
      )
  }

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName == "blacklist") {
      handleBlacklist(event)
    }
  }

  /**
   * Handler for /blacklist command
   */
  private def handleBlacklist(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()

    val subCommand = event.getInteraction.getSubcommandName
    val options: Map[String, String] = event.getInteraction.getOptions.asScala.map(option =>
      option.getName.toLowerCase() -> option.getAsString.trim()
    ).toMap

    subCommand match {
      case "on" =>
        val embed = blacklistManager.toggleBlacklist(event, enable = true)
        event.getHook.sendMessageEmbeds(embed).queue()
        logger.info(s"User ${event.getUser.getAsTag} enabled blacklist in guild ${event.getGuild.getName}")

      case "off" =>
        val embed = blacklistManager.toggleBlacklist(event, enable = false)
        event.getHook.sendMessageEmbeds(embed).queue()
        logger.info(s"User ${event.getUser.getAsTag} disabled blacklist in guild ${event.getGuild.getName}")

      case "add" =>
        val nick = options.getOrElse("nick", "")
        val reason = options.getOrElse("reason", "")
        
        if (nick.isEmpty) {
          val embed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Player name cannot be empty.")
            .setColor(3092790)
            .build()
          event.getHook.sendMessageEmbeds(embed).queue()
          return
        }
        
        if (reason.isEmpty) {
          val embed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Reason cannot be empty.")
            .setColor(3092790)
            .build()
          event.getHook.sendMessageEmbeds(embed).queue()
          return
        }
        
        val embed = blacklistManager.addPlayer(event, nick, reason)
        event.getHook.sendMessageEmbeds(embed).queue()
        logger.info(s"User ${event.getUser.getAsTag} added ${nick} to blacklist in guild ${event.getGuild.getName}")

      case "delete" =>
        val nick = options.getOrElse("nick", "")
        
        if (nick.isEmpty) {
          val embed = new EmbedBuilder()
            .setDescription(s"${Config.noEmoji} Player name cannot be empty.")
            .setColor(3092790)
            .build()
          event.getHook.sendMessageEmbeds(embed).queue()
          return
        }
        
        val embed = blacklistManager.removePlayer(event, nick)
        event.getHook.sendMessageEmbeds(embed).queue()
        logger.info(s"User ${event.getUser.getAsTag} removed ${nick} from blacklist in guild ${event.getGuild.getName}")

      case "list" =>
        val embed = blacklistManager.showBlacklist(event)
        event.getHook.sendMessageEmbeds(embed).queue()
        logger.info(s"User ${event.getUser.getAsTag} viewed blacklist in guild ${event.getGuild.getName}")

      case _ =>
        val embed = new EmbedBuilder()
          .setDescription(s"${Config.noEmoji} Invalid subcommand '$subCommand' for `/blacklist`.")
          .setColor(3092790)
          .build()
        event.getHook.sendMessageEmbeds(embed).queue()
    }
  }
}