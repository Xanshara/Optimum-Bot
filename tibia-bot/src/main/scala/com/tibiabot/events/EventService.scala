package com.tibiabot.events

import com.typesafe.scalalogging.StrictLogging
import scala.util.{Try, Success, Failure}
import java.sql.Timestamp

/**
 * Serwis zarządzający logiką eventów
 */
class EventService(repository: EventRepository) extends StrictLogging {
  
  /**
   * Tworzy nowy event
   */
  def createEvent(event: Event): Try[Event] = {
    repository.create(event)
  }
  
  /**
   * Pobiera event
   */
  def getEvent(eventId: Int): Option[Event] = {
    repository.findById(eventId)
  }
  
  /**
   * Pobiera event po message_id - TODO: Dodać do repository jeśli potrzebne
   */
  def getEventByMessageId(messageId: Long): Option[Event] = {
    None // Placeholder - trzeba dodać do repository
  }
  
  /**
   * Aktualizuje event
   */
  def updateEvent(event: Event): Try[Unit] = {
    repository.update(event)
  }
  
  /**
   * Zamyka event (ustawia active = false)
   */
  def closeEvent(eventId: Int): Try[Unit] = {
    repository.findById(eventId) match {
      case Some(event) =>
        repository.update(event.copy(active = false))
      case None =>
        Failure(new Exception(s"Event $eventId not found"))
    }
  }
  
  /**
   * Otwiera event (ustawia active = true)
   */
  def openEvent(eventId: Int): Try[Unit] = {
    repository.findById(eventId) match {
      case Some(event) =>
        repository.update(event.copy(active = true))
      case None =>
        Failure(new Exception(s"Event $eventId not found"))
    }
  }
  
  /**
   * Usuwa event
   */
  def deleteEvent(eventId: Int): Try[Unit] = {
    repository.delete(eventId)
  }
  
  /**
   * Usuwa WSZYSTKIE eventy - TYLKO dla super admina!
   */
  def deleteAllEvents(): Try[Int] = {
    repository.deleteAllEvents()
  }
  
  /**
   * Usuwa eventy z konkretnego serwera - TYLKO dla super admina!
   */
  def deleteEventsByGuild(guildId: Long): Try[Int] = {
    repository.deleteEventsByGuild(guildId)
  }
  
  /**
   * Zapisuje użytkownika na event lub zmienia jego rolę
   * NOWA LOGIKA: 
   * - Jeśli użytkownik klika w swoją obecną rolę → wypisuje się (unsignup)
   * - Jeśli użytkownik klika w inną rolę → zmienia rolę
   * - Jeśli użytkownik nie jest zapisany → zapisuje na nową rolę
   */
  def signupUser(eventId: Int, userId: Long, requestedRole: EventRole): Try[EventRole] = {
    logger.info(s"signupUser called - eventId: $eventId, userId: $userId, requestedRole: ${requestedRole.name}")
    
    // Pobierz event
    repository.findById(eventId) match {
      case None =>
        logger.error(s"Event $eventId not found")
        Failure(new Exception(s"Event $eventId not found"))
        
      case Some(event) if !event.active =>
        logger.warn(s"Event $eventId is closed")
        Failure(new Exception("Event is closed"))
        
      case Some(event) =>
        // Pobierz obecne zapisy
        val signups = repository.getSignups(eventId)
        logger.info(s"Current signups for event $eventId: ${signups.size} total")
        
        // Sprawdź czy użytkownik już jest zapisany
        signups.find(_.userId == userId) match {
          case Some(existingSignup) if existingSignup.role == requestedRole =>
            // Użytkownik klika w swoją obecną rolę → WYPISZ GO
            logger.info(s"User $userId clicked their current role ${requestedRole.name} -> unregistering")
            repository.removeSignup(eventId, userId) match {
              case Success(_) =>
                promoteFromWaitlist(event)
                return Failure(new Exception("UNREGISTER")) // Specjalny kod do obsługi w UI
              case failure => return failure.map(_ => requestedRole)
            }
            
          case Some(existingSignup) =>
            // Użytkownik klika w INNĄ rolę → ZMIEŃ ROLĘ
            logger.info(s"User $userId changing role from ${existingSignup.role.name} to ${requestedRole.name}")
            // Usuń stary signup
            repository.removeSignup(eventId, userId)
            // Dodaj nowy signup - dalej w normalnym flow
            
          case None =>
            // Użytkownik nie jest zapisany - normalny flow
            logger.info(s"User $userId is not signed up yet")
        }
        
        // Policz zapisy per rola (po usunięciu starego signupu jeśli był)
        val currentSignups = repository.getSignups(eventId)
        val tankCount = currentSignups.count(_.role == EventRole.Tank)
        val healerCount = currentSignups.count(_.role == EventRole.Healer)
        val dpsCount = currentSignups.count(_.role == EventRole.DPS)
        
        logger.info(s"Current counts - Tank: $tankCount/${event.tankLimit}, Healer: $healerCount/${event.healerLimit}, DPS: $dpsCount/${event.dpsLimit}")
        
        // Określ do jakiej roli zapisać użytkownika
        val assignedRole = requestedRole match {
          case EventRole.Waitlist => 
            logger.info("Requested waitlist -> assigning to Waitlist")
            EventRole.Waitlist // Bezpośredni zapis na waitlist
          case EventRole.Tank if tankCount < event.tankLimit => 
            logger.info(s"Tank has space ($tankCount < ${event.tankLimit}) -> assigning to Tank")
            EventRole.Tank
          case EventRole.Healer if healerCount < event.healerLimit => 
            logger.info(s"Healer has space ($healerCount < ${event.healerLimit}) -> assigning to Healer")
            EventRole.Healer
          case EventRole.DPS if dpsCount < event.dpsLimit => 
            logger.info(s"DPS has space ($dpsCount < ${event.dpsLimit}) -> assigning to DPS")
            EventRole.DPS
          case _ => 
            logger.info(s"${requestedRole.name} is full -> assigning to Waitlist")
            EventRole.Waitlist // Limit pełny → waitlist
        }
        
        logger.info(s"Final assignment: User $userId -> ${assignedRole.name}")
        
        // Dodaj signup
        repository.addSignup(eventId, userId, assignedRole).map { _ =>
          // POPRAWKA: Awansuj z waitlisty TYLKO jeśli użytkownik NIE zapisał się celowo na Waitlist
          // Jeśli ktoś klika Waitlist, chce być na Waitlist - nie awansuj go!
          if (requestedRole != EventRole.Waitlist) {
            // Użytkownik zmienił rolę lub został przypisany automatycznie - sprawdź promocję
            promoteFromWaitlist(event)
          }
          logger.info(s"Successfully assigned user $userId to ${assignedRole.name}")
          assignedRole
        }
    }
  }
  
