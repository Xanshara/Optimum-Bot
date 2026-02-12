package com.tibiabot.reactionrole

import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.Permission

/**
 * Definicje slash commands dla systemu Reaction Roles
 */
object ReactionRoleCommands {

  /**
   * Główna komenda /reactionrole - otwiera interaktywne menu
   */
  def getCommand() = {
    Commands.slash("reactionrole", "Manage reaction roles with interactive menu")
      .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_ROLES))
  }

  /**
   * Opis trybów działania reaction roles
   */
  val MODE_DESCRIPTIONS = Map(
    "normal" -> "Users can add/remove roles freely by reacting",
    "unique" -> "Users can only have ONE role from this message (mutual exclusivity)",
    "verify" -> "Role is permanent once given (won't be removed when unreacting)"
  )
}