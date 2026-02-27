package com.tibiabot.splitloot

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.awt.Color
import java.time.Instant

/**
 * Generator embedów Discord dla wyników party hunt
 */
object EmbedGenerator {
  
  def generateEmbed(sessionData: SessionData): MessageEmbed = {
    val SessionData(sessionInfo, players) = sessionData
    
    val numPlayers = players.length
    val partyBalance = sessionInfo.partyBalance
    val playerShare = if (numPlayers > 0) partyBalance / numPlayers else 0
    
    // Obliczenia
    val lootPerHour = TransferCalculator.calculateLootPerHour(
      sessionInfo.loot, 
      sessionInfo.duration.getOrElse("00:00h")
    )
    val damagePercentages = TransferCalculator.calculateDamagePercentages(players)
    val healingPercentages = TransferCalculator.calculateHealingPercentages(players)
    val transfers = TransferCalculator.calculateTransfers(players, playerShare)
    
    // Tworzenie embeda
    val embed = new EmbedBuilder()
    embed.setTitle(s"Party Hunt Session - $numPlayers members")
    embed.setColor(new Color(255, 165, 0)) // Orange
    embed.setTimestamp(Instant.now())
    
    // Podstawowe info
    embed.addField(
      "Loot per hour",
      f"`$lootPerHour%,d` gp",
      true
    )
    
    embed.addField(
      "Party Balance",
      f"`$partyBalance%,d` gp",
      true
    )
    
    embed.addField(
      "Player's Share",
      f"`$playerShare%,d` gp",
      true
    )
    
    // Damage statistics
    if (damagePercentages.nonEmpty) {
      val damageText = damagePercentages.map { case (name, percent) =>
        f"> $name: **$percent%.2f%%**"
      }.mkString("\n")
      
      embed.addField(
        "Damage",
        damageText,
        false
      )
    }
    
    // Healing statistics
    if (healingPercentages.nonEmpty) {
      val healingText = healingPercentages.map { case (name, percent) =>
        f"> $name: **$percent%.2f%%**"
      }.mkString("\n")
      
      embed.addField(
        "Healing",
        healingText,
        false
      )
    }
    
    // Splitting Instructions
    if (transfers.nonEmpty) {
      val transferText = transfers.map { t =>
        s"**${t.from}**\n```transfer ${t.amount} to ${t.to}```"
      }.mkString("\n")
      
      embed.addField(
        "Splitting Instructions",
        transferText,
        false
      )
    } else {
      embed.addField(
        "Splitting Instructions",
        "> All players are even! No transfers needed.",
        false
      )
    }
    
    // Footer
    val duration = sessionInfo.duration.getOrElse("N/A")
    val startTime = sessionInfo.startTime.getOrElse("N/A")
    embed.setFooter(s"Duration: $duration | Hunt on $startTime")
    
    embed.build()
  }
}
