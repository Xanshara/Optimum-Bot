package com.tibiabot.events

import net.dv8tion.jda.api.JDA
import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.ExecutionContext

object EventIntegration extends StrictLogging {

  private var eventCommandOpt: Option[EventCommand]             = None
  private var eventButtonListenerOpt: Option[EventButtonListener] = None
  private var eventModalListenerOpt: Option[EventModalListener]  = None
  private var eventSchedulerOpt: Option[EventScheduler]          = None

  def initialize(
    jda: JDA,
    dbUrl: String,
    dbUser: String,
    dbPassword: String,
    actorSystem: ActorSystem,
    ec: ExecutionContext,
    eventAdminGuildId: Option[Long] = None
  ): Unit = {
    logger.info("Initializing event system...")

    try {
      val repository = new EventRepository(dbUrl, dbUser, dbPassword)
      repository.createTablesIfNotExist()

      val service      = new EventService(repository)
      val embedBuilder = new EventEmbedBuilder(jda)

      val eventCommand = new EventCommand(jda, service, embedBuilder, eventAdminGuildId)
      eventCommandOpt = Some(eventCommand)

      eventCommand.registerCommands()
      logger.info("Event commands registered")

      val eventButtonListener = new EventButtonListener(jda, service, embedBuilder, eventCommand)
      eventButtonListenerOpt = Some(eventButtonListener)
      jda.addEventListener(eventButtonListener)

      val eventModalListener = new EventModalListener(eventCommand)
      eventModalListenerOpt = Some(eventModalListener)
      jda.addEventListener(eventModalListener)

      val eventReminder = new EventReminder(jda, service, embedBuilder)

      // EventScheduler teraz dostaje też service, embedBuilder i jda do obsługi recurring
      val eventScheduler = new EventScheduler(eventReminder, service, embedBuilder, jda)(actorSystem, ec)
      eventSchedulerOpt = Some(eventScheduler)
      eventScheduler.start()

      logger.info("Event system initialized successfully")
    } catch {
      case e: Exception =>
        logger.error("Failed to initialize event system", e)
        throw e
    }
  }

  def getEventCommand: Option[EventCommand] = eventCommandOpt

  def isInitialized: Boolean = eventCommandOpt.isDefined

  def shutdown(): Unit = {
    logger.info("Shutting down event system...")
    eventSchedulerOpt.foreach(_.stop())
    logger.info("Event system shut down")
  }
}
