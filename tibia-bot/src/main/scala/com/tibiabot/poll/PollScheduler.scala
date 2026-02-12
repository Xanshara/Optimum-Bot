package com.tibiabot.poll

import akka.actor.{ActorSystem, Cancellable}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
 * Scheduler sprawdzający czy ankiety się zakończyły i ogłaszający wyniki
 */
class PollScheduler(
  pollManager: PollManager,
  pollListener: PollListener,
  jda: JDA
)(implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {

  private var scheduledTask: Option[Cancellable] = None

  /**
   * Startuje scheduler
   */
  def start(): Unit = {
    logger.info("📅 Starting poll scheduler...")
    
    // Sprawdzaj co minutę czy jakieś ankiety się zakończyły
    val task = system.scheduler.scheduleAtFixedRate(
      initialDelay = 10.seconds,
      interval = 1.minute
    ) { () =>
      checkExpiredPolls()
    }
    
    scheduledTask = Some(task)
    logger.info("✅ Poll scheduler started - checking every minute")
  }

  /**
   * Zatrzymuje scheduler
   */
  def stop(): Unit = {
    scheduledTask.foreach(_.cancel())
    scheduledTask = None
    logger.info("⏹️ Poll scheduler stopped")
  }

  /**
   * Sprawdza czy jakieś ankiety się zakończyły
   */
  private def checkExpiredPolls(): Unit = {
    try {
      val expiredPolls = pollManager.getExpiredPolls()
      
      if (expiredPolls.nonEmpty) {
        logger.info(s"Found ${expiredPolls.size} expired poll(s), finalizing...")
        
        expiredPolls.foreach { poll =>
          try {
            pollListener.finalizePoll(poll, jda)
          } catch {
            case e: Exception =>
              logger.error(s"Error finalizing poll ${poll.pollId}", e)
          }
        }
      }
    } catch {
      case e: Exception =>
        logger.error("Error checking expired polls", e)
    }
  }
}
