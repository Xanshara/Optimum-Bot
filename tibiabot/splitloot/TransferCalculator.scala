package com.tibiabot.splitloot

import scala.collection.mutable.ListBuffer

/**
 * Kalkulator transferów między graczami - POPRAWIONA WERSJA
 */
object TransferCalculator {
  
  /**
   * Oblicza transfery - kto komu ile musi zapłacić
   * Ulepszona wersja która groupuje transfery po nadawcy
   */
  def calculateTransfers(players: List[Player], playerShare: Int): List[Transfer] = {
    if (players.isEmpty) return List.empty
    
    // Oblicz różnice (ile każdy jest nad/pod balance)
    case class Balance(name: String, diff: Int)
    
    val balances = players.map { player =>
      val diff = player.balance - playerShare
      Balance(player.name, diff)
    }
    
    // Podziel na kredytodawców (mają nadwyżkę - muszą oddać) i dłużników (mają niedobór - dostają)
    val creditors = balances.filter(_.diff > 0).sortBy(-_.diff).to(ListBuffer) // Sortuj malejąco (najwięcej oddaje pierwszy)
    val debtors = balances.filter(_.diff < 0).sortBy(_.diff).to(ListBuffer) // Sortuj rosnąco (najbardziej ujemne pierwsze = największy dług)
    
    val transfers = ListBuffer.empty[Transfer]
    
    var i = 0
    var j = 0
    
    // Śledź ile każdy kredytor jeszcze ma do oddania
    val remainingToGive = creditors.map(c => c.name -> c.diff).toMap.to(scala.collection.mutable.Map)
    // Śledź ile każdy dłużnik jeszcze ma do otrzymania
    val remainingToReceive = debtors.map(d => d.name -> Math.abs(d.diff)).toMap.to(scala.collection.mutable.Map)
    
    while (i < creditors.length && j < debtors.length) {
      val creditor = creditors(i)
      val debtor = debtors(j)
      
      val creditorRemaining = remainingToGive(creditor.name)
      val debtorRemaining = remainingToReceive(debtor.name)
      
      if (creditorRemaining <= 0) {
        i += 1
      } else if (debtorRemaining <= 0) {
        j += 1
      } else {
        // Ile można przelać
        val amount = Math.min(creditorRemaining, debtorRemaining)
        
        if (amount > 0) {
          transfers += Transfer(
            from = creditor.name,
            to = debtor.name,
            amount = amount
          )
          
          // Aktualizuj salda
          remainingToGive(creditor.name) = creditorRemaining - amount
          remainingToReceive(debtor.name) = debtorRemaining - amount
        }
        
        // Przejdź do następnego jeśli saldo = 0
        if (remainingToGive(creditor.name) <= 0) i += 1
        if (remainingToReceive(debtor.name) <= 0) j += 1
      }
    }
    
    transfers.toList
  }
  
  /**
   * Oblicza procentowy udział w damage
   */
  def calculateDamagePercentages(players: List[Player]): Map[String, Double] = {
    val totalDamage = players.map(_.damage).sum
    if (totalDamage == 0) return Map.empty
    
    players.map { player =>
      player.name -> Math.round(player.damage.toDouble / totalDamage * 10000) / 100.0
    }.sortBy(-_._2).toMap
  }
  
  /**
   * Oblicza procentowy udział w healing
   */
  def calculateHealingPercentages(players: List[Player]): Map[String, Double] = {
    val totalHealing = players.map(_.healing).sum
    if (totalHealing == 0) return Map.empty
    
    players.map { player =>
      player.name -> Math.round(player.healing.toDouble / totalHealing * 10000) / 100.0
    }.sortBy(-_._2).toMap
  }
  
  /**
   * Oblicza loot per hour
   */
  def calculateLootPerHour(loot: Int, duration: String): Int = {
    try {
      // Format: "01:30h" lub "1:30h"
      val timePattern = """(\d+):(\d+)h?""".r
      duration match {
        case timePattern(hours, minutes) =>
          val totalMinutes = hours.toInt * 60 + minutes.toInt
          if (totalMinutes > 0) {
            ((loot.toDouble / totalMinutes) * 60).toInt
          } else {
            0
          }
        case _ => 0
      }
    } catch {
      case _: Exception => 0
    }
  }
}
