package com.tibiabot.radio

import com.sedmelluq.discord.lavaplayer.player.{AudioPlayer, AudioPlayerManager, DefaultAudioPlayerManager}
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.track.{AudioTrack, AudioTrackEndReason}
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.audio.AudioSendHandler
import java.nio.ByteBuffer
import scala.collection.concurrent.TrieMap

object AudioManager extends StrictLogging {

  private val playerManager: AudioPlayerManager = new DefaultAudioPlayerManager()

  AudioSourceManagers.registerRemoteSources(playerManager)
  AudioSourceManagers.registerLocalSource(playerManager)

  private val players       = TrieMap[Long, AudioPlayer]()
  private val streamUrls    = TrieMap[Long, String]()
  private val voiceChannels    = TrieMap[Long, Long]()
  private val lastReconnectAt  = TrieMap[Long, Long]()   // guildId -> timestamp ms   // guildId -> channelId

  // Ustawiane raz po jda.awaitReady() w BotApp
  private var jdaRef: Option[JDA] = None

  def setJda(jda: JDA): Unit = {
    jdaRef = Some(jda)
    logger.info("AudioManager: JDA reference set")
  }

  // ── PUBLIC API ────────────────────────────────────────────────────

  def getPlayer(guildId: Long): AudioPlayer = {
    players.getOrElseUpdate(guildId, {
      val player = playerManager.createPlayer()
      player.setVolume(100)

      player.addListener(new AudioEventAdapter {
        override def onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason): Unit = {
          if (endReason == AudioTrackEndReason.STOPPED || endReason == AudioTrackEndReason.REPLACED) {
            logger.info(s"[$guildId] Stream zatrzymany/zastąpiony — nie restartuję ($endReason)")
            return
          }
          logger.warn(s"[$guildId] Stream zakończony ($endReason), restartuję...")
          streamUrls.get(guildId).foreach { url =>
            Thread.sleep(2000)
            reconnectAndReload(guildId, url)
          }
        }

        override def onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException): Unit =
          logger.error(s"[$guildId] Błąd odtwarzania: ${exception.getMessage}")

        override def onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long): Unit = {
          logger.warn(s"[$guildId] Stream zablokowany (${thresholdMs}ms), restartuję...")
          streamUrls.get(guildId).foreach { url =>
            Thread.sleep(2000)
            reconnectAndReload(guildId, url)
          }
        }
      })

