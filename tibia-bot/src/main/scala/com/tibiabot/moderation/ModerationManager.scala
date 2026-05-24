package com.tibiabot.moderation

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

// ──────────────────────────────────────────────
// Data models
// ──────────────────────────────────────────────

case class BanEntry(
  id: Long,
  userId: String,
  userName: String,
  reason: String,
  durationMinutes: Long,   // 0 = permanent
  expiresAt: Option[ZonedDateTime],
  bannedBy: String,
  bannedAt: ZonedDateTime,
  active: Boolean
)

case class MuteEntry(
  id: Long,
  userId: String,
  userName: String,
  reason: String,
  durationMinutes: Long,   // 0 = permanent
  expiresAt: Option[ZonedDateTime],
  mutedBy: String,
  mutedAt: ZonedDateTime,
  active: Boolean
)

// ──────────────────────────────────────────────
// Manager
// ──────────────────────────────────────────────

class ModerationManager extends StrictLogging {

  // Połączenie do per-guild bazy danych
  private def getConnection(guildId: String): Connection = {
    val url = s"jdbc:postgresql://${Config.postgresHost}:5432/_$guildId"
    DriverManager.getConnection(url, "postgres", Config.postgresPassword)
  }

  // ── INICJALIZACJA TABEL ────────────────────────────────────────────

  def initializeTables(guildId: String): Unit = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.createStatement()

      // Tabela banów
      st.execute(
        """CREATE TABLE IF NOT EXISTS bans (
          |  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |  user_id       VARCHAR(50)  NOT NULL,
          |  user_name     VARCHAR(255) NOT NULL,
          |  reason        TEXT         NOT NULL,
          |  duration_min  BIGINT       NOT NULL DEFAULT 0,
          |  expires_at    TIMESTAMP WITH TIME ZONE,
          |  banned_by     VARCHAR(255) NOT NULL,
          |  banned_at     TIMESTAMP WITH TIME ZONE NOT NULL,
          |  active        BOOLEAN NOT NULL DEFAULT TRUE
          |);""".stripMargin
      )

      // Tabela mutów
      st.execute(
        """CREATE TABLE IF NOT EXISTS mutes (
          |  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          |  user_id       VARCHAR(50)  NOT NULL,
          |  user_name     VARCHAR(255) NOT NULL,
          |  reason        TEXT         NOT NULL,
          |  duration_min  BIGINT       NOT NULL DEFAULT 0,
          |  expires_at    TIMESTAMP WITH TIME ZONE,
          |  muted_by      VARCHAR(255) NOT NULL,
          |  muted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
          |  active        BOOLEAN NOT NULL DEFAULT TRUE
          |);""".stripMargin
      )

      // Kolumna moderation_channel w discord_info (jeśli nie istnieje)
      val colCheck = st.executeQuery(
        "SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS " +
        "WHERE TABLE_NAME = 'discord_info' AND COLUMN_NAME = 'moderation_channel'"
      )
      if (!colCheck.next()) {
        st.execute("ALTER TABLE discord_info ADD COLUMN moderation_channel VARCHAR(50) DEFAULT '0'")
        logger.info(s"[$guildId] Dodano kolumnę moderation_channel do discord_info")
      }
      colCheck.close()

