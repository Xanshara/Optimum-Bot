package com.tibiabot.events

import net.dv8tion.jda.api.JDA
import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import scala.concurrent.ExecutionContext

/**
 * Klasa integrująca system eventów z głównym botem
 */
object EventIntegration extends StrictLogging {
  
  private var eventCommandOpt: Option[EventCommand] = None
  private var eventButtonListenerOpt: Option[EventButtonListener] = None
  private var eventModalListenerOpt: Option[EventModalListener] = None
  private var eventSchedulerOpt: Option[EventScheduler] = None
  
  /**
   * Inicjalizuje system eventów
   * @param jda Instancja JDA
   * @param dbUrl URL do bazy danych PostgreSQL
   * @param dbUser Użytkownik bazy danych
   * @param dbPassword Hasło do bazy danych
   * @param actorSystem ActorSystem dla schedulera
   * @param ec ExecutionContext dla operacji async
   * @param eventAdminGuildId Guild ID dla /event clean i /event servers (opcjonalne)
   */
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
      // Inicjalizacja repository i utworzenie tabel
      val repository = new EventRepository(dbUrl, dbUser, dbPassword)
      repository.createTablesIfNotExist()
      
      // Inicjalizacja service
      val service = new EventService(repository)
      
      // Inicjalizacja embed builder
      val embedBuilder = new EventEmbedBuilder(jda)
      
      // Inicjalizacja command handler
      val eventCommand = new EventCommand(jda, service, embedBuilder, eventAdminGuildId)
      eventCommandOpt = Some(eventCommand)
      
	  // Zarejestruj komendy w Discord
eventCommand.registerCommands()
logger.info("Event commands registered")
	  
      // Inicjalizacja button listener
      val eventButtonListener = new EventButtonListener(jda, service, embedBuilder, eventCommand)
      eventButtonListenerOpt = Some(eventButtonListener)
      jda.addEventListener(eventButtonListener)
      
      // Inicjalizacja modal listener
      val eventModalListener = new EventModalListener(eventCommand)
      eventModalListenerOpt = Some(eventModalListener)
      jda.addEventListener(eventModalListener)
      
      // Inicjalizacja reminder system
      val eventReminder = new EventReminder(jda, service, embedBuilder)
      val eventScheduler = new EventScheduler(eventReminder)(actorSystem, ec)
      eventSchedulerOpt = Some(eventScheduler)
      
      // Uruchom scheduler
      eventScheduler.start()
      
      logger.info("Event system initialized successfully with reminder scheduler")
    } catch {
      case e: Exception =>
        logger.error("Failed to initialize event system", e)
        throw e
    }
  }
  
  /**
   * Pobiera EventCommand (jeśli zainicjalizowany)
   */
  def getEventCommand: Option[EventCommand] = eventCommandOpt
  
  /**
   * Sprawdza czy system jest zainicjalizowany
   */
  def isInitialized: Boolean = eventCommandOpt.isDefined
  
  /**
   * Zatrzymuje system eventów
   */
  def shutdown(): Unit = {
    logger.info("Shutting down event system...")
    eventSchedulerOpt.foreach(_.stop())
    logger.info("Event system shut down")
  }
}