  /**
   * Wypisuje użytkownika z eventu
   * KLUCZOWA LOGIKA: awansuje pierwszą osobę z waitlisty
   */
  def unsignupUser(eventId: Int, userId: Long): Try[Unit] = {
    repository.findById(eventId) match {
      case None =>
        Failure(new Exception(s"Event $eventId not found"))
        
      case Some(event) =>
        // Usuń użytkownika
        repository.removeSignup(eventId, userId) match {
          case Success(_) =>
            // Awansuj pierwszą osobę z waitlisty
            promoteFromWaitlist(event)
            Success(())
            
          case failure => failure
        }
    }
  }
  
  /**
   * Awansuje pierwszą osobę z waitlisty do odpowiedniej roli
   */
  private def promoteFromWaitlist(event: Event): Unit = {
    val signups = repository.getSignups(event.id)
    
    // Policz zapisy per rola
    val tankCount = signups.count(_.role == EventRole.Tank)
    val healerCount = signups.count(_.role == EventRole.Healer)
    val dpsCount = signups.count(_.role == EventRole.DPS)
    
    // Znajdź pierwszą osobę z waitlisty
    val waitlistSignups = signups.filter(_.role == EventRole.Waitlist).sortBy(_.joinedAt.getTime)
    
    waitlistSignups.headOption.foreach { signup =>
      // Sprawdź która rola ma wolne miejsce
      val newRole = if (tankCount < event.tankLimit) {
        Some(EventRole.Tank)
      } else if (healerCount < event.healerLimit) {
        Some(EventRole.Healer)
      } else if (dpsCount < event.dpsLimit) {
        Some(EventRole.DPS)
      } else {
        None
      }
      
      // Awansuj jeśli jest wolne miejsce
      newRole.foreach { role =>
        repository.addSignup(event.id, signup.userId, role) // UPDATE przez ON CONFLICT
        logger.info(s"Promoted user ${signup.userId} from waitlist to ${role.name} in event ${event.id}")
      }
    }
  }
  
  /**
   * Pobiera zapisy dla eventu pogrupowane po rolach
   */
  def getSignupsByRole(eventId: Int): Map[EventRole, List[EventSignup]] = {
    val signups = repository.getSignups(eventId)
    signups.groupBy(_.role)
  }
  
  /**
   * Pobiera wszystkie aktywne eventy dla guild
   */
  def getActiveEvents(guildId: Long): List[Event] = {
    repository.findActiveByGuild(guildId)
  }
  
  /**
   * Pobiera eventy wymagające przypomnienia
   */
  def getEventsNeedingReminder(): List[Event] = {
    repository.findEventsNeedingReminder()
  }
  
  /**
   * Oznacza przypomnienie jako wysłane
   */
  def markReminderAsSent(eventId: Int): Try[Unit] = {
    repository.markReminderSent(eventId)
  }
}
