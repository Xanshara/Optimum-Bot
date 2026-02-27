package com.tibiabot.splitloot

import scala.util.matching.Regex

/**
 * Parser dla danych sesji z Tibii
 */
object SessionParser {
  
  private val statKeywords = Set("loot", "supplies", "balance", "damage", "healing")
  private val metadataKeywords = Set(
    "Session data:", "Session:", "Loot Type:", "Loot:", 
    "Supplies:", "Balance:"
  )
  
  /**
   * Parsuje dane sesji z tekstu
   */
  def parse(data: String): SessionData = {
    val lines = data.trim.split("\n").toList
    var sessionInfo = SessionInfo()
    var players = List.empty[Player]
    var currentPlayer: Option[Player] = None
    
    lines.foreach { originalLine =>
      val line = originalLine.trim
      val hasIndent = originalLine.startsWith("\t") || originalLine.startsWith("    ")
      
      // Session metadata
      if (line.startsWith("Session data:")) {
        val pattern = "From (.*?) to (.*)$".r
        pattern.findFirstMatchIn(line).foreach { m =>
          sessionInfo = sessionInfo.copy(
            startTime = Some(m.group(1).trim),
            endTime = Some(m.group(2).trim)
          )
        }
      }
      else if (line.startsWith("Session: ")) {
        sessionInfo = sessionInfo.copy(
          duration = Some(line.split(": ")(1).trim)
        )
      }
      else if (line.startsWith("Loot Type: ")) {
        sessionInfo = sessionInfo.copy(
          lootType = Some(line.split(": ")(1).trim)
        )
      }
      else if (line.startsWith("Loot: ") && currentPlayer.isEmpty) {
        sessionInfo = sessionInfo.copy(
          loot = parseNumber(line.split(": ")(1))
        )
      }
      else if (line.startsWith("Supplies: ") && currentPlayer.isEmpty) {
        sessionInfo = sessionInfo.copy(
          supplies = parseNumber(line.split(": ")(1))
        )
      }
      else if (line.startsWith("Balance: ") && currentPlayer.isEmpty) {
        sessionInfo = sessionInfo.copy(
          partyBalance = parseNumber(line.split(": ")(1))
        )
      }
      // Player stats (z wcięciem)
      else if (hasIndent && currentPlayer.isDefined && line.contains(":")) {
        val parts = line.split(":", 2)
        if (parts.length >= 2) {
          val key = parts(0).trim.toLowerCase
          if (statKeywords.contains(key)) {
            val value = parseNumber(parts(1))
            currentPlayer = currentPlayer.map { player =>
              key match {
                case "loot" => player.copy(loot = value)
                case "supplies" => player.copy(supplies = value)
                case "balance" => player.copy(balance = value)
                case "damage" => player.copy(damage = value)
                case "healing" => player.copy(healing = value)
                case _ => player
              }
            }
          }
        }
      }
      // Player name (bez wcięcia, nie metadata)
      else if (line.nonEmpty && !hasIndent) {
        val isMetadata = metadataKeywords.exists(line.startsWith)
        val lowerLine = line.toLowerCase
        val isStat = statKeywords.exists(keyword => lowerLine.startsWith(keyword + ":"))
        
        if (!isMetadata && !isStat) {
          // Zapisz poprzedniego gracza
          currentPlayer.foreach(p => players = players :+ p)
          
          // Nowy gracz
          val playerName = line.replace("(Leader)", "").trim
          currentPlayer = Some(Player(name = playerName))
        }
      }
    }
    
    // Dodaj ostatniego gracza
    currentPlayer.foreach(p => players = players :+ p)
    
    SessionData(sessionInfo, players)
  }
  
  /**
   * Parsuje liczbę z tekstu (usuwa przecinki)
   */
  private def parseNumber(text: String): Int = {
    try {
      text.replace(",", "").trim.toInt
    } catch {
      case _: NumberFormatException => 0
    }
  }
}
