package com.tibiabot.giveaway

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SubcommandData, SlashCommandData}
import net.dv8tion.jda.api.interactions.commands.{DefaultMemberPermissions, OptionType}
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel

import java.awt.Color
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * Komendy slash /giveaway:
 *   start  – utwórz giveaway (+ opcjonalna rola do oznaczenia)
 *   end    – zakończ przed czasem
 *   reroll – nowi zwycięzcy
 *   list   – aktywne giveawaye
 *   delete – usuń bez losowania
 */
class GiveawayListener(manager: GiveawayManager, scheduler: GiveawayScheduler)
                      (implicit ec: ExecutionContext)
    extends ListenerAdapter with StrictLogging {

  // ─── Definicja komendy ────────────────────────────────────────────────────

  def command: SlashCommandData =
    Commands.slash("giveaway", "Zarządzaj konkursami (giveaway) na serwerze")
      .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER))
      .setGuildOnly(true)
      .addSubcommands(

        new SubcommandData("start", "Utwórz nowy giveaway")
          .addOptions(
            new OptionData(OptionType.STRING,  "czas",       "Czas trwania, np. 10m, 2h, 1d",    true),
            new OptionData(OptionType.INTEGER, "zwyciezcy",  "Liczba zwycięzców (1–20)",           true)
              .setMinValue(1).setMaxValue(20),
            new OptionData(OptionType.STRING,  "nagroda",    "Co można wygrać?",                  true),
            new OptionData(OptionType.STRING,  "tytul",      "Tytuł giveaway (np. Konkurs świąteczny)", false),
            new OptionData(OptionType.STRING,  "opis",       "Dodatkowy opis / warunki (opcjonalny)", false),
            new OptionData(OptionType.ROLE,    "rola",       "Rola oznaczona w wiadomości (opcjonalnie)", false)
          ),

        new SubcommandData("end", "Zakończ giveaway przed czasem")
          .addOptions(new OptionData(OptionType.STRING, "id", "ID wiadomości giveaway", true)),

        new SubcommandData("reroll", "Wylosuj nowych zwycięzców zakończonego giveaway")
          .addOptions(
            new OptionData(OptionType.STRING,  "id",     "ID wiadomości giveaway",              true),
            new OptionData(OptionType.INTEGER, "liczba", "Ile nowych zwycięzców (domyślnie 1)", false)
              .setMinValue(1).setMaxValue(20)
          ),

        new SubcommandData("list", "Pokaż aktywne giveawaye na tym serwerze"),

        new SubcommandData("delete", "Usuń giveaway (bez losowania zwycięzców)")
          .addOptions(new OptionData(OptionType.STRING, "id", "ID wiadomości giveaway", true))
      )

  // ─── Routing ──────────────────────────────────────────────────────────────

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName != "giveaway") return
    event.getSubcommandName match {
      case "start"  => handleStart(event)
      case "end"    => handleEnd(event)
      case "reroll" => handleReroll(event)
      case "list"   => handleList(event)
      case "delete" => handleDelete(event)
      case other    =>
        event.reply(s"${Config.noEmoji} Nieznana subkomenda: `$other`").setEphemeral(true).queue()
    }
  }

  // ─── /giveaway start ──────────────────────────────────────────────────────

  private def handleStart(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()

    val opts     = event.getOptions.asScala.map(o => o.getName -> o).toMap
    val timeStr  = opts("czas").getAsString.trim
    val winners  = opts("zwyciezcy").getAsLong.toInt
    val prize    = opts("nagroda").getAsString.trim
    val titleOpt = opts.get("tytul").map(_.getAsString.trim).filter(_.nonEmpty)
    val descOpt  = opts.get("opis").map(_.getAsString.trim).filter(_.nonEmpty)
    val roleOpt  = opts.get("rola").map(_.getAsRole.getIdLong)

    if (prize.length > 250) {
      event.getHook.sendMessage(s"${Config.noEmoji} Nazwa nagrody nie może przekraczać 250 znaków.").queue()
      return
    }

    val seconds = parseTime(timeStr)
    if (seconds <= 0) {
      event.getHook.sendMessage(
        s"${Config.noEmoji} Nie rozpoznaję formatu czasu `$timeStr`.\n" +
        "Użyj np. `10m`, `2h`, `1d`, `30s` lub `1h30m`."
      ).queue()
      return
    }
    if (seconds < 10) {
      event.getHook.sendMessage(s"${Config.noEmoji} Minimalny czas giveaway to **10 sekund**.").queue()
      return
    }
    if (seconds > 60 * 60 * 24 * 30) {
      event.getHook.sendMessage(s"${Config.noEmoji} Maksymalny czas giveaway to **30 dni**.").queue()
      return
    }

    val endTime   = Instant.now().getEpochSecond + seconds
    val guildId   = event.getGuild.getIdLong
    val channelId = event.getChannel.getIdLong
    val userId    = event.getUser.getIdLong

    val gTemp = Giveaway(
      messageId   = 0L,
      channelId   = channelId,
      guildId     = guildId,
      userId      = userId,
      endTime     = endTime,
      winners     = winners,
      prize       = prize,
      description = descOpt,
      roleId      = roleOpt,
      title       = titleOpt,
      ended       = false
    )

    val embed  = manager.buildActiveEmbed(gTemp, 0).build()
    val button = manager.enterButton()

    // Treść wiadomości — jeśli jest rola, oznacz ją
    val content = roleOpt.map(r => s"<@&$r>").getOrElse("")

    val sendAction = if (content.nonEmpty)
      event.getChannel.sendMessage(content).setEmbeds(embed).setComponents(ActionRow.of(button))
    else
      event.getChannel.sendMessageEmbeds(embed).setComponents(ActionRow.of(button))

    sendAction.queue(
      msg => {
        val g = gTemp.copy(messageId = msg.getIdLong)
        manager.saveGiveaway(g)
        event.getHook.sendMessage(s"✅ Giveaway utworzony! ID: `${msg.getIdLong}`").queue()
        logger.info(s"Giveaway ${msg.getIdLong} ('$prize') na guild $guildId")
      },
      err => {
        logger.error(s"Błąd tworzenia giveaway na guild $guildId: ${err.getMessage}")
        event.getHook.sendMessage(s"${Config.noEmoji} Błąd: ${err.getMessage}").queue()
      }
    )
  }

  // ─── /giveaway end ────────────────────────────────────────────────────────

  private def handleEnd(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val idStr = event.getOption("id").getAsString.trim
    parseMessageId(idStr) match {
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Nieprawidłowe ID: `$idStr`").queue()
      case Some(msgId) =>
        manager.getGiveaway(msgId) match {
          case None =>
            event.getHook.sendMessage(s"${Config.noEmoji} Nie znaleziono giveaway `$msgId`.").queue()
          case Some(g) if g.guildId != event.getGuild.getIdLong =>
            event.getHook.sendMessage(s"${Config.noEmoji} Ten giveaway nie należy do tego serwera.").queue()
          case Some(g) if g.ended =>
            event.getHook.sendMessage(s"${Config.noEmoji} Ten giveaway już jest zakończony.").queue()
          case Some(g) =>
            scheduler.endGiveaway(g)
            event.getHook.sendMessage(s"✅ Giveaway `$msgId` zakończony!").queue()
        }
    }
  }

  // ─── /giveaway reroll ─────────────────────────────────────────────────────

  private def handleReroll(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(false).queue()
    val idStr = event.getOption("id").getAsString.trim
    val count = Option(event.getOption("liczba")).map(_.getAsLong.toInt).getOrElse(1)

    parseMessageId(idStr) match {
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Nieprawidłowe ID: `$idStr`").queue()
      case Some(msgId) =>
        manager.getGiveaway(msgId) match {
          case None =>
            event.getHook.sendMessage(s"${Config.noEmoji} Nie znaleziono giveaway `$msgId`.").queue()
          case Some(g) if g.guildId != event.getGuild.getIdLong =>
            event.getHook.sendMessage(s"${Config.noEmoji} Ten giveaway nie należy do tego serwera.").queue()
          case Some(g) =>
            val entries = manager.getEntries(msgId)
            if (entries.isEmpty) {
              event.getHook.sendMessage("⚠️ Brak uczestników — nie można losować.").queue()
            } else {
              val winners  = manager.pickWinners(entries, count)
              val mentions = manager.formatWinnerMention(winners)
              event.getHook.sendMessage(
                s"🎲 **${event.getUser.getAsMention} ponownie losuje!**\n" +
                s"🎊 Nowi zwycięzcy **${g.prize}**: $mentions — Gratulacje! 🎉"
              ).queue()
            }
        }
    }
  }

  // ─── /giveaway list ───────────────────────────────────────────────────────

  private def handleList(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val giveaways = manager.getActiveGiveawaysByGuild(event.getGuild.getIdLong)
    if (giveaways.isEmpty) {
      event.getHook.sendMessage("📋 Brak aktywnych giveawayów na tym serwerze.").queue()
      return
    }
    val eb = new EmbedBuilder().setTitle("🎉 Aktywne giveawaye").setColor(new Color(0xFF6600))
    giveaways.foreach { g =>
      val count = manager.countEntries(g.messageId)
      eb.addField(
        g.prize,
        s"⏰ Koniec: <t:${g.endTime}:R>\n🎫 Uczestników: **$count**\n🏆 Zwycięzców: **${g.winners}**\n🔗 ID: `${g.messageId}`",
        false
      )
    }
    event.getHook.sendMessageEmbeds(eb.build()).queue()
  }

  // ─── /giveaway delete ─────────────────────────────────────────────────────

  private def handleDelete(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val idStr = event.getOption("id").getAsString.trim
    parseMessageId(idStr) match {
      case None =>
        event.getHook.sendMessage(s"${Config.noEmoji} Nieprawidłowe ID: `$idStr`").queue()
      case Some(msgId) =>
        manager.getGiveaway(msgId) match {
          case None =>
            event.getHook.sendMessage(s"${Config.noEmoji} Nie znaleziono giveaway `$msgId`.").queue()
          case Some(g) if g.guildId != event.getGuild.getIdLong =>
            event.getHook.sendMessage(s"${Config.noEmoji} Ten giveaway nie należy do tego serwera.").queue()
          case Some(g) =>
            manager.deleteGiveaway(msgId)
            Try {
              Option(event.getGuild.getTextChannelById(g.channelId))
                .orElse(Option(event.getGuild.getThreadChannelById(g.channelId)))
                .foreach { ch =>
                  ch.asInstanceOf[MessageChannel].deleteMessageById(msgId).queue(_ => (), _ => ())
                }
            }
            event.getHook.sendMessage(s"✅ Giveaway `$msgId` usunięty.").queue()
        }
    }
  }

  // ─── Helpers ──────────────────────────────────────────────────────────────

  private def parseTime(input: String): Int = {
    val cleaned = input.toLowerCase
      .replaceAll("(sekund[ay]?|seconds?|secs?)", "s")
      .replaceAll("(minut[ay]?|minutes?|mins?)",  "m")
      .replaceAll("(godzin[ay]?|hours?|hrs?)",    "h")
      .replaceAll("(dni|day[s]?)",                "d")
      .replaceAll("[^0-9smhd]", "")
    if (cleaned.isEmpty) return -1
    val pattern = "(\\d+)([smhd])".r
    val matches = pattern.findAllMatchIn(cleaned).toList
    if (matches.isEmpty) Try(cleaned.toInt).getOrElse(-1)
    else matches.map { m =>
      val n = m.group(1).toInt
      m.group(2) match {
        case "s" => n
        case "m" => n * 60
        case "h" => n * 3600
        case "d" => n * 86400
        case _   => 0
      }
    }.sum
  }

  private def parseMessageId(s: String): Option[Long] =
    Try(s.toLong).filter(_ > 0).toOption
}
