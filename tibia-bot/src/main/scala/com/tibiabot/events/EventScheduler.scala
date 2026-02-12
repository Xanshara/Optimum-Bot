package com.tibiabot.events

import akka.actor.{ActorSystem, Cancellable}
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
 * Scheduler dla systemu przypomnień
 */
class EventScheduler(
  eventReminder: EventReminder
)(implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {
  
  private var scheduledTask: Option[Cancellable] = None
  
  /**
   * Uruchamia scheduler - sprawdza przypomnienia co 1 minutę
   */
  def start(): Unit = {
    logger.info("Starting event reminder scheduler...")
    
    val task = system.scheduler.scheduleWithFixedDelay(
      initialDelay = 10.seconds,  // Pierwsze sprawdzenie po 10 sekundach
      delay = 1.minute             // Kolejne co minutę
    ) { () =>
      try {
        eventReminder.checkAndSendReminders()
      } catch {
        case e: Exception =>
          logger.error("Error in scheduled reminder check", e)
      }
    }
    
    scheduledTask = Some(task)
    logger.info("Event reminder scheduler started")
  }
  
  /**
   * Zatrzymuje scheduler
   */
  def stop(): Unit = {
    scheduledTask.foreach(_.cancel())
    scheduledTask = None
    logger.info("Event reminder scheduler stopped")
  }
}