      logger.info(s"Utworzono nowy AudioPlayer dla guild $guildId")
      player
    })
  }

  /** Używane przez RadioCommand — zna channelId */
  def loadAndPlay(guildId: Long, trackUrl: String, channelId: Long, onSuccess: AudioTrack => Unit, onError: String => Unit): Unit = {
    logger.info(s"[$guildId] Ładowanie streamu: $trackUrl")
    streamUrls.put(guildId, trackUrl)
    voiceChannels.put(guildId, channelId)
    doLoad(guildId, trackUrl, onSuccess, onError)
  }

  /** Używane przez BotApp (auto-reconnect) — channelId jest już w voiceChannels */
  def loadAndPlay(guildId: Long, trackUrl: String, onSuccess: AudioTrack => Unit, onError: String => Unit): Unit = {
    streamUrls.put(guildId, trackUrl)
    doLoad(guildId, trackUrl, onSuccess, onError)
  }

  def stopPlayer(guildId: Long): Unit = {
    streamUrls.remove(guildId)
    voiceChannels.remove(guildId)
    players.get(guildId).foreach { p => p.stopTrack(); logger.info(s"[$guildId] Player zatrzymany") }
  }

  def setVolume(guildId: Long, volume: Int): Unit =
    players.get(guildId).foreach(_.setVolume(Math.max(0, Math.min(100, volume))))

  def getVolume(guildId: Long): Option[Int] =
    players.get(guildId).map(_.getVolume)

  def isPlaying(guildId: Long): Boolean =
    players.get(guildId).exists(_.getPlayingTrack != null)

  def getAudioSendHandler(guildId: Long): AudioSendHandler =
    new LavaPlayerAudioSendHandler(getPlayer(guildId))

  // ── PRIVATE ──────────────────────────────────────────────────────

  private def reconnectAndReload(guildId: Long, url: String): Unit = {
    // Cooldown 30s — zapobiega pętli reconnectów
    val now  = System.currentTimeMillis()
    val last = lastReconnectAt.getOrElse(guildId, 0L)
    if (now - last < 30000L) {
      logger.warn(s"[$guildId] Reconnect cooldown aktywny (${(now - last) / 1000}s < 30s), pomijam")
      return
    }
    lastReconnectAt.put(guildId, now)

    jdaRef match {
      case None =>
        logger.warn(s"[$guildId] Brak JDA ref — tylko reload")
        doReload(guildId, url)

      case Some(jda) =>
        val guild = jda.getGuildById(guildId)
        if (guild == null) { logger.warn(s"[$guildId] Guild nie znaleziony"); return }

        val audioManager = guild.getAudioManager

        if (!audioManager.isConnected) {
          // Bot wypadł z kanału — reconnect raz z dłuższym opóźnieniem
          voiceChannels.get(guildId).filter(_ != 0L) match {
            case Some(channelId) =>
              val channel = guild.getVoiceChannelById(channelId)
              if (channel != null) {
                logger.info(s"[$guildId] Reconnect do kanału ${channel.getName}")
                audioManager.setSendingHandler(getAudioSendHandler(guildId))
                audioManager.openAudioConnection(channel)
                Thread.sleep(3000) // Dłuższe opóźnienie — dajemy czas JDA na połączenie
              } else logger.warn(s"[$guildId] Kanał $channelId nie istnieje")
            case _ => logger.warn(s"[$guildId] Brak zapisanego channelId")
          }
        } else {
          // Bot JEST na kanale — nie ruszaj połączenia, tylko przeładuj stream
          logger.info(s"[$guildId] Bot na kanale, tylko przeładowuję stream")
        }

        doReload(guildId, url)
    }
  }

  private def doLoad(guildId: Long, url: String, onSuccess: AudioTrack => Unit, onError: String => Unit): Unit = {
    playerManager.loadItem(url, new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler {
      override def loadFailed(e: FriendlyException): Unit = { logger.error(s"[$guildId] Błąd ładowania: ${e.getMessage}"); onError(s"Nie udało się załadować: ${e.getMessage}") }
      override def noMatches(): Unit = { logger.warn(s"[$guildId] Nie znaleziono streamu: $url"); onError("Nie znaleziono streamu") }
      override def trackLoaded(track: AudioTrack): Unit = { getPlayer(guildId).playTrack(track); logger.info(s"[$guildId] Stream załadowany: ${track.getInfo.title}"); onSuccess(track) }
      override def playlistLoaded(playlist: com.sedmelluq.discord.lavaplayer.track.AudioPlaylist): Unit = {
        if (!playlist.getTracks.isEmpty) { val t = playlist.getTracks.get(0); getPlayer(guildId).playTrack(t); logger.info(s"[$guildId] Playlista: ${t.getInfo.title}"); onSuccess(t) }
        else onError("Pusta playlista")
      }
    })
  }

  private def doReload(guildId: Long, url: String): Unit =
    doLoad(guildId, url, t => logger.info(s"[$guildId] Auto-restart OK: ${t.getInfo.title}"), e => logger.error(s"[$guildId] Auto-restart nieudany: $e"))
}

class LavaPlayerAudioSendHandler(player: AudioPlayer) extends AudioSendHandler {
  private val SILENCE = ByteBuffer.wrap(Array[Byte](0xF8.toByte, 0xFF.toByte, 0xFE.toByte))
  private var lastFrame: com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame = _

  override def canProvide(): Boolean = {
    lastFrame = player.provide()
    true // Zawsze true — cisza zamiast rozłączenia
  }

  override def provide20MsAudio(): ByteBuffer =
    if (lastFrame != null) ByteBuffer.wrap(lastFrame.getData)
    else SILENCE.duplicate()

  override def isOpus: Boolean = true
}