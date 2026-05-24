package com.tibiabot.radio

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import com.typesafe.scalalogging.LazyLogging

class RadioCommand extends ListenerAdapter with LazyLogging {

  private val DEFAULT_STREAM_URL = "https://rs9-krk2-cyfronet.rmfstream.pl/RMFFM48"

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    if (event.getName != "radio") return

    val action = event.getOption("action")
    if (action == null) {
      event.reply("❌ Musisz wybrać akcję: on lub off").setEphemeral(true).queue()
      return
    }

    action.getAsString.toLowerCase match {
      case "on"  => handleRadioOn(event)
      case "off" => handleRadioOff(event)
      case _     => event.reply("❌ Nieprawidłowa akcja! Użyj: on lub off").setEphemeral(true).queue()
    }
  }

  private def handleRadioOn(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      event.reply("❌ Ta komenda działa tylko na serwerze!").setEphemeral(true).queue()
      return
    }

    val channelOption = event.getOption("channel")
    if (channelOption == null) {
      event.reply("❌ Musisz wybrać kanał głosowy!").setEphemeral(true).queue()
      return
    }

    val selectedChannel: GuildChannelUnion = channelOption.getAsChannel

    if (!selectedChannel.getType.isAudio) {
      event.reply("❌ Musisz wybrać kanał głosowy, a nie tekstowy!").setEphemeral(true).queue()
      return
    }

    val voiceChannel = selectedChannel.asVoiceChannel()

    val urlOption = event.getOption("url")
    val streamUrl = if (urlOption != null) {
      val customUrl = urlOption.getAsString.trim
      logger.info(s"Użytkownik podał własny URL: $customUrl")
      customUrl
    } else {
      logger.info(s"Używam domyślnego URL: $DEFAULT_STREAM_URL")
      DEFAULT_STREAM_URL
    }

    event.deferReply().queue()
    logger.info(s"Włączanie Radio na kanale ${voiceChannel.getName} (${voiceChannel.getId}) w guild ${guild.getName} (${guild.getId})")

    val audioManager = guild.getAudioManager
    audioManager.openAudioConnection(voiceChannel)
    audioManager.setSendingHandler(AudioManager.getAudioSendHandler(guild.getIdLong))

    // WAŻNE: przekazujemy voiceChannel.getIdLong żeby AudioManager wiedział gdzie reconnectować
    AudioManager.loadAndPlay(
      guild.getIdLong,
      streamUrl,
      voiceChannel.getIdLong,
      track => {
        logger.info(s"Radio uruchomione pomyślnie na guild ${guild.getId}")
        val streamName = track.getInfo.title

        RadioStateRepository.saveRadioState(
          guild.getIdLong,
          voiceChannel.getIdLong,
          streamUrl
        ) match {
          case scala.util.Success(_) =>
            logger.info(s"✅ Stan radia zapisany do bazy dla guild ${guild.getId}")
          case scala.util.Failure(e) =>
            logger.warn(s"⚠️ Nie udało się zapisać stanu radia: ${e.getMessage}")
        }

        event.getHook.sendMessage(
          s"✅ **Radio** włączone na kanale **${voiceChannel.getName}**!\n" +
          s"🎵 Odtwarzanie: **$streamName**\n" +
          s"🔗 Stream: `$streamUrl`\n" +
          s"🔄 Radio będzie automatycznie wznowione po restarcie bota!"
        ).queue()
      },
      error => {
        logger.error(s"Błąd uruchamiania radia na guild ${guild.getId}: $error")
        audioManager.closeAudioConnection()
        event.getHook.sendMessage(
          s"❌ **Błąd:** Nie udało się załadować: $error\n" +
          "Spróbuj ponownie za chwilę. Jeśli problem się powtarza, skontaktuj się z administratorem bota."
        ).queue()
      }
    )
  }

  private def handleRadioOff(event: SlashCommandInteractionEvent): Unit = {
    val guild = event.getGuild
    if (guild == null) {
      event.reply("❌ Ta komenda działa tylko na serwerze!").setEphemeral(true).queue()
      return
    }

    logger.info(s"Wyłączanie Radio w guild ${guild.getName} (${guild.getId})")

    val audioManager = guild.getAudioManager

    if (!audioManager.isConnected) {
      event.reply("ℹ️ Bot nie jest obecnie podłączony do żadnego kanału głosowego.").setEphemeral(true).queue()
      return
    }

    event.deferReply().queue()

    AudioManager.stopPlayer(guild.getIdLong)
    audioManager.closeAudioConnection()

    RadioStateRepository.removeRadioState(guild.getIdLong) match {
      case scala.util.Success(_) =>
        logger.info(s"✅ Stan radia usunięty z bazy dla guild ${guild.getId}")
      case scala.util.Failure(e) =>
        logger.warn(s"⚠️ Nie udało się usunąć stanu radia: ${e.getMessage}")
    }

    event.getHook.sendMessage("✅ **Radio wyłączone** - bot rozłączył się z kanału głosowego.").queue()
    logger.info(s"Radio wyłączone pomyślnie na guild ${guild.getId}")
  }
}
