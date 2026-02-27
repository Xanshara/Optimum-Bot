package com.tibiabot.radio

import com.sedmelluq.discord.lavaplayer.player.{AudioPlayer, AudioPlayerManager, DefaultAudioPlayerManager}
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.track.{AudioTrack, AudioTrackEndReason}
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.audio.AudioSendHandler
import java.nio.ByteBuffer
import scala.collection.concurrent.TrieMap

/**
 * Manager do obsługi audio przez LavaPlayer
 * Zarządza playerami dla każdego serwera Discord
 */
object AudioManager extends StrictLogging {
  
  private val playerManager: AudioPlayerManager = new DefaultAudioPlayerManager()
  
  // Konfiguracja LavaPlayer - rejestracja źródeł audio
  AudioSourceManagers.registerRemoteSources(playerManager)
  AudioSourceManagers.registerLocalSource(playerManager)
  
  // Mapa graczy - jeden player na guild
  private val players = TrieMap[Long, AudioPlayer]()
  
  /**
   * Pobiera lub tworzy player dla danego serwera
   * @param guildId ID serwera Discord
   * @return AudioPlayer dla tego serwera
   */
  def getPlayer(guildId: Long): AudioPlayer = {
    players.getOrElseUpdate(guildId, {
      val player = playerManager.createPlayer()
      player.setVolume(100) // Domyślna głośność 100%
      
      player.addListener(new AudioEventAdapter {
        override def onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason): Unit = {
          if (endReason.mayStartNext) {
            logger.info(s"Track zakończony na guild $guildId: ${track.getInfo.title}")
          }
        }
        
        override def onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException): Unit = {
          logger.error(s"Błąd odtwarzania na guild $guildId: ${exception.getMessage}")
        }
      })
      
      logger.info(s"Utworzono nowy AudioPlayer dla guild $guildId")
      player
    })
  }
  
  /**
   * Ładuje i odtwarza stream z podanego URL
   * @param guildId ID serwera
   * @param trackUrl URL do streamu (np. Radio Eska)
   * @param onSuccess callback po udanym załadowaniu
   * @param onError callback po błędzie
   */
  def loadAndPlay(
    guildId: Long, 
    trackUrl: String, 
    onSuccess: AudioTrack => Unit, 
    onError: String => Unit
  ): Unit = {
    logger.info(s"Próba załadowania streamu dla guild $guildId: $trackUrl")
    
    playerManager.loadItem(trackUrl, new com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler {
      override def loadFailed(exception: FriendlyException): Unit = {
        logger.error(s"Nie udało się załadować streamu: ${exception.getMessage}")
        onError(s"Nie udało się załadować: ${exception.getMessage}")
      }
      
      override def noMatches(): Unit = {
        logger.warn(s"Nie znaleziono streamu: $trackUrl")
        onError("Nie znaleziono streamu")
      }
      
      override def trackLoaded(track: AudioTrack): Unit = {
        val player = getPlayer(guildId)
        player.playTrack(track)
        logger.info(s"Stream załadowany pomyślnie: ${track.getInfo.title}")
        onSuccess(track)
      }
      
      override def playlistLoaded(playlist: com.sedmelluq.discord.lavaplayer.track.AudioPlaylist): Unit = {
        if (!playlist.getTracks.isEmpty) {
          val track = playlist.getTracks.get(0)
          val player = getPlayer(guildId)
          player.playTrack(track)
          logger.info(s"Playlista załadowana, odtwarzam pierwszy track: ${track.getInfo.title}")
          onSuccess(track)
        } else {
          logger.warn("Playlista pusta")
          onError("Pusta playlista")
        }
      }
    })
  }
  
  /**
   * Zatrzymuje odtwarzanie dla danego serwera
   * @param guildId ID serwera
   */
  def stopPlayer(guildId: Long): Unit = {
    players.get(guildId).foreach { player =>
      player.stopTrack()
      logger.info(s"Zatrzymano player dla guild $guildId")
    }
  }
  
  /**
   * Ustawia głośność dla danego serwera
   * @param guildId ID serwera
   * @param volume Głośność 0-100
   */
  def setVolume(guildId: Long, volume: Int): Unit = {
    val clampedVolume = Math.max(0, Math.min(100, volume))
    players.get(guildId).foreach { player =>
      player.setVolume(clampedVolume)
      logger.info(s"Ustawiono głośność na guild $guildId: $clampedVolume%")
    }
  }
  
  /**
   * Pobiera obecną głośność dla serwera
   * @param guildId ID serwera
   * @return Głośność 0-100 lub None jeśli player nie istnieje
   */
  def getVolume(guildId: Long): Option[Int] = {
    players.get(guildId).map(_.getVolume)
  }
  
  /**
   * Zwraca handler do wysyłania audio do Discord
   * @param guildId ID serwera
   * @return AudioSendHandler
   */
  def getAudioSendHandler(guildId: Long): AudioSendHandler = {
    new LavaPlayerAudioSendHandler(getPlayer(guildId))
  }
  
  /**
   * Sprawdza czy player dla danego serwera gra muzykę
   * @param guildId ID serwera
   * @return true jeśli coś jest odtwarzane
   */
  def isPlaying(guildId: Long): Boolean = {
    players.get(guildId).exists(_.getPlayingTrack != null)
  }
}

/**
 * Handler do wysyłania audio do Discord przez JDA
 */
class LavaPlayerAudioSendHandler(player: AudioPlayer) extends AudioSendHandler {
  private var lastFrame: com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame = _
  
  override def canProvide(): Boolean = {
    lastFrame = player.provide()
    lastFrame != null
  }
  
  override def provide20MsAudio(): ByteBuffer = {
    ByteBuffer.wrap(lastFrame.getData)
  }
  
  override def isOpus: Boolean = true
}