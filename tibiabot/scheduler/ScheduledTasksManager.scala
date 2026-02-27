package com.tibiabot.scheduler

import akka.actor.ActorSystem
import com.tibiabot.rashid.RashidData
import com.tibiabot.news.TibiaNewsManager
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.{EmbedBuilder, JDA}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import java.awt.Color
import java.time.{LocalDateTime, LocalTime, ZoneId, ZonedDateTime}
import java.util.concurrent.TimeUnit

/**
 * Manager dla zaplanowanych zadań (daily Rashid, news check)
 * Obsługuje wysyłanie do wielu kanałów jednocześnie
 */
class ScheduledTasksManager(
  jda: JDA,
  rashidChannelIds: List[String],
  newsChannelIds: List[String]
)(implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {
  
  private val newsManager = new TibiaNewsManager()
  
  def start(): Unit = {
    scheduleDailyRashidMessage()
    scheduleNewsCheck()
    logger.info("Scheduled tasks started")
  }
  
  private def scheduleDailyRashidMessage(): Unit = {
    if (rashidChannelIds.isEmpty) {
      logger.info("Rashid channels not configured, skipping daily Rashid messages")
      return
    }
    
    logger.info(s"Rashid will be sent to ${rashidChannelIds.size} channel(s): ${rashidChannelIds.mkString(", ")}")
    
    val targetTime = LocalTime.of(10, 15) // 11:00
    val initialDelay = calculateInitialDelay(targetTime)
    
    system.scheduler.scheduleAtFixedRate(
      initialDelay = initialDelay,
      interval = 24.hours
    ) { () =>
      sendDailyRashidMessage()
    }
    
    logger.info(s"Daily Rashid message scheduled at 10:15 (initial delay: ${initialDelay.toMinutes} minutes)")
  }
  
  private def scheduleNewsCheck(): Unit = {
    if (newsChannelIds.isEmpty) {
      logger.info("News channels not configured, skipping news checks")
      return
    }
    
    logger.info(s"News will be sent to ${newsChannelIds.size} channel(s): ${newsChannelIds.mkString(", ")}")
    
    system.scheduler.scheduleAtFixedRate(
      initialDelay = 1.minute,
      interval = 1.hour
    ) { () =>
      newsChannelIds.foreach { channelId =>
        newsManager.checkAndSendNews(jda, channelId)
      }
    }
    
    logger.info("News check scheduled every hour")
  }
  
  private def sendDailyRashidMessage(): Unit = {
    try {
      RashidData.getTodayLocation() match {
        case Some(location) =>
          val embed = createRashidEmbed(location)
          var successCount = 0
          var failCount = 0
          
          rashidChannelIds.foreach { channelId =>
            try {
              val channel = jda.getTextChannelById(channelId)
              
              if (channel != null && channel.canTalk()) {
                channel.sendMessageEmbeds(embed).queue()
                successCount += 1
                logger.info(s"Daily Rashid message sent to channel $channelId")
              } else {
                failCount += 1
                logger.warn(s"Cannot send daily Rashid message to channel $channelId - channel not found or no permissions")
              }
            } catch {
              case e: Exception =>
                failCount += 1
                logger.error(s"Error sending daily Rashid message to channel $channelId", e)
            }
          }
          
          logger.info(s"Daily Rashid sent: $successCount success, $failCount failed out of ${rashidChannelIds.size} channels")
          
        case None =>
          logger.error("Unable to determine Rashid's location for daily message")
      }
    } catch {
      case e: Exception =>
        logger.error("Error sending daily Rashid message", e)
    }
  }
  
  private def createRashidEmbed(location: com.tibiabot.rashid.RashidLocation): net.dv8tion.jda.api.entities.MessageEmbed = {
    new EmbedBuilder()
      .setTitle("Informations:")
      .setColor(new Color(0, 255, 0))
      .addField(
        "",
        s"[Rashid](https://tibiopedia.pl/npcs/Rashid)\n\n${location.description}",
        true
      )
      .setThumbnail(location.imageUrl)
      .setImage(location.mapImageUrl)
      .build()
  }
  
  private def calculateInitialDelay(targetTime: LocalTime): FiniteDuration = {
    val now = ZonedDateTime.now(ZoneId.systemDefault())
    val nowTime = now.toLocalTime
    
    val targetToday = now.toLocalDate.atTime(targetTime).atZone(ZoneId.systemDefault())
    
    val targetDateTime = if (nowTime.isAfter(targetTime)) {
      targetToday.plusDays(1)
    } else {
      targetToday
    }
    
    val delayMillis = targetDateTime.toInstant.toEpochMilli - now.toInstant.toEpochMilli
    FiniteDuration(delayMillis, TimeUnit.MILLISECONDS)
  }
}