      st.close()
      logger.info(s"[$guildId] Tabele moderacji gotowe")
    } catch {
      case e: Exception => logger.error(s"[$guildId] Błąd inicjalizacji tabel moderacji", e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  // ── MODERATION CHANNEL ─────────────────────────────────────────────

  def getModerationChannel(guildId: String): String = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("SELECT moderation_channel FROM discord_info LIMIT 1")
      val rs = st.executeQuery()
      val result = if (rs.next()) Option(rs.getString("moderation_channel")).getOrElse("0") else "0"
      rs.close(); st.close()
      result
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd pobierania moderation_channel", e)
        "0"
    } finally {
      if (conn != null) conn.close()
    }
  }

  def setModerationChannel(guildId: String, channelId: String): Boolean = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("UPDATE discord_info SET moderation_channel = ?")
      st.setString(1, channelId)
      val updated = st.executeUpdate()
      st.close()
      updated > 0
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd ustawiania moderation_channel", e)
        false
    } finally {
      if (conn != null) conn.close()
    }
  }

  // ── BANY ───────────────────────────────────────────────────────────

  /** Zwraca aktywny ban użytkownika, jeśli istnieje. */
  def getActiveBan(guildId: String, userId: String): Option[BanEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement(
        "SELECT * FROM bans WHERE user_id = ? AND active = TRUE ORDER BY banned_at DESC LIMIT 1"
      )
      st.setString(1, userId)
      val rs = st.executeQuery()
      val result = if (rs.next()) Some(rowToBan(rs)) else None
      rs.close(); st.close()
      result
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd getActiveBan", e)
        None
    } finally {
      if (conn != null) conn.close()
    }
  }

  /** Dodaje ban do bazy danych. */
  def addBan(guildId: String, userId: String, userName: String,
             reason: String, durationMinutes: Long, bannedBy: String): Try[BanEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val now = ZonedDateTime.now()
      val expiresAt: Option[ZonedDateTime] =
        if (durationMinutes == 0) None
        else Some(now.plusMinutes(durationMinutes))

      // Dezaktywuj stare bany
      val deact = conn.prepareStatement("UPDATE bans SET active = FALSE WHERE user_id = ? AND active = TRUE")
      deact.setString(1, userId)
      deact.executeUpdate()
      deact.close()

      val st = conn.prepareStatement(
        """INSERT INTO bans (user_id, user_name, reason, duration_min, expires_at, banned_by, banned_at, active)
          |VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
          |RETURNING id""".stripMargin
      )
      st.setString(1, userId)
      st.setString(2, userName)
      st.setString(3, reason)
      st.setLong(4, durationMinutes)
      st.setTimestamp(5, expiresAt.map(t => Timestamp.from(t.toInstant)).orNull)
      st.setString(6, bannedBy)
      st.setTimestamp(7, Timestamp.from(now.toInstant))

      val rs = st.executeQuery()
      val id = if (rs.next()) rs.getLong(1) else -1L
      rs.close(); st.close()

      Success(BanEntry(id, userId, userName, reason, durationMinutes, expiresAt, bannedBy, now, active = true))
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd addBan", e)
        Failure(e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  /** Dezaktywuje ban użytkownika. */
  def removeBan(guildId: String, userId: String): Boolean = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("UPDATE bans SET active = FALSE WHERE user_id = ? AND active = TRUE")
      st.setString(1, userId)
      val rows = st.executeUpdate()
      st.close()
      rows > 0
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd removeBan", e)
        false
    } finally {
      if (conn != null) conn.close()
    }
  }

  /** Zwraca wszystkie aktywne bany. */
  def getActiveBans(guildId: String): List[BanEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("SELECT * FROM bans WHERE active = TRUE ORDER BY banned_at DESC")
      val rs = st.executeQuery()
      val results = ListBuffer[BanEntry]()
      while (rs.next()) results += rowToBan(rs)
      rs.close(); st.close()
      results.toList
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd getActiveBans", e)
        List.empty
    } finally {
      if (conn != null) conn.close()
    }
  }

  // ── MUTY ───────────────────────────────────────────────────────────

  def getActiveMute(guildId: String, userId: String): Option[MuteEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement(
        "SELECT * FROM mutes WHERE user_id = ? AND active = TRUE ORDER BY muted_at DESC LIMIT 1"
      )
      st.setString(1, userId)
      val rs = st.executeQuery()
      val result = if (rs.next()) Some(rowToMute(rs)) else None
      rs.close(); st.close()
      result
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd getActiveMute", e)
        None
    } finally {
      if (conn != null) conn.close()
    }
  }

  def addMute(guildId: String, userId: String, userName: String,
              reason: String, durationMinutes: Long, mutedBy: String): Try[MuteEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val now = ZonedDateTime.now()
      val expiresAt: Option[ZonedDateTime] =
        if (durationMinutes == 0) None
        else Some(now.plusMinutes(durationMinutes))

      val deact = conn.prepareStatement("UPDATE mutes SET active = FALSE WHERE user_id = ? AND active = TRUE")
      deact.setString(1, userId)
      deact.executeUpdate()
      deact.close()

      val st = conn.prepareStatement(
        """INSERT INTO mutes (user_id, user_name, reason, duration_min, expires_at, muted_by, muted_at, active)
          |VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
          |RETURNING id""".stripMargin
      )
      st.setString(1, userId)
      st.setString(2, userName)
      st.setString(3, reason)
      st.setLong(4, durationMinutes)
      st.setTimestamp(5, expiresAt.map(t => Timestamp.from(t.toInstant)).orNull)
      st.setString(6, mutedBy)
      st.setTimestamp(7, Timestamp.from(now.toInstant))

      val rs = st.executeQuery()
      val id = if (rs.next()) rs.getLong(1) else -1L
      rs.close(); st.close()

      Success(MuteEntry(id, userId, userName, reason, durationMinutes, expiresAt, mutedBy, now, active = true))
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd addMute", e)
        Failure(e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  def removeMute(guildId: String, userId: String): Boolean = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("UPDATE mutes SET active = FALSE WHERE user_id = ? AND active = TRUE")
      st.setString(1, userId)
      val rows = st.executeUpdate()
      st.close()
      rows > 0
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd removeMute", e)
        false
    } finally {
      if (conn != null) conn.close()
    }
  }

  def getActiveMutes(guildId: String): List[MuteEntry] = {
    var conn: Connection = null
    try {
      conn = getConnection(guildId)
      val st = conn.prepareStatement("SELECT * FROM mutes WHERE active = TRUE ORDER BY muted_at DESC")
      val rs = st.executeQuery()
      val results = ListBuffer[MuteEntry]()
      while (rs.next()) results += rowToMute(rs)
      rs.close(); st.close()
      results.toList
    } catch {
      case e: Exception =>
        logger.error(s"[$guildId] Błąd getActiveMutes", e)
        List.empty
    } finally {
      if (conn != null) conn.close()
    }
  }

  // ── HELPERS ────────────────────────────────────────────────────────

  private def rowToBan(rs: java.sql.ResultSet): BanEntry = {
    val expiresTs = rs.getTimestamp("expires_at")
    BanEntry(
      id            = rs.getLong("id"),
      userId        = rs.getString("user_id"),
      userName      = rs.getString("user_name"),
      reason        = rs.getString("reason"),
      durationMinutes = rs.getLong("duration_min"),
      expiresAt     = Option(expiresTs).map(t => ZonedDateTime.ofInstant(t.toInstant, java.time.ZoneOffset.UTC)),
      bannedBy      = rs.getString("banned_by"),
      bannedAt      = ZonedDateTime.ofInstant(rs.getTimestamp("banned_at").toInstant, java.time.ZoneOffset.UTC),
      active        = rs.getBoolean("active")
    )
  }

  private def rowToMute(rs: java.sql.ResultSet): MuteEntry = {
    val expiresTs = rs.getTimestamp("expires_at")
    MuteEntry(
      id            = rs.getLong("id"),
      userId        = rs.getString("user_id"),
      userName      = rs.getString("user_name"),
      reason        = rs.getString("reason"),
      durationMinutes = rs.getLong("duration_min"),
      expiresAt     = Option(expiresTs).map(t => ZonedDateTime.ofInstant(t.toInstant, java.time.ZoneOffset.UTC)),
      mutedBy       = rs.getString("muted_by"),
      mutedAt       = ZonedDateTime.ofInstant(rs.getTimestamp("muted_at").toInstant, java.time.ZoneOffset.UTC),
      active        = rs.getBoolean("active")
    )
  }

  // ── FORMAT CZASU ───────────────────────────────────────────────────

  def formatDuration(minutes: Long): String = {
    if (minutes == 0) return "Permanentny"
    val days    = minutes / 1440
    val hours   = (minutes % 1440) / 60
    val mins    = minutes % 60
    val parts   = ListBuffer[String]()
    if (days > 0)  parts += s"$days d"
    if (hours > 0) parts += s"$hours h"
    if (mins > 0)  parts += s"$mins min"
    parts.mkString(" ")
  }
}
