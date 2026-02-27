package com.tibiabot.events

import java.sql.{Connection, DriverManager, ResultSet, Timestamp}
import scala.util.{Try, Using}
import com.typesafe.scalalogging.StrictLogging

class EventRepository(dbUrl: String, dbUser: String, dbPassword: String) extends StrictLogging {

  private def getConnection: Connection =
    DriverManager.getConnection(dbUrl, dbUser, dbPassword)

  def createTablesIfNotExist(): Unit = {
    Using.resource(getConnection) { conn =>
      val stmt = conn.createStatement()

      // Tabela events
      stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS events (
          id SERIAL PRIMARY KEY,
          guild_id BIGINT NOT NULL,
          channel_id BIGINT NOT NULL,
          message_id BIGINT NOT NULL,
          title TEXT NOT NULL,
          description TEXT,
          event_time TIMESTAMP NOT NULL,
          tank_limit INT NOT NULL,
          healer_limit INT NOT NULL,
          dps_limit INT NOT NULL,
          mention_role_id BIGINT,
          reminder_minutes INT DEFAULT 15,
          reminder_sent BOOLEAN DEFAULT FALSE,
          active BOOLEAN DEFAULT TRUE,
          created_by BIGINT NOT NULL,
          created_at TIMESTAMP DEFAULT NOW(),
          is_recurring BOOLEAN DEFAULT FALSE,
          recurring_interval_days INT,
          next_event_created BOOLEAN DEFAULT FALSE
        )
      """)

      // Migracja — dodaj kolumny jeśli tabela już istniała bez nich
      val migrations = List(
        "ALTER TABLE events ADD COLUMN IF NOT EXISTS is_recurring BOOLEAN DEFAULT FALSE",
        "ALTER TABLE events ADD COLUMN IF NOT EXISTS recurring_interval_days INT",
        "ALTER TABLE events ADD COLUMN IF NOT EXISTS next_event_created BOOLEAN DEFAULT FALSE",
        "ALTER TABLE events ADD COLUMN IF NOT EXISTS fixed_reminder_sent BOOLEAN DEFAULT FALSE"
      )
      migrations.foreach { sql =>
        Try(stmt.executeUpdate(sql)).recover {
          case e => logger.warn(s"Migration skipped: ${e.getMessage}")
        }
      }

      // Tabela signupów
      stmt.executeUpdate("""
        CREATE TABLE IF NOT EXISTS event_signups (
          event_id INT REFERENCES events(id) ON DELETE CASCADE,
          user_id BIGINT NOT NULL,
          role VARCHAR(16) NOT NULL,
          joined_at TIMESTAMP DEFAULT NOW(),
          PRIMARY KEY (event_id, user_id)
        )
      """)

      logger.info("Event tables ready")
    }
  }

  def create(event: Event): Try[Event] = Try {
    Using.resource(getConnection) { conn =>
      val sql = """
        INSERT INTO events (
          guild_id, channel_id, message_id, title, description, event_time,
          tank_limit, healer_limit, dps_limit, mention_role_id, reminder_minutes,
          created_by, is_recurring, recurring_interval_days
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
      """
      val stmt = conn.prepareStatement(sql)
      stmt.setLong(1, event.guildId)
      stmt.setLong(2, event.channelId)
      stmt.setLong(3, event.messageId)
      stmt.setString(4, event.title)
      stmt.setString(5, event.description.orNull)
      stmt.setTimestamp(6, event.eventTime)
      stmt.setInt(7, event.tankLimit)
      stmt.setInt(8, event.healerLimit)
      stmt.setInt(9, event.dpsLimit)
      event.mentionRoleId match {
        case Some(r) => stmt.setLong(10, r)
        case None    => stmt.setNull(10, java.sql.Types.BIGINT)
      }
      stmt.setInt(11, event.reminderMinutes)
      stmt.setLong(12, event.createdBy)
      stmt.setBoolean(13, event.isRecurring)
      event.recurringIntervalDays match {
        case Some(d) => stmt.setInt(14, d)
        case None    => stmt.setNull(14, java.sql.Types.INTEGER)
      }

      val rs = stmt.executeQuery()
      if (rs.next()) event.copy(id = rs.getInt(1))
      else throw new Exception("Failed to create event")
    }
  }

  def findById(id: Int): Option[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("SELECT * FROM events WHERE id = ?")
        stmt.setInt(1, id)
        val rs = stmt.executeQuery()
        if (rs.next()) Some(parseEvent(rs)) else None
      }
    }.toOption.flatten
  }

  def findActiveByGuild(guildId: Long): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement(
          "SELECT * FROM events WHERE guild_id = ? AND active = TRUE ORDER BY event_time ASC"
        )
        stmt.setLong(1, guildId)
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) events = events :+ parseEvent(rs)
        events
      }
    }.getOrElse(List.empty)
  }

  def findAllActive(): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement(
          "SELECT * FROM events WHERE active = TRUE ORDER BY event_time ASC"
        )
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) events = events :+ parseEvent(rs)
        events
      }
    }.getOrElse(List.empty)
  }

  def update(event: Event): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("""
        UPDATE events SET
          title = ?,
          description = ?,
          event_time = ?,
          tank_limit = ?,
          healer_limit = ?,
          dps_limit = ?,
          mention_role_id = ?,
          reminder_minutes = ?,
          active = ?,
          is_recurring = ?,
          recurring_interval_days = ?,
          next_event_created = ?,
          fixed_reminder_sent = ?
        WHERE id = ?
      """)
      stmt.setString(1, event.title)
      stmt.setString(2, event.description.orNull)
      stmt.setTimestamp(3, event.eventTime)
      stmt.setInt(4, event.tankLimit)
      stmt.setInt(5, event.healerLimit)
      stmt.setInt(6, event.dpsLimit)
      event.mentionRoleId match {
        case Some(r) => stmt.setLong(7, r)
        case None    => stmt.setNull(7, java.sql.Types.BIGINT)
      }
      stmt.setInt(8, event.reminderMinutes)
      stmt.setBoolean(9, event.active)
      stmt.setBoolean(10, event.isRecurring)
      event.recurringIntervalDays match {
        case Some(d) => stmt.setInt(11, d)
        case None    => stmt.setNull(11, java.sql.Types.INTEGER)
      }
      stmt.setBoolean(12, event.nextEventCreated)
      stmt.setBoolean(13, event.fixedReminderSent)
      stmt.setInt(14, event.id)
      stmt.executeUpdate()
    }
  }

  def delete(id: Int): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("DELETE FROM events WHERE id = ?")
      stmt.setInt(1, id)
      stmt.executeUpdate()
    }
  }

  def deleteAllEvents(): Try[Int] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("DELETE FROM events")
      stmt.executeUpdate()
    }
  }

  def deleteEventsByGuild(guildId: Long): Try[Int] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("DELETE FROM events WHERE guild_id = ?")
      stmt.setLong(1, guildId)
      stmt.executeUpdate()
    }
  }

  // ========== RECURRING ==========

  /**
   * Znajdź cykliczne eventy gotowe do odnowienia:
   * - is_recurring = true
   * - active = false (zamknięte)
   * - next_event_created = false (jeszcze nie odnowione)
   * - event_time już minął
   */
  def findRecurringEventsToProcess(): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM events
          WHERE is_recurring = TRUE
          AND active = FALSE
          AND next_event_created = FALSE
          AND event_time < NOW()
        """)
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) events = events :+ parseEvent(rs)
        events
      }
    }.getOrElse(List.empty)
  }

  def markNextEventCreated(eventId: Int): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement(
        "UPDATE events SET next_event_created = TRUE WHERE id = ?"
      )
      stmt.setInt(1, eventId)
      stmt.executeUpdate()
    }
  }

  // ========== SIGNUPS ==========

  def addSignup(eventId: Int, userId: Long, role: EventRole): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("""
        INSERT INTO event_signups (event_id, user_id, role)
        VALUES (?, ?, ?)
        ON CONFLICT (event_id, user_id) DO UPDATE SET role = ?
      """)
      val roleStr = EventRole.toString(role)
      stmt.setInt(1, eventId)
      stmt.setLong(2, userId)
      stmt.setString(3, roleStr)
      stmt.setString(4, roleStr)
      stmt.executeUpdate()
    }
  }

  def removeSignup(eventId: Int, userId: Long): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement(
        "DELETE FROM event_signups WHERE event_id = ? AND user_id = ?"
      )
      stmt.setInt(1, eventId)
      stmt.setLong(2, userId)
      stmt.executeUpdate()
    }
  }

  def getSignups(eventId: Int): List[EventSignup] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement(
          "SELECT * FROM event_signups WHERE event_id = ? ORDER BY joined_at ASC"
        )
        stmt.setInt(1, eventId)
        val rs = stmt.executeQuery()
        var signups = List.empty[EventSignup]
        while (rs.next()) {
          signups = signups :+ EventSignup(
            eventId  = rs.getInt("event_id"),
            userId   = rs.getLong("user_id"),
            role     = EventRole.fromString(rs.getString("role")).getOrElse(EventRole.Waitlist),
            joinedAt = rs.getTimestamp("joined_at")
          )
        }
        signups
      }
    }.getOrElse(List.empty)
  }

  // ========== REMINDERS ==========

  def findEventsNeedingReminder(): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM events
          WHERE active = TRUE
          AND reminder_sent = FALSE
          AND event_time <= NOW() + (reminder_minutes || ' minutes')::INTERVAL
          AND event_time > NOW()
        """)
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) events = events :+ parseEvent(rs)
        events
      }
    }.getOrElse(List.empty)
  }

  def findEventsNeedingFixedReminder(): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM events
          WHERE active = TRUE
          AND fixed_reminder_sent = FALSE
          AND event_time <= NOW() + INTERVAL '10 hours'
          AND event_time > NOW()
        """)
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) events = events :+ parseEvent(rs)
        events
      }
    }.getOrElse(List.empty)
  }

  def markReminderSent(eventId: Int): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement(
        "UPDATE events SET reminder_sent = TRUE WHERE id = ?"
      )
      stmt.setInt(1, eventId)
      stmt.executeUpdate()
    }
  }

  def markFixedReminderSent(eventId: Int): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement(
        "UPDATE events SET fixed_reminder_sent = TRUE WHERE id = ?"
      )
      stmt.setInt(1, eventId)
      stmt.executeUpdate()
    }
  }

  // ========== PARSER ==========

  private def parseEvent(rs: ResultSet): Event = {
    val recurringInterval = rs.getInt("recurring_interval_days")
    Event(
      id                   = rs.getInt("id"),
      guildId              = rs.getLong("guild_id"),
      channelId            = rs.getLong("channel_id"),
      messageId            = rs.getLong("message_id"),
      title                = rs.getString("title"),
      description          = Option(rs.getString("description")),
      eventTime            = rs.getTimestamp("event_time"),
      tankLimit            = rs.getInt("tank_limit"),
      healerLimit          = rs.getInt("healer_limit"),
      dpsLimit             = rs.getInt("dps_limit"),
      mentionRoleId        = Option(rs.getLong("mention_role_id")).filter(_ != 0),
      reminderMinutes      = rs.getInt("reminder_minutes"),
      reminderSent         = rs.getBoolean("reminder_sent"),
      active               = rs.getBoolean("active"),
      createdBy            = rs.getLong("created_by"),
      createdAt            = rs.getTimestamp("created_at"),
      isRecurring          = rs.getBoolean("is_recurring"),
      recurringIntervalDays = if (rs.wasNull()) None else Some(recurringInterval),
      nextEventCreated     = rs.getBoolean("next_event_created"),
      fixedReminderSent    = rs.getBoolean("fixed_reminder_sent")
    )
  }
}
