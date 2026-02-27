package com.tibiabot.events

import com.typesafe.scalalogging.StrictLogging
import scala.util.{Try, Success, Failure}
import java.sql.Timestamp
import java.util.Calendar

class EventService(repository: EventRepository) extends StrictLogging {

  def createEvent(event: Event): Try[Event] = repository.create(event)

  def getEvent(eventId: Int): Option[Event] = repository.findById(eventId)

  def getEventByMessageId(messageId: Long): Option[Event] = None

  def updateEvent(event: Event): Try[Unit] = repository.update(event)

  def closeEvent(eventId: Int): Try[Unit] =
    repository.findById(eventId) match {
      case Some(e) => repository.update(e.copy(active = false))
      case None    => Failure(new Exception(s"Event $eventId not found"))
    }

  def openEvent(eventId: Int): Try[Unit] =
    repository.findById(eventId) match {
      case Some(e) => repository.update(e.copy(active = true))
      case None    => Failure(new Exception(s"Event $eventId not found"))
    }

  def deleteEvent(eventId: Int): Try[Unit] = repository.delete(eventId)

  def deleteAllEvents(): Try[Int] = repository.deleteAllEvents()

  def deleteEventsByGuild(guildId: Long): Try[Int] = repository.deleteEventsByGuild(guildId)

  def getActiveEvents(guildId: Long): List[Event] = repository.findActiveByGuild(guildId)

  // Wszystkie aktywne eventy ze wszystkich guildów (dla schedulera)
  def getActiveEvents(): List[Event] = repository.findAllActive()

  def getEventsNeedingReminder(): List[Event] = repository.findEventsNeedingReminder()

  def getEventsNeedingFixedReminder(): List[Event] = repository.findEventsNeedingFixedReminder()

  def markReminderAsSent(eventId: Int): Try[Unit] = repository.markReminderSent(eventId)

  def markFixedReminderAsSent(eventId: Int): Try[Unit] = repository.markFixedReminderSent(eventId)

  // ========== SIGNUP LOGIC ==========

  def signupUser(eventId: Int, userId: Long, requestedRole: EventRole): Try[EventRole] = {
    repository.findById(eventId) match {
      case None =>
        Failure(new Exception(s"Event $eventId not found"))

      case Some(event) if !event.active =>
        Failure(new Exception("This event is closed"))

      case Some(event) =>
        val signups = repository.getSignups(eventId)
        val userSignup = signups.find(_.userId == userId)

        userSignup match {
          // Kliknął swoją rolę → wypisz
          case Some(existing) if existing.role == requestedRole =>
            repository.removeSignup(eventId, userId)
            Failure(new Exception("UNREGISTER"))

          // Już zapisany na inną rolę → zmień
          case Some(_) =>
            repository.addSignup(eventId, userId, requestedRole)
            Success(requestedRole)

          // Nie zapisany → zapisz (lub waitlist jeśli pełne)
          case None =>
            val roleSignups = signups.filter(_.role == requestedRole)
            val limit = requestedRole match {
              case EventRole.Tank    => event.tankLimit
              case EventRole.Healer  => event.healerLimit
              case EventRole.DPS     => event.dpsLimit
              case EventRole.Waitlist => Int.MaxValue
            }

            if (roleSignups.size < limit) {
              repository.addSignup(eventId, userId, requestedRole)
              Success(requestedRole)
            } else {
              repository.addSignup(eventId, userId, EventRole.Waitlist)
              Success(EventRole.Waitlist)
            }
        }
    }
  }

  def unsignupUser(eventId: Int, userId: Long): Try[Unit] = {
    repository.removeSignup(eventId, userId) match {
      case Success(_) =>
        promoteFromWaitlist(eventId)
        Success(())
      case failure => failure
    }
  }

  private def promoteFromWaitlist(eventId: Int): Unit = {
    repository.findById(eventId).foreach { event =>
      val signups = repository.getSignups(eventId)

      List(EventRole.Tank, EventRole.Healer, EventRole.DPS).foreach { role =>
        val limit = role match {
          case EventRole.Tank   => event.tankLimit
          case EventRole.Healer => event.healerLimit
          case EventRole.DPS    => event.dpsLimit
          case _                => 0
        }
        val current = signups.count(_.role == role)
        if (current < limit) {
          signups.find(_.role == EventRole.Waitlist).foreach { waiting =>
            repository.addSignup(eventId, waiting.userId, role)
          }
        }
      }
    }
  }

  def getSignupsByRole(eventId: Int): Map[EventRole, List[EventSignup]] = {
    val signups = repository.getSignups(eventId)
    Map(
      EventRole.Tank     -> signups.filter(_.role == EventRole.Tank),
      EventRole.Healer   -> signups.filter(_.role == EventRole.Healer),
      EventRole.DPS      -> signups.filter(_.role == EventRole.DPS),
      EventRole.Waitlist -> signups.filter(_.role == EventRole.Waitlist)
    )
  }

  // ========== RECURRING ==========

  /**
   * Znajdź cykliczne eventy gotowe do odnowienia
   */
  def getRecurringEventsToProcess(): List[Event] =
    repository.findRecurringEventsToProcess()

  /**
   * Tworzy następną kopię cyklicznego eventu.
   * Zwraca nowo utworzony Event.
   */
  def createRecurringCopy(original: Event): Try[Event] = {
    original.recurringIntervalDays match {
      case None =>
        Failure(new Exception(s"Event ${original.id} has no recurring interval"))

      case Some(intervalDays) =>
        val cal = Calendar.getInstance()
        cal.setTimeInMillis(original.eventTime.getTime)
        cal.add(Calendar.DAY_OF_MONTH, intervalDays)
        val newEventTime = new Timestamp(cal.getTimeInMillis)

        val newEvent = original.copy(
          id               = 0,
          messageId        = 0L,      // placeholder — zostanie ustawiony po wysłaniu wiadomości
          eventTime        = newEventTime,
          active           = true,
          reminderSent     = false,
          nextEventCreated = false,
          createdAt        = new Timestamp(System.currentTimeMillis())
        )

        // Oznacz stary event jako "następny już stworzony"
        repository.markNextEventCreated(original.id).foreach { _ =>
          logger.info(s"Marked event ${original.id} as next_event_created")
        }

        Success(newEvent)
    }
  }
}
