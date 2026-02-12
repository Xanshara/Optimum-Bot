package com.tibiabot.poll

import com.tibiabot.Config
import com.typesafe.scalalogging.StrictLogging

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.ZonedDateTime
import scala.collection.mutable.ListBuffer
import scala.util.{Try, Success, Failure}

/**
 * Zarządza operacjami bazy danych dla ankiet
 */
class PollManager extends StrictLogging {

  private val url = s"jdbc:postgresql://${Config.postgresHost}:5432/bot_cache"
  private val username = "postgres"
  private val password = Config.postgresPassword

  /**
   * Tworzy tabele w bazie danych dla ankiet
   */
  def createTables(): Unit = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      val statement = conn.createStatement()

      // Tabela dla ankiet
      val createPollsTable = 
        """
        CREATE TABLE IF NOT EXISTS polls (
          poll_id VARCHAR(100) PRIMARY KEY,
          guild_id VARCHAR(50) NOT NULL,
          channel_id VARCHAR(50) NOT NULL,
          message_id VARCHAR(50) NOT NULL,
          question TEXT NOT NULL,
          options TEXT[] NOT NULL,
          allow_multiple BOOLEAN DEFAULT FALSE,
          created_by VARCHAR(50) NOT NULL,
          created_at TIMESTAMP WITH TIME ZONE NOT NULL,
          ends_at TIMESTAMP WITH TIME ZONE NOT NULL,
          is_active BOOLEAN DEFAULT TRUE
        );
        """

      // Tabela dla głosów
      val createVotesTable = 
        """
        CREATE TABLE IF NOT EXISTS poll_votes (
          id SERIAL PRIMARY KEY,
          poll_id VARCHAR(100) NOT NULL,
          user_id VARCHAR(50) NOT NULL,
          option_indices INTEGER[] NOT NULL,
          voted_at TIMESTAMP WITH TIME ZONE NOT NULL,
          UNIQUE(poll_id, user_id),
          FOREIGN KEY (poll_id) REFERENCES polls(poll_id) ON DELETE CASCADE
        );
        """

      // Indeksy dla szybszych zapytań
      val createIndexes = 
        """
        CREATE INDEX IF NOT EXISTS idx_polls_active ON polls(is_active, ends_at);
        CREATE INDEX IF NOT EXISTS idx_polls_guild ON polls(guild_id);
        CREATE INDEX IF NOT EXISTS idx_votes_poll ON poll_votes(poll_id);
        """

      statement.execute(createPollsTable)
      statement.execute(createVotesTable)
      statement.execute(createIndexes)
      
