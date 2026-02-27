package com.tibiabot.events

import akka.actor.{ActorSystem, Cancellable}
import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.JDA
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.util.{Success, Failure}
import scala.jdk.CollectionConverters._
import java.util.Calendar

class EventScheduler(
  eventReminder: EventReminder,
  eventService: EventService,
  embedBuilder: EventEmbedBuilder,
  jda: JDA
)(implicit system: ActorSystem, ec: ExecutionContext) extends StrictLogging {

  private var scheduledTask: Option[Cancellable] = None

  def start(): Unit = {
    logger.info("Starting event scheduler (reminders + recurring)...")

    val task = system.scheduler.scheduleWithFixedDelay(
      initialDelay = 10.seconds,
      delay        = 1.minute
    ) { () =>
      try {
        // Przypomnienia (co minutę)
        eventReminder.checkAndSendReminders()

        // Eventy cykliczne — tylko o 22:30
        val cal = Calendar.getInstance()
        val hour   = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        if (hour == 22 && minute == 30) {
          logger.info("22:30 — auto-closing past events and checking recurring...")
          autoClosePastEvents()
          processRecurringEvents()
        }
      } catch {
        case e: Exception =>
          logger.error("Error in scheduled task", e)
      }
    }

    scheduledTask = Some(task)
    logger.info("Event scheduler started")
  }

  def stop(): Unit = {
    scheduledTask.foreach(_.cancel())
    scheduledTask = None
    logger.info("Event scheduler stopped")
  }

  // ========== AUTO-CLOSE ==========

  private def autoClosePastEvents(): Unit = {
    try {
      val now = System.currentTimeMillis()
      val activeEvents = eventService.getActiveEvents()  // wszystkie aktywne ze wszystkich guildów

      val pastEvents = activeEvents.filter(_.eventTime.getTime < now)

      if (pastEvents.isEmpty) {
        logger.debug("No past events to auto-close")
        return
      }

      logger.info(s"Auto-closing ${pastEvents.size} past event(s)...")

      pastEvents.foreach { event =>
        eventService.closeEvent(event.id) match {
          case scala.util.Success(_) =>
            logger.info(s"Auto-closed event ${event.id}: ${event.title}")

            // Zaktualizuj embed na kanale żeby pokazywało [CLOSED]
            val channel = Option(jda.getTextChannelById(event.channelId))
              .orElse(Option(jda.getThreadChannelById(event.channelId)))
              .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
              .orNull
            if (channel != null) {
              channel.retrieveMessageById(event.messageId).queue(
                msg => {
                  val closedEvent = event.copy(active = false)
                  val signups = eventService.getSignupsByRole(event.id)
                  val embed = embedBuilder.buildEventEmbed(closedEvent, signups)
                  val buttons = EventButtons.createEventButtons(event.id, active = false)
                  msg.editMessageEmbeds(embed).setComponents(buttons.asJava).queue()
                },
                err => logger.warn(s"Could not update message for closed event ${event.id}: ${err.getMessage}")
              )
            }

          case scala.util.Failure(e) =>
            logger.error(s"Failed to auto-close event ${event.id}: ${e.getMessage}")
        }
      }
    } catch {
      case e: Exception =>
        logger.error("Error in autoClosePastEvents", e)
    }
  }

  // ========== RECURRING LOGIC ==========

  private def processRecurringEvents(): Unit = {
    val events = eventService.getRecurringEventsToProcess()

    if (events.isEmpty) {
      logger.debug("No recurring events to process")
      return
    }

    logger.info(s"Processing ${events.size} recurring event(s)")

    events.foreach { original =>
      try {
        eventService.createRecurringCopy(original) match {
          case Failure(e) =>
            logger.error(s"Failed to prepare recurring copy for event ${original.id}: ${e.getMessage}")

          case Success(newEventTemplate) =>
            val channel = Option(jda.getTextChannelById(original.channelId))
              .orElse(Option(jda.getThreadChannelById(original.channelId)))
              .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
              .orNull
            if (channel == null) {
              logger.warn(s"Channel ${original.channelId} not found for recurring event ${original.id}")
            } else {
              // Wyślij mention
              val mentionText = original.mentionRoleId match {
                case Some(-1L)   => "@everyone"
                case Some(-2L)   => "@here"
                case Some(roleId) => s"<@&$roleId>"
                case None        => "\u200B"
              }

              channel.sendMessage(mentionText).queue { msg =>
                val newEvent = newEventTemplate.copy(messageId = msg.getIdLong)

                eventService.createEvent(newEvent) match {
                  case Success(createdEvent) =>
                    val signups = eventService.getSignupsByRole(createdEvent.id)
                    val embed   = embedBuilder.buildEventEmbed(createdEvent, signups)
                    val buttons = EventButtons.createEventButtons(createdEvent.id, createdEvent.active)

                    msg.editMessage(mentionText)
                      .setEmbeds(embed)
                      .setComponents(buttons.asJava)
                      .queue(
                        _ => logger.info(s"Recurring event ${createdEvent.id} created (from ${original.id})"),
                        err => logger.error(s"Failed to edit recurring event message: ${err.getMessage}")
                      )

                  case Failure(e) =>
                    logger.error(s"Failed to save recurring event (from ${original.id}): ${e.getMessage}")
                    msg.delete().queue()
                }
              }
            }
        }
      } catch {
        case e: Exception =>
          logger.error(s"Unexpected error processing recurring event ${original.id}", e)
      }
    }
  }
}
