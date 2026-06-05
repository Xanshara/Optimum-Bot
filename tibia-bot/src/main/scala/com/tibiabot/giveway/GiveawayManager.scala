package com.tibiabot.giveaway

import com.typesafe.scalalogging.StrictLogging
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.interactions.components.buttons.{Button, ButtonStyle}

import java.awt.Color
import java.sql.DriverManager
import java.time.Instant
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext
import scala.util.{Random, Try}

class GiveawayManager(postgresHost: String, postgresPassword: String)(implicit ec: ExecutionContext) extends StrictLogging {

  val ENTER_BUTTON_ID = "giveaway_enter"

  private val COLOR_ACTIVE = new Color(0xFF6600)
  private val COLOR_ENDED  = new Color(0x2F3136)

  private val DB_URL  = s"jdbc:postgresql://$postgresHost:5432/bot_cache"
  private val DB_USER = "postgres"
  private val DB_PASS = postgresPassword

  // ─── Inicjalizacja tabel ──────────────────────────────────────────────────

  def createTables(): Unit = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val st = conn.createStatement()
    st.executeUpdate(
      """CREATE TABLE IF NOT EXISTS giveaways (
        |  message_id  BIGINT PRIMARY KEY,
        |  channel_id  BIGINT NOT NULL,
        |  guild_id    BIGINT NOT NULL,
        |  user_id     BIGINT NOT NULL,
        |  end_time    BIGINT NOT NULL,
        |  winners     INT    NOT NULL DEFAULT 1,
        |  prize       VARCHAR(250) NOT NULL,
        |  description VARCHAR(1000),
        |  role_id     BIGINT,
        |  ended       BOOLEAN NOT NULL DEFAULT FALSE
        |);""".stripMargin)
    st.executeUpdate(
      """CREATE TABLE IF NOT EXISTS giveaway_entries (
        |  giveaway_id BIGINT NOT NULL,
        |  user_id     BIGINT NOT NULL,
        |  user_name   VARCHAR(100),
        |  entered_at  BIGINT NOT NULL,
        |  PRIMARY KEY (giveaway_id, user_id)
        |);""".stripMargin)
    Try { st.execute("ALTER TABLE giveaways ADD COLUMN IF NOT EXISTS role_id BIGINT") }
    // Migracja: kolumna title (tytuł/opis giveaway, oddzielna od nagrody)
    Try { st.execute("ALTER TABLE giveaways ADD COLUMN IF NOT EXISTS title VARCHAR(250)") }
    st.close()
    conn.close()
    logger.info("✅ Tabele giveaway gotowe")
  }

  // ─── CRUD ────────────────────────────────────────────────────────────────

  def saveGiveaway(g: Giveaway): Unit = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps = conn.prepareStatement(
      "INSERT INTO giveaways (message_id, channel_id, guild_id, user_id, end_time, winners, prize, description, role_id, title, ended) " +
      "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE) ON CONFLICT (message_id) DO NOTHING")
    ps.setLong(1, g.messageId)
    ps.setLong(2, g.channelId)
    ps.setLong(3, g.guildId)
    ps.setLong(4, g.userId)
    ps.setLong(5, g.endTime)
    ps.setInt(6, g.winners)
    ps.setString(7, g.prize)
    ps.setString(8, g.description.orNull)
    g.roleId match {
      case Some(r) => ps.setLong(9, r)
      case None    => ps.setNull(9, java.sql.Types.BIGINT)
    }
    ps.setString(10, g.title.orNull)
    ps.executeUpdate()
    ps.close()
    conn.close()
  }

  def markEnded(messageId: Long): Unit = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps = conn.prepareStatement("UPDATE giveaways SET ended = TRUE WHERE message_id = ?")
    ps.setLong(1, messageId)
    ps.executeUpdate()
    ps.close()
    conn.close()
  }

  def deleteGiveaway(messageId: Long): Unit = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps1 = conn.prepareStatement("DELETE FROM giveaway_entries WHERE giveaway_id = ?")
    ps1.setLong(1, messageId); ps1.executeUpdate(); ps1.close()
    val ps2 = conn.prepareStatement("DELETE FROM giveaways WHERE message_id = ?")
    ps2.setLong(1, messageId); ps2.executeUpdate(); ps2.close()
    conn.close()
  }

  def getGiveaway(messageId: Long): Option[Giveaway] = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps = conn.prepareStatement("SELECT * FROM giveaways WHERE message_id = ?")
    ps.setLong(1, messageId)
    val rs = ps.executeQuery()
    val result = if (rs.next()) Some(Giveaway.fromResultSet(rs)) else None
    rs.close(); ps.close(); conn.close()
    result
  }

  def getActiveGiveaways(): List[Giveaway] = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val st = conn.createStatement()
    val rs = st.executeQuery("SELECT * FROM giveaways WHERE ended = FALSE ORDER BY end_time ASC")
    val buf = ListBuffer[Giveaway]()
    while (rs.next()) buf += Giveaway.fromResultSet(rs)
    rs.close(); st.close(); conn.close()
    buf.toList
  }

  def getActiveGiveawaysByGuild(guildId: Long): List[Giveaway] =
    getActiveGiveaways().filter(_.guildId == guildId)

  def getExpiredGiveaways(): List[Giveaway] = {
    val now = Instant.now().getEpochSecond
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps = conn.prepareStatement("SELECT * FROM giveaways WHERE ended = FALSE AND end_time <= ?")
    ps.setLong(1, now)
    val rs = ps.executeQuery()
    val buf = ListBuffer[Giveaway]()
    while (rs.next()) buf += Giveaway.fromResultSet(rs)
    rs.close(); ps.close(); conn.close()
    buf.toList
  }

  // ─── Zgłoszenia ──────────────────────────────────────────────────────────

  def addEntry(giveawayId: Long, userId: Long, userName: String): Option[Int] = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    Try {
      val ps = conn.prepareStatement(
        "INSERT INTO giveaway_entries (giveaway_id, user_id, user_name, entered_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")
      ps.setLong(1, giveawayId)
      ps.setLong(2, userId)
      ps.setString(3, userName)
      ps.setLong(4, Instant.now().getEpochSecond)
      val rows = ps.executeUpdate()
      ps.close()
      if (rows == 0) { conn.close(); None }
      else { val c = countEntries(conn, giveawayId); conn.close(); Some(c) }
    }.getOrElse { conn.close(); None }
  }

  def countEntries(giveawayId: Long): Int = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val c = countEntries(conn, giveawayId); conn.close(); c
  }

  private def countEntries(conn: java.sql.Connection, giveawayId: Long): Int = {
    val ps = conn.prepareStatement("SELECT COUNT(*) FROM giveaway_entries WHERE giveaway_id = ?")
    ps.setLong(1, giveawayId)
    val rs = ps.executeQuery()
    val c = if (rs.next()) rs.getInt(1) else 0
    rs.close(); ps.close(); c
  }

  def getEntries(giveawayId: Long): List[(Long, String)] = {
    val conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)
    val ps = conn.prepareStatement("SELECT user_id, user_name FROM giveaway_entries WHERE giveaway_id = ?")
    ps.setLong(1, giveawayId)
    val rs = ps.executeQuery()
    val buf = ListBuffer[(Long, String)]()
    while (rs.next()) buf += ((rs.getLong("user_id"), Option(rs.getString("user_name")).getOrElse("Nieznany")))
    rs.close(); ps.close(); conn.close()
    buf.toList
  }

  // ─── Losowanie ───────────────────────────────────────────────────────────

  def pickWinners(entries: List[(Long, String)], count: Int): List[(Long, String)] = {
    if (entries.isEmpty) return List.empty
    Random.shuffle(entries).take(count.min(entries.size))
  }

  // ─── Embedy ──────────────────────────────────────────────────────────────

  /**
   * Embed aktywnego giveaway:
   *
   *   🎉 [tytuł]              ← embed title
   *   ─────────────────────
   *   [opis opcjonalny]
   *
   *   🎁 Nagroda: [prize]
   *   ⏰ Koniec: ...
   *   👤 Organizator: ...
   *   🎭 Rola: ... (jeśli podana)
   *   🎫 Uczestników: N
   *   🏆 Zwycięzców: N
   *
   *   Naciśnij przycisk poniżej, żeby wziąć udział!
   */
  def buildActiveEmbed(g: Giveaway, entryCount: Int): EmbedBuilder = {
    val eb = new EmbedBuilder()
    // Tytuł embeda — jeśli podano title używamy go, fallback na prize
    eb.setTitle(s"🎉 ${g.title.getOrElse(g.prize)}")
    eb.setColor(COLOR_ACTIVE)
    eb.setTimestamp(Instant.ofEpochSecond(g.endTime))

    val desc = new StringBuilder()

    // Opis (opcjonalny)
    g.description.foreach(d => desc.append(s"$d\n\n"))

    // Nagroda — pokazujemy tylko jeśli mamy osobny tytuł
    if (g.title.isDefined) {
      desc.append(s"🎁 **Nagroda:** ${g.prize}\n")
    }

    desc.append(s"⏰ **Koniec:** <t:${g.endTime}:R> (<t:${g.endTime}:f>)\n")
    desc.append(s"👤 **Organizator:** <@${g.userId}>\n")
    g.roleId.foreach(r => desc.append(s"🎭 **Wymagana rola:** <@&$r>\n"))
    desc.append(s"🎫 **Uczestników:** **$entryCount**\n")
    desc.append(s"🏆 **Zwycięzców:** **${g.winners}**\n\n")
    desc.append("Naciśnij przycisk poniżej, żeby wziąć udział!")

    eb.setDescription(desc.toString())
    eb.setFooter("Kończy się")
    eb
  }

  def buildEndedEmbed(g: Giveaway, entryCount: Int, winners: List[(Long, String)]): EmbedBuilder = {
    val eb = new EmbedBuilder()
    eb.setTitle(s"🎉 ${g.title.getOrElse(g.prize)}")
    eb.setColor(COLOR_ENDED)
    eb.setTimestamp(Instant.ofEpochSecond(g.endTime))

    val winnersStr = if (winners.isEmpty) "Brak uczestników"
                     else winners.map { case (id, _) => s"<@$id>" }.mkString(", ")

    val desc = new StringBuilder()
    g.description.foreach(d => desc.append(s"$d\n\n"))
    if (g.title.isDefined) {
      desc.append(s"🎁 **Nagroda:** ${g.prize}\n")
    }
    desc.append(s"⏰ **Zakończono:** <t:${g.endTime}:f>\n")
    desc.append(s"👤 **Organizator:** <@${g.userId}>\n")
    desc.append(s"🎫 **Uczestników:** **$entryCount**\n")
    desc.append(s"🏆 **Zwycięzcy:** $winnersStr")

    eb.setDescription(desc.toString())
    eb.setFooter("Zakończono")
    eb
  }

  def enterButton(): Button =
    Button.of(ButtonStyle.SUCCESS, ENTER_BUTTON_ID, "Weź udział 🎉")

  def formatWinnerMention(winners: List[(Long, String)]): String =
    if (winners.isEmpty) "nikt"
    else winners.map { case (id, _) => s"<@$id>" }.mkString(", ")
}

// ─── Model ────────────────────────────────────────────────────────────────────

case class Giveaway(
  messageId:   Long,
  channelId:   Long,
  guildId:     Long,
  userId:      Long,
  endTime:     Long,
  winners:     Int,
  prize:       String,          // nagroda (co można wygrać)
  description: Option[String],  // dodatkowy opis (warunki itp.)
  roleId:      Option[Long],
  title:       Option[String],  // tytuł giveaway (pokazywany na górze embeda)
  ended:       Boolean
)

object Giveaway {
  def fromResultSet(rs: java.sql.ResultSet): Giveaway = {
    val roleRaw = rs.getLong("role_id")
    Giveaway(
      messageId   = rs.getLong("message_id"),
      channelId   = rs.getLong("channel_id"),
      guildId     = rs.getLong("guild_id"),
      userId      = rs.getLong("user_id"),
      endTime     = rs.getLong("end_time"),
      winners     = rs.getInt("winners"),
      prize       = rs.getString("prize"),
      description = Option(rs.getString("description")),
      roleId      = if (rs.wasNull()) None else Some(roleRaw),
      title       = Option(rs.getString("title")),
      ended       = rs.getBoolean("ended")
    )
  }
}
