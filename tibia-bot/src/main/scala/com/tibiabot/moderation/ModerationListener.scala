package com.tibiabot.moderation

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.{Commands, OptionData, SlashCommandData}

import java.awt.Color
import java.time.{Duration => JDuration}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

class ModerationListener(manager: ModerationManager) extends ListenerAdapter with StrictLogging {

  // ── DEFINICJE KOMEND ──────────────────────────────────────────────

  val banCommand: SlashCommandData =
    Commands.slash("ban", "Zbanuj użytkownika serwera")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.BAN_MEMBERS))
      .addOptions(
        new OptionData(OptionType.USER,    "user",   "Użytkownik do zbanowania",         true),
        new OptionData(OptionType.INTEGER, "time",   "Czas w minutach (0 = permanentny)", true),
        new OptionData(OptionType.STRING,  "reason", "Powód bana",                        true)
      )

  val unbanCommand: SlashCommandData =
    Commands.slash("unban", "Odbanuj użytkownika")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.BAN_MEMBERS))
      .addOptions(
        new OptionData(OptionType.USER, "user", "Użytkownik do odbanowania", true)
      )

  val banListCommand: SlashCommandData =
    Commands.slash("ban_list", "Wyświetl listę aktywnych banów")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.BAN_MEMBERS))

  val muteCommand: SlashCommandData =
    Commands.slash("mute", "Wycisz użytkownika serwera")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.MODERATE_MEMBERS))
      .addOptions(
        new OptionData(OptionType.USER,    "user",   "Użytkownik do wyciszenia",           true),
        new OptionData(OptionType.INTEGER, "time",   "Czas w minutach (0 = permanentny)",   true),
        new OptionData(OptionType.STRING,  "reason", "Powód wyciszenia",                    true)
      )

  val unmuteCommand: SlashCommandData =
    Commands.slash("unmute", "Odcisz użytkownika")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.MODERATE_MEMBERS))
      .addOptions(
        new OptionData(OptionType.USER, "user", "Użytkownik do odciszenia", true)
      )

  val muteListCommand: SlashCommandData =
    Commands.slash("mute_list", "Wyświetl listę aktywnych wyciszeń")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.MODERATE_MEMBERS))

  val modLogCommand: SlashCommandData =
    Commands.slash("mod_log", "Ustaw kanał logów moderacji")
      .setDefaultPermissions(net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
        .enabledFor(Permission.MANAGE_SERVER))
      .addOptions(
        new OptionData(OptionType.CHANNEL, "channel", "Kanał, na którym będą pojawiać się logi moderacji", true)
      )

  // Wszystkie komendy jako lista — wygodne przy rejestracji w BotApp
  val allCommands: List[SlashCommandData] =
    List(banCommand, unbanCommand, banListCommand, muteCommand, unmuteCommand, muteListCommand, modLogCommand)

  // ── ROUTER ────────────────────────────────────────────────────────

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    event.getName match {
      case "ban"      => handleBan(event)
      case "unban"    => handleUnban(event)
      case "ban_list" => handleBanList(event)
      case "mute"     => handleMute(event)
      case "unmute"   => handleUnmute(event)
      case "mute_list"=> handleMuteList(event)
      case "mod_log"  => handleModLog(event)
      case _          => // ignoruj
    }
  }

  // ── BAN ───────────────────────────────────────────────────────────

  private def handleBan(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guild      = event.getGuild
    val guildId    = guild.getId
    val targetUser = event.getOption("user").getAsUser
    val duration   = event.getOption("time").getAsLong
    val reason     = event.getOption("reason").getAsString
    val moderator  = event.getUser

    // Inicjalizacja tabel (idempotentne)
    manager.initializeTables(guildId)

    // Nie można banować samego siebie
    if (targetUser.getId == moderator.getId) {
      reply(event, Color.RED, "❌ Nie możesz zbanować samego siebie.")
      return
    }

    // Sprawdź czy użytkownik jest na serwerze i czy ma wyższe uprawnienia
    val targetMember = guild.retrieveMemberById(targetUser.getId).complete()
    if (targetMember != null) {
      val executorMember = guild.getMember(moderator)
      if (executorMember != null && !executorMember.canInteract(targetMember)) {
        reply(event, Color.RED, "❌ Nie możesz zbanować użytkownika z wyższą rolą od Twojej.")
        return
      }
    }

    // Zapis do bazy
    manager.addBan(guildId, targetUser.getId, targetUser.getAsTag, reason, duration, moderator.getAsTag) match {
      case Failure(e) =>
        reply(event, Color.RED, s"❌ Błąd zapisu do bazy danych: ${e.getMessage}")

      case Success(ban) =>
        // Wyślij DM do zbanowanego — PRZED banem, bo po banie bot nie może mu pisać
        val durationText = manager.formatDuration(duration)
        val banDmEmbed = new EmbedBuilder()
          .setTitle("🔨 Otrzymałeś bana")
          .setDescription(
            s"**Serwer:** ${guild.getName}\n" +
            s"**Czas:** $durationText\n" +
            s"**Powód:** $reason"
          )
          .setColor(Color.ORANGE)
          .setTimestamp(java.time.Instant.now())
          .build()
        targetUser.openPrivateChannel().queue(
          ch => ch.sendMessageEmbeds(banDmEmbed).queue(
            _ => logger.info(s"[$guildId] DM ban wysłany do ${targetUser.getAsTag}"),
            e  => logger.warn(s"[$guildId] Nie można wysłać DM do ${targetUser.getAsTag}: ${e.getMessage}")
          ),
          e => logger.warn(s"[$guildId] Nie można otworzyć DM do ${targetUser.getAsTag}: ${e.getMessage}")
        )

        // Wykonaj Discord ban
        val reasonFull = s"[$durationText] $reason — zbanowany przez ${moderator.getAsTag}"
        guild.ban(targetUser, 0, java.util.concurrent.TimeUnit.SECONDS)
          .reason(reasonFull)
          .queue(
            _ => logger.info(s"[$guildId] Zbanowano ${targetUser.getAsTag} na $durationText"),
            e  => logger.error(s"[$guildId] Błąd Discord ban dla ${targetUser.getAsTag}", e)
          )

        // Odpowiedź dla moderatora
        reply(event, Color.ORANGE,
          s"🔨 Użytkownik **${targetUser.getAsTag}** został zbanowany.\n" +
          s"⏱️ Czas: **$durationText**\n" +
          s"📋 Powód: **$reason**"
        )

        // Log na kanał moderacji
        sendModLog(guild,
          s"🔨 Użytkownik <@${targetUser.getId}> (**${targetUser.getAsTag}**) dostał bana " +
          s"na **$durationText** za: **$reason**\n" +
          s"👮 Moderator: <@${moderator.getId}>",
          Color.ORANGE
        )
    }
  }

  // ── UNBAN ─────────────────────────────────────────────────────────

  private def handleUnban(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guild      = event.getGuild
    val guildId    = guild.getId
    val targetUser = event.getOption("user").getAsUser
    val moderator  = event.getUser

    manager.initializeTables(guildId)

    val hadActiveBan = manager.removeBan(guildId, targetUser.getId)

    // Zawsze próbuj unbanować na Discordzie (może być zbanowany, ale nie w naszej DB)
    guild.unban(targetUser).queue(
      _ => logger.info(s"[$guildId] Odbanowano ${targetUser.getAsTag}"),
      e  => logger.warn(s"[$guildId] Brak aktywnego bana Discord dla ${targetUser.getAsTag}: ${e.getMessage}")
    )

    if (hadActiveBan) {
      reply(event, Color.GREEN,
        s"✅ Użytkownik **${targetUser.getAsTag}** został odbanowany."
      )
      sendModLog(guild,
        s"✅ Użytkownik <@${targetUser.getId}> (**${targetUser.getAsTag}**) został odbanowany.\n" +
        s"👮 Moderator: <@${moderator.getId}>",
        Color.GREEN
      )
    } else {
      reply(event, Color.GRAY,
        s"ℹ️ Użytkownik **${targetUser.getAsTag}** nie miał aktywnego bana w bazie danych.\n" +
        s"Próba odbanowania na Discordzie i tak została wysłana."
      )
    }
  }

  // ── BAN_LIST ──────────────────────────────────────────────────────

  private def handleBanList(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guildId = event.getGuild.getId
    manager.initializeTables(guildId)

    val bans = manager.getActiveBans(guildId)

    if (bans.isEmpty) {
      reply(event, Color.GREEN, "✅ Brak aktywnych banów na tym serwerze.")
      return
    }

    val embed = new EmbedBuilder()
      .setTitle("🔨 Aktywne bany")
      .setColor(Color.ORANGE)
      .setFooter(s"Łącznie: ${bans.size} aktywnych banów")

    bans.take(20).foreach { ban =>
      val timeText = manager.formatDuration(ban.durationMinutes)
      val expiresText = ban.expiresAt
        .map(t => s"Wygasa: <t:${t.toEpochSecond}:R>")
        .getOrElse("Permanentny")
      embed.addField(
        s"${ban.userName}",
        s"**Powód:** ${ban.reason}\n**Czas:** $timeText\n**$expiresText**\n**Nadano przez:** ${ban.bannedBy}",
        false
      )
    }

    if (bans.size > 20) {
      embed.setDescription(s"_Wyświetlono 20 z ${bans.size} banów._")
    }

    event.getHook.sendMessageEmbeds(embed.build()).queue()
  }

  // ── MUTE ──────────────────────────────────────────────────────────

  private def handleMute(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guild      = event.getGuild
    val guildId    = guild.getId
    val targetUser = event.getOption("user").getAsUser
    val duration   = event.getOption("time").getAsLong
    val reason     = event.getOption("reason").getAsString
    val moderator  = event.getUser

    manager.initializeTables(guildId)

    if (targetUser.getId == moderator.getId) {
      reply(event, Color.RED, "❌ Nie możesz wyciszyć samego siebie.")
      return
    }

    val targetMember = guild.retrieveMemberById(targetUser.getId).complete()
    if (targetMember == null) {
      reply(event, Color.RED, "❌ Nie znaleziono tego użytkownika na serwerze.")
      return
    }

    val executorMember = guild.getMember(moderator)
    if (executorMember != null && !executorMember.canInteract(targetMember)) {
      reply(event, Color.RED, "❌ Nie możesz wyciszyć użytkownika z wyższą rolą od Twojej.")
      return
    }

    // Zapis do bazy
    manager.addMute(guildId, targetUser.getId, targetUser.getAsTag, reason, duration, moderator.getAsTag) match {
      case Failure(e) =>
        reply(event, Color.RED, s"❌ Błąd zapisu do bazy danych: ${e.getMessage}")

      case Success(_) =>
        val durationText = manager.formatDuration(duration)

        // Wyślij DM do wyciszonego użytkownika
        val muteDmEmbed = new EmbedBuilder()
          .setTitle("🔇 Zostałeś wyciszony")
          .setDescription(
            s"**Serwer:** ${guild.getName}\n" +
            s"**Czas:** $durationText\n" +
            s"**Powód:** $reason"
          )
          .setColor(Color.YELLOW)
          .setTimestamp(java.time.Instant.now())
          .build()
        targetUser.openPrivateChannel().queue(
          ch => ch.sendMessageEmbeds(muteDmEmbed).queue(
            _ => logger.info(s"[$guildId] DM mute wysłany do ${targetUser.getAsTag}"),
            e  => logger.warn(s"[$guildId] Nie można wysłać DM do ${targetUser.getAsTag}: ${e.getMessage}")
          ),
          e => logger.warn(s"[$guildId] Nie można otworzyć DM do ${targetUser.getAsTag}: ${e.getMessage}")
        )

        // Discord Timeout (max 28 dni = 40320 min)
        // Permanentny / > 28 dni → przypisz rolę Muted
        val MAX_TIMEOUT_MINUTES = 40320L

        if (duration == 0 || duration > MAX_TIMEOUT_MINUTES) {
          // Użyj roli "Muted" dla permanentnych / bardzo długich
          ensureMutedRole(guild) match {
            case Some(mutedRole) =>
              guild.addRoleToMember(targetMember, mutedRole)
                .reason(s"Mute: $reason — przez ${moderator.getAsTag}")
                .queue(
                  _ => logger.info(s"[$guildId] Nadano rolę Muted dla ${targetUser.getAsTag}"),
                  e  => logger.error(s"[$guildId] Błąd nadawania roli Muted", e)
                )
            case None =>
              logger.warn(s"[$guildId] Nie udało się utworzyć roli Muted")
          }
        } else {
          // Discord natywny timeout
          targetMember.timeoutFor(JDuration.ofMinutes(duration))
            .reason(s"Mute [$durationText]: $reason — przez ${moderator.getAsTag}")
            .queue(
              _ => logger.info(s"[$guildId] Timeout dla ${targetUser.getAsTag} na $durationText"),
              e  => logger.error(s"[$guildId] Błąd timeout dla ${targetUser.getAsTag}", e)
            )
        }

        reply(event, Color.YELLOW,
          s"🔇 Użytkownik **${targetUser.getAsTag}** został wyciszony.\n" +
          s"⏱️ Czas: **$durationText**\n" +
          s"📋 Powód: **$reason**"
        )

        sendModLog(guild,
          s"🔇 Użytkownik <@${targetUser.getId}> (**${targetUser.getAsTag}**) dostał muta " +
          s"na **$durationText** za: **$reason**\n" +
          s"👮 Moderator: <@${moderator.getId}>",
          Color.YELLOW
        )
    }
  }

  // ── UNMUTE ────────────────────────────────────────────────────────

  private def handleUnmute(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guild      = event.getGuild
    val guildId    = guild.getId
    val targetUser = event.getOption("user").getAsUser
    val moderator  = event.getUser

    manager.initializeTables(guildId)

    val hadActiveMute = manager.removeMute(guildId, targetUser.getId)

    val targetMember = guild.retrieveMemberById(targetUser.getId).complete()
    if (targetMember != null) {
      // Usuń Discord timeout
      targetMember.removeTimeout()
        .reason(s"Unmute przez ${moderator.getAsTag}")
        .queue(
          _ => logger.info(s"[$guildId] Usunięto timeout dla ${targetUser.getAsTag}"),
          e  => logger.debug(s"[$guildId] Brak aktywnego timeout (OK): ${e.getMessage}")
        )

      // Usuń rolę Muted (jeśli istnieje)
      guild.getRolesByName("Muted", true).asScala.headOption.foreach { mutedRole =>
        if (targetMember.getRoles.asScala.contains(mutedRole)) {
          guild.removeRoleFromMember(targetMember, mutedRole)
            .reason(s"Unmute przez ${moderator.getAsTag}")
            .queue(
              _ => logger.info(s"[$guildId] Usunięto rolę Muted od ${targetUser.getAsTag}"),
              e  => logger.error(s"[$guildId] Błąd usuwania roli Muted", e)
            )
        }
      }
    }

    if (hadActiveMute) {
      reply(event, Color.GREEN,
        s"✅ Użytkownik **${targetUser.getAsTag}** został odciszony."
      )
      sendModLog(guild,
        s"✅ Użytkownik <@${targetUser.getId}> (**${targetUser.getAsTag}**) został odciszony.\n" +
        s"👮 Moderator: <@${moderator.getId}>",
        Color.GREEN
      )
    } else {
      reply(event, Color.GRAY,
        s"ℹ️ Użytkownik **${targetUser.getAsTag}** nie miał aktywnego muta w bazie danych.\n" +
        s"Próba odciszenia i tak została wysłana."
      )
    }
  }

  // ── MUTE_LIST ─────────────────────────────────────────────────────

  private def handleMuteList(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guildId = event.getGuild.getId
    manager.initializeTables(guildId)

    val mutes = manager.getActiveMutes(guildId)

    if (mutes.isEmpty) {
      reply(event, Color.GREEN, "✅ Brak aktywnych wyciszeń na tym serwerze.")
      return
    }

    val embed = new EmbedBuilder()
      .setTitle("🔇 Aktywne wyciszenia")
      .setColor(Color.YELLOW)
      .setFooter(s"Łącznie: ${mutes.size} aktywnych wyciszeń")

    mutes.take(20).foreach { mute =>
      val timeText = manager.formatDuration(mute.durationMinutes)
      val expiresText = mute.expiresAt
        .map(t => s"Wygasa: <t:${t.toEpochSecond}:R>")
        .getOrElse("Permanentny")
      embed.addField(
        s"${mute.userName}",
        s"**Powód:** ${mute.reason}\n**Czas:** $timeText\n**$expiresText**\n**Nadano przez:** ${mute.mutedBy}",
        false
      )
    }

    if (mutes.size > 20) {
      embed.setDescription(s"_Wyświetlono 20 z ${mutes.size} wyciszeń._")
    }

    event.getHook.sendMessageEmbeds(embed.build()).queue()
  }

  // ── MOD_LOG SETUP ─────────────────────────────────────────────────

  private def handleModLog(event: SlashCommandInteractionEvent): Unit = {
    event.deferReply(true).queue()
    val guildId = event.getGuild.getId
    manager.initializeTables(guildId)

    val channel = event.getOption("channel").getAsChannel
    val channelId = channel.getId

    val ok = manager.setModerationChannel(guildId, channelId)
    if (ok) {
      reply(event, Color.GREEN,
        s"✅ Kanał logów moderacji ustawiony na <#$channelId>.\n" +
        s"Wszystkie akcje ban/mute będą tam logowane."
      )
    } else {
      reply(event, Color.RED, "❌ Błąd zapisu kanału logów. Upewnij się, że bot jest już skonfigurowany (`/setup`).")
    }
  }

  // ── HELPERS ───────────────────────────────────────────────────────

  /** Wyślij embed jako ephemeral reply. */
  private def reply(event: SlashCommandInteractionEvent, color: Color, description: String): Unit = {
    val embed = new EmbedBuilder()
      .setDescription(description)
      .setColor(color)
      .build()
    event.getHook.sendMessageEmbeds(embed).setEphemeral(true).queue()
  }

  /** Wyślij wiadomość na skonfigurowany kanał moderacji. */
  private def sendModLog(guild: Guild, message: String, color: Color): Unit = {
    val channelId = manager.getModerationChannel(guild.getId)
    if (channelId == null || channelId == "0" || channelId.isBlank) return

    val channel: TextChannel = guild.getTextChannelById(channelId)
    if (channel == null || !channel.canTalk()) {
      logger.warn(s"[${guild.getId}] Kanał mod-log $channelId niedostępny")
      return
    }

    val embed = new EmbedBuilder()
      .setDescription(message)
      .setColor(color)
      .setTimestamp(java.time.Instant.now())
      .setFooter("Optimum Bot — Moderacja", Config.webHookAvatar)
      .build()

    channel.sendMessageEmbeds(embed).queue(
      _ => logger.debug(s"[${guild.getId}] Mod-log wysłany"),
      e  => logger.error(s"[${guild.getId}] Błąd wysyłania mod-log", e)
    )
  }

  /**
   * Znajdź lub utwórz rolę "Muted" z odpowiednimi deny permisja.
   * Używane tylko dla permanentnych / bardzo długich mutów.
   */
  private def ensureMutedRole(guild: Guild): Option[net.dv8tion.jda.api.entities.Role] = {
    try {
      guild.getRolesByName("Muted", true).asScala.headOption.orElse {
        val role = guild.createRole()
          .setName("Muted")
          .setColor(Color.GRAY)
          .setMentionable(false)
          .setHoisted(false)
          .complete()

        // Zablokuj pisanie na wszystkich kanałach tekstowych
        guild.getTextChannels.asScala.foreach { ch =>
          try {
            ch.upsertPermissionOverride(role)
              .deny(Permission.MESSAGE_SEND, Permission.MESSAGE_ADD_REACTION)
              .queue()
          } catch {
            case e: Exception => logger.debug(s"Skip perm override for ${ch.getName}: ${e.getMessage}")
          }
        }

        logger.info(s"[${guild.getId}] Utworzono rolę Muted")
        Some(role)
      }
    } catch {
      case e: Exception =>
        logger.error(s"[${guild.getId}] Błąd tworzenia roli Muted", e)
        None
    }
  }
}
