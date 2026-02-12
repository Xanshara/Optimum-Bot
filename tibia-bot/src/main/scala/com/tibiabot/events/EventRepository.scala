package com.tibiabot.events

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Timestamp}
import scala.util.{Try, Using}
import com.typesafe.scalalogging.StrictLogging

class EventRepository(dbUrl: String, dbUser: String, dbPassword: String) extends StrictLogging {

  private def getConnection: Connection = {
    DriverManager.getConnection(dbUrl, dbUser, dbPassword)
  }

  def createTablesIfNotExist(): Unit = {
    Using.resource(getConnection) { conn =>
      val stmt = conn.createStatement()
      
      // Tabela events z nowymi polami
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
          created_at TIMESTAMP DEFAULT NOW()
        )
      """)
      
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
      
      logger.info("Event tables created or already exist")
    }
  }

  def create(event: Event): Try[Event] = Try {
    Using.resource(getConnection) { conn =>
      val sql = """
        INSERT INTO events (
          guild_id, channel_id, message_id, title, description, event_time,
          tank_limit, healer_limit, dps_limit, mention_role_id, reminder_minutes,
          created_by
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        case Some(roleId) => stmt.setLong(10, roleId)
        case None => stmt.setNull(10, java.sql.Types.BIGINT)
      }
      stmt.setInt(11, event.reminderMinutes)
      stmt.setLong(12, event.createdBy)
      
      val rs = stmt.executeQuery()
      if (rs.next()) {
        event.copy(id = rs.getInt(1))
      } else {
        throw new Exception("Failed to create event")
      }
    }
  }

  def findById(id: Int): Option[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM events WHERE id = ?
        """)
        stmt.setInt(1, id)
        
        val rs = stmt.executeQuery()
        if (rs.next()) {
          Some(parseEvent(rs))
        } else {
          None
        }
      }
    }.toOption.flatten
  }

  def findActiveByGuild(guildId: Long): List[Event] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM events WHERE guild_id = ? AND active = TRUE
          ORDER BY event_time ASC
        """)
        stmt.setLong(1, guildId)
        
        val rs = stmt.executeQuery()
        var events = List.empty[Event]
        while (rs.next()) {
          events = events :+ parseEvent(rs)
        }
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
          active = ?
        WHERE id = ?
      """)
      
      stmt.setString(1, event.title)
      stmt.setString(2, event.description.orNull)
      stmt.setTimestamp(3, event.eventTime)
      stmt.setInt(4, event.tankLimit)
      stmt.setInt(5, event.healerLimit)
      stmt.setInt(6, event.dpsLimit)
      event.mentionRoleId match {
        case Some(roleId) => stmt.setLong(7, roleId)
        case None => stmt.setNull(7, java.sql.Types.BIGINT)
      }
      stmt.setInt(8, event.reminderMinutes)
      stmt.setBoolean(9, event.active)
      stmt.setInt(10, event.id)
      
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
      val count = stmt.executeUpdate()
      count
    }
  }
  
  def deleteEventsByGuild(guildId: Long): Try[Int] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("DELETE FROM events WHERE guild_id = ?")
      stmt.setLong(1, guildId)
      val count = stmt.executeUpdate()
      count
    }
  }

  def addSignup(eventId: Int, userId: Long, role: EventRole): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("""
        INSERT INTO event_signups (event_id, user_id, role)
        VALUES (?, ?, ?)
        ON CONFLICT (event_id, user_id) DO UPDATE SET role = ?
      """)
      
      stmt.setInt(1, eventId)
      stmt.setLong(2, userId)
      // POPRAWKA: Używamy EventRole.toString zamiast role.name!
      val roleString = EventRole.toString(role)
      stmt.setString(3, roleString)
      stmt.setString(4, roleString)
      
      stmt.executeUpdate()
    }
  }

  def removeSignup(eventId: Int, userId: Long): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("""
        DELETE FROM event_signups WHERE event_id = ? AND user_id = ?
      """)
      
      stmt.setInt(1, eventId)
      stmt.setLong(2, userId)
      stmt.executeUpdate()
    }
  }

  def getSignups(eventId: Int): List[EventSignup] = {
    Try {
      Using.resource(getConnection) { conn =>
        val stmt = conn.prepareStatement("""
          SELECT * FROM event_signups WHERE event_id = ? ORDER BY joined_at ASC
        """)
        stmt.setInt(1, eventId)
        
        val rs = stmt.executeQuery()
        var signups = List.empty[EventSignup]
        while (rs.next()) {
          signups = signups :+ EventSignup(
            eventId = rs.getInt("event_id"),
            userId = rs.getLong("user_id"),
            role = EventRole.fromString(rs.getString("role")).getOrElse(EventRole.Waitlist),
            joinedAt = rs.getTimestamp("joined_at")
          )
        }
        signups
      }
    }.getOrElse(List.empty)
  }
  
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
        while (rs.next()) {
          events = events :+ parseEvent(rs)
        }
        events
      }
    }.getOrElse(List.empty)
  }
  
  def markReminderSent(eventId: Int): Try[Unit] = Try {
    Using.resource(getConnection) { conn =>
      val stmt = conn.prepareStatement("""
        UPDATE events SET reminder_sent = TRUE WHERE id = ?
      """)
      stmt.setInt(1, eventId)
      stmt.executeUpdate()
    }
  }

  private def parseEvent(rs: ResultSet): Event = {
    Event(
      id = rs.getInt("id"),
      guildId = rs.getLong("guild_id"),
      channelId = rs.getLong("channel_id"),
      messageId = rs.getLong("message_id"),
      title = rs.getString("title"),
      description = Option(rs.getString("description")),
      eventTime = rs.getTimestamp("event_time"),
      tankLimit = rs.getInt("tank_limit"),
      healerLimit = rs.getInt("healer_limit"),
      dpsLimit = rs.getInt("dps_limit"),
      mentionRoleId = Option(rs.getLong("mention_role_id")).filter(_ != 0),
      reminderMinutes = rs.getInt("reminder_minutes"),
      reminderSent = rs.getBoolean("reminder_sent"),
      active = rs.getBoolean("active"),
      createdBy = rs.getLong("created_by"),
      createdAt = rs.getTimestamp("created_at")
    )
  }
}
