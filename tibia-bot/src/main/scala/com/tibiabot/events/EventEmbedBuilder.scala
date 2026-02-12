package com.tibiabot.events

import net.dv8tion.jda.api.{EmbedBuilder, JDA}
import net.dv8tion.jda.api.entities.MessageEmbed

import java.awt.Color
import java.text.SimpleDateFormat

/**
 * Builder embedów eventów
 */
class EventEmbedBuilder(jda: JDA) {

  private val dateFormat = new SimpleDateFormat("EEE MMM dd, yyyy HH:mm")

  def buildEventEmbed(
    event: Event,
    signupsByRole: Map[EventRole, List[EventSignup]]
  ): MessageEmbed = {

    val embed = new EmbedBuilder()

    // Tytuł
    embed.setTitle(event.title)

    // Opis
    event.description.foreach(embed.setDescription)

    // Kolor
    embed.setColor(if (event.active) Color.GREEN else Color.RED)

    // Czas
    val timeStr = dateFormat.format(event.eventTime)
    embed.addField("Time", s"📅 $timeStr", false)

    // Role
    addRole(embed, EventRole.Tank, signupsByRole, event.tankLimit)
    addRole(embed, EventRole.Healer, signupsByRole, event.healerLimit)
    addRole(embed, EventRole.DPS, signupsByRole, event.dpsLimit)

    // Waitlist
    val waitlist = signupsByRole.getOrElse(EventRole.Waitlist, Nil)
    if (waitlist.nonEmpty) {
      embed.addField(
        s"${EventRole.Waitlist.emoji} ${EventRole.Waitlist.name}",
        waitlist.map(s => s"<@${s.userId}>").mkString("\n"),
        false
      )
    }

    // Footer
    Option(jda.getUserById(event.createdBy)).foreach { user =>
      embed.setFooter(
        s"Event ID: ${event.id} • Created by ${user.getName}",
        user.getAvatarUrl
      )
    }

    // Zamknięty event
    if (!event.active) {
      embed.setTitle(s"${event.title} [CLOSED]")
    }

    embed.build()
  }

  private def addRole(
    embed: EmbedBuilder,
    role: EventRole,
    signupsByRole: Map[EventRole, List[EventSignup]],
    limit: Int
  ): Unit = {

    val signups = signupsByRole.getOrElse(role, Nil)

    val value =
      if (signups.isEmpty) "-"
      else signups.map(s => s"<@${s.userId}>").mkString("\n")

    embed.addField(
      s"${role.emoji} ${role.name} (${signups.size}/$limit)",
      value,
      true
    )
  }
}
