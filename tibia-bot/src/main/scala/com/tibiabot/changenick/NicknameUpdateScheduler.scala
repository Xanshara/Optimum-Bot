package com.tibiabot.changenick

import akka.actor.{Actor, ActorSystem, Cancellable, Props}
import com.tibiabot.Config
import com.tibiabot.tibiadata.TibiaDataClient
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import scala.util.matching.Regex

/**
 * Scheduler który automatycznie aktualizuje levele w nickach członków
 * 
 * Skanuje nicki w formacie: emoji Name [level]
 * Sprawdza aktualny level w TibiaData API
 * Aktualizuje jeśli się zmienił
 */
class NicknameUpdateScheduler(
    jda: JDA,
    tibiaDataClient: TibiaDataClient
)(implicit ec: ExecutionContext, actorSystem: ActorSystem) extends StrictLogging {

  // Pattern do rozpoznawania nicków: emoji Name [123]
  private val nicknamePattern: Regex = """^\S+\s+(.+?)\s+\[(\d+)\]$""".r
  
  // Scheduler - uruchamia się co 24h
  private var schedulerTask: Option[Cancellable] = None

  /**
   * Uruchom scheduler - sprawdza co 12h
   */
  def start(): Unit = {
    val interval = 12.hours // Sprawdzanie co 12 godzin
    
    schedulerTask = Some(
      actorSystem.scheduler.scheduleAtFixedRate(
        initialDelay = 1.hour, // Pierwsze sprawdzenie za 1h od startu
        interval = interval
      ) { () =>
        logger.info("🔄 Starting automatic nickname level update...")
        updateAllNicknames()
      }
    )
    
    logger.info(s"✅ Nickname update scheduler started (interval: ${interval.toHours}h)")
  }

  /**
   * Zatrzymaj scheduler
   */
  def stop(): Unit = {
    schedulerTask.foreach(_.cancel())
    logger.info("⏹️ Nickname update scheduler stopped")
  }

  /**
   * Aktualizuj wszystkie nicki na wszystkich serwerach
   */
  def updateAllNicknames(): Unit = {
    jda.getGuilds.asScala.foreach { guild =>
      updateGuildNicknames(guild)
    }
  }

  /**
   * Aktualizuj nicki na konkretnym serwerze
   */
  def updateGuildNicknames(guild: Guild): Unit = {
    logger.info(s"🔍 Checking nicknames on guild: ${guild.getName}")
    
    val members = guild.getMembers.asScala.toList
      .filterNot(_.getUser.isBot) // Pomiń boty
    
    var updatedCount = 0
    var errorCount = 0
    var checkedCount = 0
    
    members.foreach { member =>
      val currentNick = member.getEffectiveName
      
      logger.info(s"Checking member: $currentNick")
      
      // Sprawdź czy nick pasuje do wzorca: emoji Name [level]
      nicknamePattern.findFirstMatchIn(currentNick) match {
        case Some(m) =>
          val characterName = m.group(1)
          val currentLevel = m.group(2).toInt
          
          checkedCount += 1
          logger.info(s"Found character: $characterName (current level: $currentLevel)")
          
          // Dodaj małe opóźnienie między requestami (500ms)
          Thread.sleep(500)
          
          // Sprawdź aktualny level w API
          tibiaDataClient.getCharacter(characterName).onComplete {
            case Success(Right(characterResponse)) =>
              val character = characterResponse.character.character
              val apiLevel = try {
                character.level.toString.toDouble.toInt
              } catch {
                case _: Exception => 0
              }
              
              // Sprawdź czy level się zmienił
              if (apiLevel != currentLevel && apiLevel > 0) {
                val levelDiff = apiLevel - currentLevel
                val levelChange = if (levelDiff > 0) s"+$levelDiff" else levelDiff.toString
                
                logger.info(s"📊 Level change detected: ${character.name} $currentLevel → $apiLevel ($levelChange)")
                
                // Aktualizuj nick
                val professionEmoji = getProfessionEmoji(character.vocation)
                val newNickname = s"$professionEmoji ${character.name} [$apiLevel]"
                
                // Sprawdź limit 32 znaków
                val finalNickname = if (newNickname.length > 32) {
                  val withoutEmoji = s"${character.name} [$apiLevel]"
                  if (withoutEmoji.length > 32) {
                    character.name.take(32)
                  } else {
                    withoutEmoji
                  }
                } else {
                  newNickname
                }
                
                // Zmień nick
                if (guild.getSelfMember.canInteract(member)) {
                  member.modifyNickname(finalNickname).queue(
                    _ => {
                      logger.info(s"✅ Updated nickname: ${member.getUser.getName} → $finalNickname")
                    },
                    error => {
                      logger.warn(s"❌ Failed to update nickname for ${member.getUser.getName}: ${error.getMessage}")
                    }
                  )
                } else {
                  logger.debug(s"⚠️ Cannot interact with member: ${member.getUser.getName} (hierarchy)")
                }
              } else if (apiLevel > 0) {
                logger.info(s"✓ Level unchanged: ${character.name} (still $currentLevel)")
              }
              
            case Success(Left(error)) =>
              logger.info(s"Character not found: $characterName - $error")
              
            case Failure(exception) =>
              logger.warn(s"API error for character $characterName: ${exception.getMessage}")
          }
          
        case None =>
          // Nick nie pasuje do wzorca - pomijamy
          logger.info(s"⚠️ Skipping (no regex match): $currentNick")
      }
    }
    
    logger.info(s"✅ Nickname update completed for ${guild.getName}: checked $checkedCount members")
  }

  /**
   * Pomocnicza metoda - zwraca emoji dla profesji
   */
  private def getProfessionEmoji(vocation: String): String = {
    vocation.toLowerCase match {
      case v if v.contains("knight") => "🛡️"
      case v if v.contains("paladin") => "🏹"
      case v if v.contains("sorcerer") => "🔥"
      case v if v.contains("druid") => "🌿"
      case v if v.contains("monk") => "👊"
      case _ => "⚔️"
    }
  }
}