      statement.close()
      logger.info("✅ Poll database tables created successfully")
    } catch {
      case e: Exception =>
        logger.error("❌ Error creating poll tables", e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Zapisuje nową ankietę do bazy danych
   */
  def createPoll(poll: Poll): Try[Unit] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val insertStmt = conn.prepareStatement(
        """
        INSERT INTO polls (poll_id, guild_id, channel_id, message_id, question, options, 
                          allow_multiple, created_by, created_at, ends_at, is_active)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
      )
      
      // Konwersja listy opcji na PostgreSQL array
      val optionsArray = conn.createArrayOf("text", poll.options.toArray)
      
      insertStmt.setString(1, poll.pollId)
      insertStmt.setString(2, poll.guildId)
      insertStmt.setString(3, poll.channelId)
      insertStmt.setString(4, poll.messageId)
      insertStmt.setString(5, poll.question)
      insertStmt.setArray(6, optionsArray)
      insertStmt.setBoolean(7, poll.allowMultiple)
      insertStmt.setString(8, poll.createdBy)
      insertStmt.setTimestamp(9, Timestamp.from(poll.createdAt.toInstant))
      insertStmt.setTimestamp(10, Timestamp.from(poll.endsAt.toInstant))
      insertStmt.setBoolean(11, poll.isActive)
      
      insertStmt.executeUpdate()
      insertStmt.close()
      
      logger.info(s"✅ Poll ${poll.pollId} created in database")
      Success(())
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error creating poll ${poll.pollId}", e)
        Failure(e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Pobiera ankietę z bazy danych
   */
  def getPoll(pollId: String): Option[Poll] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val stmt = conn.prepareStatement("SELECT * FROM polls WHERE poll_id = ?")
      stmt.setString(1, pollId)
      
      val rs = stmt.executeQuery()
      
      val result = if (rs.next()) {
        val optionsArray = rs.getArray("options").getArray.asInstanceOf[Array[String]]
        
        Some(Poll(
          pollId = rs.getString("poll_id"),
          guildId = rs.getString("guild_id"),
          channelId = rs.getString("channel_id"),
          messageId = rs.getString("message_id"),
          question = rs.getString("question"),
          options = optionsArray.toList,
          allowMultiple = rs.getBoolean("allow_multiple"),
          createdBy = rs.getString("created_by"),
          createdAt = ZonedDateTime.ofInstant(rs.getTimestamp("created_at").toInstant, java.time.ZoneOffset.UTC),
          endsAt = ZonedDateTime.ofInstant(rs.getTimestamp("ends_at").toInstant, java.time.ZoneOffset.UTC),
          isActive = rs.getBoolean("is_active")
        ))
      } else {
        None
      }
      
      rs.close()
      stmt.close()
      result
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error getting poll $pollId", e)
        None
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Dodaje lub aktualizuje głos użytkownika
   */
  def vote(pollId: String, userId: String, optionIndices: List[Int]): Try[Unit] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      // Sprawdź czy ankieta jest aktywna
      val checkStmt = conn.prepareStatement("SELECT is_active FROM polls WHERE poll_id = ?")
      checkStmt.setString(1, pollId)
      val checkRs = checkStmt.executeQuery()
      
      if (!checkRs.next() || !checkRs.getBoolean("is_active")) {
        checkRs.close()
        checkStmt.close()
        return Failure(new Exception("Poll is not active"))
      }
      
      checkRs.close()
      checkStmt.close()
      
      // Usuń poprzedni głos użytkownika jeśli istnieje
      val deleteStmt = conn.prepareStatement("DELETE FROM poll_votes WHERE poll_id = ? AND user_id = ?")
      deleteStmt.setString(1, pollId)
      deleteStmt.setString(2, userId)
      deleteStmt.executeUpdate()
      deleteStmt.close()
      
      // Dodaj nowy głos
      val indicesArray = conn.createArrayOf("integer", optionIndices.map(Integer.valueOf).toArray)
      
      val insertStmt = conn.prepareStatement(
        "INSERT INTO poll_votes (poll_id, user_id, option_indices, voted_at) VALUES (?, ?, ?, ?)"
      )
      insertStmt.setString(1, pollId)
      insertStmt.setString(2, userId)
      insertStmt.setArray(3, indicesArray)
      insertStmt.setTimestamp(4, Timestamp.from(ZonedDateTime.now().toInstant))
      
      insertStmt.executeUpdate()
      insertStmt.close()
      
      logger.debug(s"✅ Vote recorded: poll=$pollId, user=$userId, options=${optionIndices.mkString(",")}")
      Success(())
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error recording vote: poll=$pollId, user=$userId", e)
        Failure(e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Pobiera wyniki ankiety
   */
  def getResults(pollId: String): List[PollResult] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      // Pobierz ankietę żeby znać opcje i typ
      val poll = getPoll(pollId)
      if (poll.isEmpty) return List.empty
      
      val options = poll.get.options
      val allowMultiple = poll.get.allowMultiple
      
      // Pobierz wszystkie głosy
      val stmt = conn.prepareStatement(
        "SELECT user_id, option_indices FROM poll_votes WHERE poll_id = ?"
      )
      stmt.setString(1, pollId)
      
      val rs = stmt.executeQuery()
      val voteCounts = new scala.collection.mutable.HashMap[Int, Int]()
      var totalVoters = 0
      
      while (rs.next()) {
        totalVoters += 1
        val indicesArray = rs.getArray("option_indices").getArray.asInstanceOf[Array[java.lang.Integer]]
        val indices = indicesArray.map(_.intValue()).toList
        
        // Policz głosy na każdą opcję
        indices.foreach { idx =>
          voteCounts.put(idx, voteCounts.getOrElse(idx, 0) + 1)
        }
      }
      
      rs.close()
      stmt.close()
      
      // Oblicz procenty
      val totalForPercentage = if (allowMultiple) totalVoters.toDouble else voteCounts.values.sum.toDouble
      
      // Utwórz wyniki dla każdej opcji
      val results = options.zipWithIndex.map { case (optionText, idx) =>
        val voteCount = voteCounts.getOrElse(idx, 0)
        val percentage = if (totalForPercentage > 0) (voteCount / totalForPercentage) * 100 else 0.0
        
        PollResult(idx, optionText, voteCount, percentage)
      }
      
      results
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error getting results for poll $pollId", e)
        List.empty
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Pobiera aktywne ankiety które się skończyły
   */
  def getExpiredPolls(): List[Poll] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val stmt = conn.prepareStatement(
        "SELECT * FROM polls WHERE is_active = TRUE AND ends_at <= ?"
      )
      stmt.setTimestamp(1, Timestamp.from(ZonedDateTime.now().toInstant))
      
      val rs = stmt.executeQuery()
      val polls = new ListBuffer[Poll]()
      
      while (rs.next()) {
        val optionsArray = rs.getArray("options").getArray.asInstanceOf[Array[String]]
        
        polls += Poll(
          pollId = rs.getString("poll_id"),
          guildId = rs.getString("guild_id"),
          channelId = rs.getString("channel_id"),
          messageId = rs.getString("message_id"),
          question = rs.getString("question"),
          options = optionsArray.toList,
          allowMultiple = rs.getBoolean("allow_multiple"),
          createdBy = rs.getString("created_by"),
          createdAt = ZonedDateTime.ofInstant(rs.getTimestamp("created_at").toInstant, java.time.ZoneOffset.UTC),
          endsAt = ZonedDateTime.ofInstant(rs.getTimestamp("ends_at").toInstant, java.time.ZoneOffset.UTC),
          isActive = rs.getBoolean("is_active")
        )
      }
      
      rs.close()
      stmt.close()
      polls.toList
    } catch {
      case e: Exception =>
        logger.error("❌ Error getting expired polls", e)
        List.empty
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Pobiera liczbę unikalnych głosujących w ankiecie
   */
  def getTotalVoters(pollId: String): Int = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val stmt = conn.prepareStatement(
        "SELECT COUNT(DISTINCT user_id) as voter_count FROM poll_votes WHERE poll_id = ?"
      )
      stmt.setString(1, pollId)
      
      val rs = stmt.executeQuery()
      val count = if (rs.next()) rs.getInt("voter_count") else 0
      
      rs.close()
      stmt.close()
      count
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error getting total voters for poll $pollId", e)
        0
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Dezaktywuje ankietę
   */
  def deactivatePoll(pollId: String): Try[Unit] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val stmt = conn.prepareStatement("UPDATE polls SET is_active = FALSE WHERE poll_id = ?")
      stmt.setString(1, pollId)
      stmt.executeUpdate()
      stmt.close()
      
      logger.info(s"✅ Poll $pollId deactivated")
      Success(())
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error deactivating poll $pollId", e)
        Failure(e)
    } finally {
      if (conn != null) conn.close()
    }
  }

  /**
   * Pobiera głos użytkownika dla danej ankiety
   */
  def getUserVote(pollId: String, userId: String): Option[List[Int]] = {
    var conn: Connection = null
    try {
      conn = DriverManager.getConnection(url, username, password)
      
      val stmt = conn.prepareStatement(
        "SELECT option_indices FROM poll_votes WHERE poll_id = ? AND user_id = ?"
      )
      stmt.setString(1, pollId)
      stmt.setString(2, userId)
      
      val rs = stmt.executeQuery()
      val result = if (rs.next()) {
        val indicesArray = rs.getArray("option_indices").getArray.asInstanceOf[Array[java.lang.Integer]]
        Some(indicesArray.map(_.intValue()).toList)
      } else {
        None
      }
      
      rs.close()
      stmt.close()
      result
    } catch {
      case e: Exception =>
        logger.error(s"❌ Error getting user vote: poll=$pollId, user=$userId", e)
        None
    } finally {
      if (conn != null) conn.close()
    }
  }
}
