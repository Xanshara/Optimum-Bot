package com.tibiabot.events

import net.dv8tion.jda.api.{EmbedBuilder, JDA}
import net.dv8tion.jda.api.entities.MessageEmbed

import java.awt.Color
import java.text.SimpleDateFormat

class EventEmbedBuilder(jda: JDA) {

  private val dateFormat = new SimpleDateFormat("EEE MMM dd, yyyy HH:mm")

  def buildEventEmbed(
    event: Event,
    signupsByRole: Map[EventRole, List[EventSignup]]
  ): MessageEmbed = {

    val embed = new EmbedBuilder()

    // Tytuł
    val titlePrefix = if (!event.active) "[CLOSED] " else ""
    embed.setTitle(s"$titlePrefix${event.title}")

    // Opis
    event.description.foreach(embed.setDescription)

    // Kolor
    embed.setColor(if (event.active) Color.GREEN else Color.RED)

    // Czas
    val timeStr = dateFormat.format(event.eventTime)
    embed.addField("Time", s"📅 $timeStr", false)

    // Cykliczność
    if (event.isRecurring) {
      val intervalText = event.recurringIntervalDays match {
        case Some(1)  => "co 1 dzień"
        case Some(2)  => "co 2 dni"
        case Some(7)  => "co 7 dni (tygodniowo)"
        case Some(14) => "co 14 dni (dwutygodniowo)"
        case Some(30) => "co 30 dni (miesięcznie)"
        case Some(n)  => s"co $n dni"
        case None     => "cykliczny"
      }
      embed.addField("🔁 Powtarzanie", intervalText, false)
    }

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

    embed.build()
  }

  private def addRole(
    embed: EmbedBuilder,
    role: EventRole,
    signupsByRole: Map[EventRole, List[EventSignup]],
    limit: Int
  ): Unit = {
    val signups = signupsByRole.getOrElse(role, Nil)
    val value   = if (signups.isEmpty) "-" else signups.map(s => s"<@${s.userId}>").mkString("\n")
    embed.addField(s"${role.emoji} ${role.name} (${signups.size}/$limit)", value, true)
  }
}
