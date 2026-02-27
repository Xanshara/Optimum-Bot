package com.tibiabot.events

import net.dv8tion.jda.api.JDA
import com.typesafe.scalalogging.StrictLogging
import scala.jdk.CollectionConverters._

/**
 * System przypomnień o eventach
 */
class EventReminder(
  jda: JDA,
  eventService: EventService,
  embedBuilder: EventEmbedBuilder
) extends StrictLogging {
  
  /**
   * Sprawdza eventy wymagające przypomnienia i wysyła je
   */
  def checkAndSendReminders(): Unit = {
    try {
      // Konfigurowalny reminder
      logger.debug("Checking for events needing reminders...")
      val eventsNeedingReminder = eventService.getEventsNeedingReminder()
      if (eventsNeedingReminder.nonEmpty) {
        logger.info(s"Found ${eventsNeedingReminder.size} event(s) needing configurable reminder")
        eventsNeedingReminder.foreach { event =>
          logger.info(s"Processing reminder for event ${event.id}: ${event.title}")
          sendReminder(event)
        }
      }

      // Stały reminder 10h przed eventem
      val eventsNeedingFixedReminder = eventService.getEventsNeedingFixedReminder()
      if (eventsNeedingFixedReminder.nonEmpty) {
        logger.info(s"Found ${eventsNeedingFixedReminder.size} event(s) needing fixed 10h reminder")
        eventsNeedingFixedReminder.foreach { event =>
          logger.info(s"Processing fixed 10h reminder for event ${event.id}: ${event.title}")
          sendFixedReminder(event)
        }
      }
    } catch {
      case e: Exception =>
        logger.error("Error checking reminders", e)
    }
  }
  
  /**
   * Wysyła przypomnienie dla eventu (konfigurowalny)
   */
  private def sendReminder(event: Event): Unit = {
    try {
      val channel = Option(jda.getTextChannelById(event.channelId))
        .orElse(Option(jda.getThreadChannelById(event.channelId)))
        .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
        .orNull
      if (channel == null) {
        logger.warn(s"Channel ${event.channelId} not found for event ${event.id}")
        eventService.markReminderAsSent(event.id) // Oznacz jako wysłane aby nie próbować ponownie
        return
      }
      
      // Calculate time until event
      val now = System.currentTimeMillis()
      val eventTimeMillis = event.eventTime.getTime
      val minutesUntil = ((eventTimeMillis - now) / 1000 / 60).toInt
      
      logger.info(s"Event ${event.id} is in $minutesUntil minutes (reminder set for ${event.reminderMinutes} minutes before)")
      
      if (minutesUntil < 0) {
        // Event already passed
        logger.warn(s"Event ${event.id} already passed (was $minutesUntil minutes ago), marking reminder as sent")
        eventService.markReminderAsSent(event.id)
        return
      }
      
      // Build reminder message
      val mentionText = event.mentionRoleId match {
        case Some(-1L) => "@everyone"
        case Some(-2L) => "@here"
        case Some(roleId) => s"<@&$roleId>"
        case None => ""
      }
      
      val timeText = if (minutesUntil == 0) {
        "now"
      } else if (minutesUntil == 1) {
        "1 minute"
      } else {
        s"$minutesUntil minutes"
      }
      
      val embed = new net.dv8tion.jda.api.EmbedBuilder()
        .setTitle(s"🔔 Event Reminder: ${event.title}")
        .setDescription(s"**${event.title}** starts in **$timeText**!")
        .setColor(java.awt.Color.ORANGE)
        .addField("Event Time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(event.eventTime), true)
        .build()
      
      // Try to get original message for link
      channel.retrieveMessageById(event.messageId).queue(
        msg => {
          val messageLink = msg.getJumpUrl
          val messageContent = if (mentionText.nonEmpty) mentionText else " " // Discord requires non-empty content
          
          channel.sendMessage(messageContent)
            .setEmbeds(embed)
            .addActionRow(
              net.dv8tion.jda.api.interactions.components.buttons.Button.link(messageLink, "View Event")
            )
            .queue(
              _ => {
                logger.info(s"✅ Successfully sent reminder for event ${event.id}")
                eventService.markReminderAsSent(event.id)
              },
              error => {
                logger.error(s"Failed to send reminder for event ${event.id}", error)
                // Nie oznaczaj jako wysłane aby spróbować ponownie
              }
            )
        },
        error => {
          // Original message not found, send without link
          logger.warn(s"Original message not found for event ${event.id}, sending reminder without link")
          val messageContent = if (mentionText.nonEmpty) mentionText else " "
          
          channel.sendMessage(messageContent)
            .setEmbeds(embed)
            .queue(
              _ => {
                logger.info(s"✅ Sent reminder for event ${event.id} (without link)")
                eventService.markReminderAsSent(event.id)
              },
              error2 => {
                logger.error(s"Failed to send reminder for event ${event.id}", error2)
                // Nie oznaczaj jako wysłane aby spróbować ponownie
              }
            )
        }
      )
    } catch {
      case e: Exception =>
        logger.error(s"Error sending reminder for event ${event.id}", e)
    }
  }

  /**
   * Stały reminder 10h przed eventem — nie można wyłączyć
   */
  private def sendFixedReminder(event: Event): Unit = {
    try {
      val channel = Option(jda.getTextChannelById(event.channelId))
        .orElse(Option(jda.getThreadChannelById(event.channelId)))
        .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
        .orNull
      if (channel == null) {
        logger.warn(s"Channel ${event.channelId} not found for fixed reminder event ${event.id}")
        eventService.markFixedReminderAsSent(event.id)
        return
      }

      val now          = System.currentTimeMillis()
      val minutesUntil = ((event.eventTime.getTime - now) / 1000 / 60).toInt

      if (minutesUntil < 0) {
        eventService.markFixedReminderAsSent(event.id)
        return
      }

      val hoursUntil = minutesUntil / 60
      val timeText = if (minutesUntil < 60) s"$minutesUntil minut"
                     else if (hoursUntil == 1) "1 godzinę"
                     else s"$hoursUntil godzin"

      val mentionText = event.mentionRoleId match {
        case Some(-1L)    => "@everyone"
        case Some(-2L)    => "@here"
        case Some(roleId) => s"<@&$roleId>"
        case None         => ""
      }

      val embed = new net.dv8tion.jda.api.EmbedBuilder()
        .setTitle(s"⏰ Przypomnienie: ${event.title}")
        .setDescription(s"**${event.title}** zaczyna się za **$timeText**!")
        .setColor(java.awt.Color.YELLOW)
        .addField("Event Time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(event.eventTime), true)
        .build()

      val messageContent = if (mentionText.nonEmpty) mentionText else " "

      channel.retrieveMessageById(event.messageId).queue(
        msg => {
          channel.sendMessage(messageContent)
            .setEmbeds(embed)
            .addActionRow(
              net.dv8tion.jda.api.interactions.components.buttons.Button.link(msg.getJumpUrl, "View Event")
            )
            .queue(
              _ => {
                logger.info(s"✅ Sent fixed 10h reminder for event ${event.id}")
                eventService.markFixedReminderAsSent(event.id)
              },
              err => logger.error(s"Failed to send fixed reminder for event ${event.id}", err)
            )
        },
        _ => {
          channel.sendMessage(messageContent)
            .setEmbeds(embed)
            .queue(
              _ => {
                logger.info(s"✅ Sent fixed 10h reminder for event ${event.id} (no link)")
                eventService.markFixedReminderAsSent(event.id)
              },
              err => logger.error(s"Failed to send fixed reminder for event ${event.id}", err)
            )
        }
      )
    } catch {
      case e: Exception =>
        logger.error(s"Error sending fixed reminder for event ${event.id}", e)
    }
  }
}
