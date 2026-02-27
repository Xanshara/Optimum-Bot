package com.tibiabot.events

import net.dv8tion.jda.api.events.interaction.component.{ButtonInteractionEvent, StringSelectInteractionEvent}
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.components.text.{TextInput, TextInputStyle}
import net.dv8tion.jda.api.interactions.modals.Modal
import net.dv8tion.jda.api.{JDA, Permission}
import com.typesafe.scalalogging.StrictLogging
import scala.util.{Success, Failure, Try}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

class EventButtonListener(
  jda: JDA,
  eventService: EventService,
  embedBuilder: EventEmbedBuilder,
  eventCommand: EventCommand
) extends ListenerAdapter with StrictLogging {

  // Tymczasowe przechowywanie wybranych użytkowników do usunięcia
  private val selectedUsersToRemove = new TrieMap[String, List[Long]]()

  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId

    // Sprawdź czy to przycisk eventu
    if (!buttonId.startsWith("event:")) return

    // Multi-step create continue buttons
    if (buttonId.startsWith("event:continue_step")) {
      handleContinueButton(event)
      return
    }

    // Cykliczne — jednorazowy
    if (buttonId.startsWith("event:recurring_no:")) {
      val targetUserId = buttonId.split(":").last.toLong
      if (event.getUser.getIdLong != targetUserId) {
        event.reply("❌ This is not your session.").setEphemeral(true).queue()
      } else {
        event.deferReply(true).queue()
        eventCommand.finalizeEventCreation(targetUserId, isRecurring = false, None, event.getHook)
      }
      return
    }

    // Cykliczne — pokaż wybór interwału
    if (buttonId.startsWith("event:recurring_yes:")) {
      val targetUserId = buttonId.split(":").last.toLong
      if (event.getUser.getIdLong != targetUserId) {
        event.reply("❌ This is not your session.").setEphemeral(true).queue()
      } else {
        event.deferReply(true).queue()

        val dropdown = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
          .create(s"event:recurring_interval:$targetUserId")
          .setPlaceholder("Wybierz co ile dni event ma się powtarzać")
          .addOption("Co 1 dzień",   "1",  "Powtarza się codziennie")
          .addOption("Co 2 dni",     "2",  "Co dwa dni")
          .addOption("Co 7 dni",     "7",  "Tygodniowo")
          .addOption("Co 14 dni",    "14", "Co dwa tygodnie")
          .addOption("Co 30 dni",    "30", "Miesięcznie")
          .build()

        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("🔁 Ustaw interwał powtarzania")
          .setDescription("Wybierz co ile dni event ma się automatycznie odnawiać:")
          .setColor(java.awt.Color.BLUE)
          .build()

        event.getHook.sendMessageEmbeds(embed)
          .addActionRow(dropdown)
          .setEphemeral(true)
          .queue()
      }
      return
    }

    // Multi-step edit — Step 2 button
    if (buttonId.startsWith("event:edit_step2:")) {
      val parts = buttonId.split(":")
      if (parts.length >= 3) {
        val targetUserId = parts(2).toLong
        if (event.getUser.getIdLong != targetUserId) {
          event.reply("❌ This is not your edit session.").setEphemeral(true).queue()
        } else {
          eventCommand.openEditStep2Modal(event, targetUserId)
        }
      }
      return
    }

    // Manage buttons
    if (buttonId.startsWith("event:manage_show_remove:")) {
      handleShowRemoveList(event)
      return
    }

    if (buttonId.startsWith("event:manage_show_add:")) {
      handleShowAddList(event)
      return
    }

    if (buttonId.startsWith("event:confirm_remove:")) {
      handleConfirmRemove(event)
      return
    }

    if (buttonId.startsWith("event:confirm_add:")) {
      handleConfirmAdd(event)
      return
    }

    if (buttonId.startsWith("event:cancel_manage:")) {
      event.editMessage("❌ Cancelled")
        .setComponents()
        .queue(
          _ => logger.debug("Manage dialog cancelled"),
          error => logger.error(s"Failed to cancel manage dialog: ${error.getMessage}")
        )
      return
    }

    // Regular event buttons
    logger.info(s"Button clicked: $buttonId")

    EventButtons.parseButtonId(buttonId) match {
      case None =>
        logger.error(s"Failed to parse button ID: $buttonId")
        event.reply("❌ Invalid button ID").setEphemeral(true).queue()

      case Some((eventId, action)) =>
        logger.info(s"Parsed button - eventId: $eventId, action: '$action'")

        action match {
          case "tank"    => handleRoleSignup(event, eventId, EventRole.Tank)
          case "healer"  => handleRoleSignup(event, eventId, EventRole.Healer)
          case "dps"     => handleRoleSignup(event, eventId, EventRole.DPS)
          case "waitlist" => handleRoleSignup(event, eventId, EventRole.Waitlist)
          case "leave"   => handleLeave(event, eventId)
          case "manage"  => handleManage(event, eventId)
          case "edit"    => handleEdit(event, eventId)
          case "delete"  => handleDelete(event, eventId)
          case _ =>
            logger.warn(s"Unknown action: '$action'")
            event.reply("❌ Unknown action").setEphemeral(true).queue()
        }
    }
  }

  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    val selectId = event.getComponentId

    if (selectId.startsWith("event:select_role:")) {
      handleRoleSelection(event)
      return
    }

    // Wybór interwału cyklicznego
    if (selectId.startsWith("event:recurring_interval:")) {
      val targetUserId = selectId.split(":").last.toLong
      if (event.getUser.getIdLong != targetUserId) {
        event.reply("❌ This is not your session.").setEphemeral(true).queue()
        return
      }
      val intervalDays = event.getValues.get(0).toInt
      event.deferReply(true).queue()
      eventCommand.finalizeEventCreation(targetUserId, isRecurring = true, Some(intervalDays), event.getHook)
      return
    }

    // Ustawienie interwału cyklicznego dla istniejącego eventu (z /event recurring on)
    if (selectId.startsWith("event:set_recurring_interval:")) {
      val eventId = selectId.split(":").last.toInt
      val intervalDays = event.getValues.get(0).toInt
      event.deferReply(true).queue()

      eventService.getEvent(eventId) match {
        case None =>
          event.getHook.sendMessage(s"❌ Event $eventId not found").setEphemeral(true).queue()
        case Some(existing) =>
          val updated = existing.copy(isRecurring = true, recurringIntervalDays = Some(intervalDays), nextEventCreated = false)
          eventService.updateEvent(updated) match {
            case scala.util.Success(_) =>
              eventCommand.updateEventMessage(updated)
              event.getHook.sendMessage(s"✅ Event **${existing.title}** (ID: $eventId) — cykliczność **włączona** (co $intervalDays dni)").setEphemeral(true).queue()
            case scala.util.Failure(e) =>
              event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
          }
      }
      return
    }

    if (selectId.startsWith("event:remove_select:")) {
      handleRemoveSelection(event)
      return
    }

    if (selectId.startsWith("event:add_select:")) {
      handleAddSelection(event)
      return
    }

    if (selectId.startsWith("event:add_role_select:")) {
      handleAddRoleSelection(event)
      return
    }
  }

  // ========== ROLE SIGNUP ==========

  private def handleRoleSignup(event: ButtonInteractionEvent, eventId: Int, role: EventRole): Unit = {
    event.deferEdit().queue()
    val userId = event.getUser.getIdLong
    logger.info(s"User $userId clicked button for role: ${role.name}")

    eventService.signupUser(eventId, userId, role) match {
      case Success(assignedRole) =>
        logger.info(s"User $userId assigned to role: ${assignedRole.name}")
        updateEventMessage(eventId, event.getMessage)

      case Failure(e) if e.getMessage == "UNREGISTER" =>
        logger.info(s"User $userId unregistered from event $eventId")
        updateEventMessage(eventId, event.getMessage)

      case Failure(e) =>
        logger.error(s"Failed to signup user $userId for role ${role.name}: ${e.getMessage}")
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }

  // ========== LEAVE ==========

  private def handleLeave(event: ButtonInteractionEvent, eventId: Int): Unit = {
    event.deferEdit().queue()
    val userId = event.getUser.getIdLong

    eventService.unsignupUser(eventId, userId) match {
      case Success(_) =>
        updateEventMessage(eventId, event.getMessage)

      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }

  // ========== DELETE ==========

  private def handleDelete(event: ButtonInteractionEvent, eventId: Int): Unit = {
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to delete this event. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }

    event.deferReply(true).queue()

    eventService.deleteEvent(eventId) match {
      case Success(_) =>
        event.getHook.sendMessage("✅ Event deleted successfully").queue()
        event.getMessage.delete().queue(
          _ => logger.info(s"Deleted event message for event $eventId"),
          error => logger.error(s"Failed to delete message: ${error.getMessage}")
        )

      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").queue()
    }
  }

  // ========== EDIT — MULTI-STEP ==========

  /**
   * Otwiera Step 1 edycji (title + description + datetime)
   */
  private def handleEdit(event: ButtonInteractionEvent, eventId: Int): Unit = {
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to edit this event. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }

    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()

      case Some(existingEvent) =>
        val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")

        val modal = Modal.create(s"event_edit_step1:$eventId", "Edit Event - Step 1/2")
          .addActionRow(
            TextInput.create("title", "Title", TextInputStyle.SHORT)
              .setValue(existingEvent.title)
              .setRequired(true)
              .build()
          )
          .addActionRow(
            TextInput.create("description", "Description", TextInputStyle.PARAGRAPH)
              .setValue(existingEvent.description.getOrElse(""))
              .setPlaceholder("Leave empty to remove description")
              .setRequired(false)
              .setMaxLength(1000)
              .build()
          )
          .addActionRow(
            TextInput.create("datetime", "Date & Time (YYYY-MM-DD HH:MM)", TextInputStyle.SHORT)
              .setValue(dateFormat.format(existingEvent.eventTime))
              .setRequired(false)
              .build()
          )
          .build()

        event.replyModal(modal).queue()
    }
  }

  // ========== MANAGE ==========

  private def handleManage(event: ButtonInteractionEvent, eventId: Int): Unit = {
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to manage signups. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }

    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()

      case Some(_) =>
        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("👥 Manage Event Signups")
          .setDescription("Select an action:")
          .setColor(java.awt.Color.BLUE)
          .build()

        val removeButton = net.dv8tion.jda.api.interactions.components.buttons.Button.danger(
          s"event:manage_show_remove:$eventId:${event.getUser.getIdLong}", "Remove User"
        )
        val addButton = net.dv8tion.jda.api.interactions.components.buttons.Button.success(
          s"event:manage_show_add:$eventId:${event.getUser.getIdLong}", "Add User"
        )
        val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
          s"event:cancel_manage:$eventId", "Cancel"
        )

        event.replyEmbeds(embed)
          .addActionRow(removeButton, addButton, cancelButton)
          .setEphemeral(true)
          .queue()
    }
  }

  private def handleShowRemoveList(event: ButtonInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid button ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    val signupsByRole = eventService.getSignupsByRole(eventId)
    val allSignups = signupsByRole.values.flatten.toList

    if (allSignups.isEmpty) {
      event.reply("❌ No users are signed up for this event").setEphemeral(true).queue()
      return
    }

    event.deferReply(true).queue()

    val guild = event.getGuild

    val selectMenu = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
      .create(s"event:remove_select:$eventId:$adminUserId")
      .setPlaceholder("Select users to remove")
      .setMinValues(1)
      .setMaxValues(math.min(allSignups.size, 25))

    allSignups.take(25).foreach { signup =>
      val member = guild.getMemberById(signup.userId)
      val displayName = if (member != null) {
        Option(member.getNickname()).getOrElse(member.getUser.getName)
      } else {
        s"User ${signup.userId}"
      }
      selectMenu.addOption(s"$displayName (${signup.role.name})", signup.userId.toString)
    }

    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("👥 Remove Users from Event")
      .setDescription("Select users to remove:")
      .setColor(java.awt.Color.RED)
      .build()

    val removeButton = net.dv8tion.jda.api.interactions.components.buttons.Button.danger(
      s"event:confirm_remove:$eventId:$adminUserId", "Remove Selected"
    )
    val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
      s"event:cancel_manage:$eventId", "Cancel"
    )

    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu.build())
      .addActionRow(removeButton, cancelButton)
      .setEphemeral(true)
      .queue()
  }

  private def handleShowAddList(event: ButtonInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid button ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    val signupsByRole = eventService.getSignupsByRole(eventId)
    val signedUpUserIds = signupsByRole.values.flatten.map(_.userId).toSet

    val guild = event.getGuild
    val availableMembers = guild.getMembers.asScala
      .filter(m => !m.getUser.isBot && !signedUpUserIds.contains(m.getIdLong))
      .sortBy(m => Option(m.getNickname()).getOrElse(m.getUser.getName).toLowerCase)
      .take(25)

    if (availableMembers.isEmpty) {
      event.reply("❌ All members are already signed up or no members available").setEphemeral(true).queue()
      return
    }

    event.deferReply(true).queue()

    val selectMenu = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
      .create(s"event:add_select:$eventId:$adminUserId")
      .setPlaceholder("Select a user to add")
      .setMinValues(1)
      .setMaxValues(1)

    availableMembers.foreach { member =>
      val displayName = Option(member.getNickname()).getOrElse(member.getUser.getName)
      selectMenu.addOption(displayName, member.getIdLong.toString)
    }

    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("👥 Add User to Event")
      .setDescription("Select a user to add:")
      .setColor(java.awt.Color.GREEN)
      .build()

    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu.build())
      .setEphemeral(true)
      .queue()
  }

  private def handleConfirmRemove(event: ButtonInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid button ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    event.deferReply(true).queue()

    val key = s"$eventId:$adminUserId"
    selectedUsersToRemove.get(key) match {
      case None =>
        event.getHook.sendMessage("❌ No users selected. Please select users from the dropdown first.").queue()

      case Some(userIds) =>
        var removed = 0
        var errors = 0

        userIds.foreach { userId =>
          eventService.unsignupUser(eventId, userId) match {
            case Success(_) => removed += 1
            case Failure(_) => errors += 1
          }
        }

        selectedUsersToRemove.remove(key)

        eventService.getEvent(eventId).foreach { evt =>
          val channel = Option(jda.getTextChannelById(evt.channelId))
            .orElse(Option(jda.getThreadChannelById(evt.channelId)))
            .map(_.asInstanceOf[net.dv8tion.jda.api.entities.channel.middleman.MessageChannel])
            .orNull
          if (channel != null) {
            channel.retrieveMessageById(evt.messageId).queue { msg =>
              updateEventMessage(eventId, msg)
            }
          }
        }

        val message = if (errors == 0)
          s"✅ Successfully removed **$removed** user(s) from the event"
        else
          s"✅ Removed **$removed** user(s), failed to remove **$errors**"

        event.getHook.sendMessage(message).queue(
          _ => event.getMessage.delete().queue(
            _ => logger.debug("Removed manage dialog after confirmation"),
            error => logger.warn(s"Could not delete manage dialog: ${error.getMessage}")
          )
        )
    }
  }

  private def handleConfirmAdd(event: ButtonInteractionEvent): Unit = {
    // Używamy modala/dropdown flow - ten handler nie jest już potrzebny
  }

  // ========== STRING SELECT HANDLERS ==========

  private def handleRemoveSelection(event: StringSelectInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid selection ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    val selectedUserIds = event.getValues.asScala.toList.map(_.toLong)
    selectedUsersToRemove.put(s"$eventId:$adminUserId", selectedUserIds)

    val count = selectedUserIds.size
    event.reply(s"✅ Selected **$count** user(s) to remove. Click **Remove Selected** to confirm.").setEphemeral(true).queue()
  }

  private def handleAddSelection(event: StringSelectInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid selection ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    val selectedUserId = event.getValues.get(0).toLong
    val key = s"add:$eventId:$adminUserId"
    eventCommand.setSelectedUserToAdd(key, List(selectedUserId))

    event.deferReply(true).queue()

    val guild = event.getGuild
    val member = guild.getMemberById(selectedUserId)
    val displayName = if (member != null) {
      Option(member.getNickname()).getOrElse(member.getUser.getName)
    } else {
      s"User $selectedUserId"
    }

    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("👥 Select Role")
      .setDescription(s"Select a role for **$displayName**:")
      .setColor(java.awt.Color.BLUE)
      .build()

    val roleSelect = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
      .create(s"event:add_role_select:$eventId:$adminUserId")
      .setPlaceholder("Select role")
      .addOption("🛡 Tank", "tank", "Tank role")
      .addOption("💚 Healer", "healer", "Healer role")
      .addOption("⚔ Damage", "dps", "Damage dealer role")
      .addOption("⏳ Waitlist", "waitlist", "Waitlist")
      .build()

    val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
      s"event:cancel_manage:$eventId", "Cancel"
    )

    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(roleSelect)
      .addActionRow(cancelButton)
      .setEphemeral(true)
      .queue()
  }

  private def handleAddRoleSelection(event: StringSelectInteractionEvent): Unit = {
    val parts = event.getComponentId.split(":")
    if (parts.length < 4) {
      event.reply("❌ Invalid selection ID").setEphemeral(true).queue()
      return
    }

    val eventId = parts(2).toInt
    val adminUserId = parts(3).toLong

    if (adminUserId != event.getUser.getIdLong) {
      event.reply("❌ This is not your manage session").setEphemeral(true).queue()
      return
    }

    event.deferReply(true).queue()

    val key = s"add:$eventId:$adminUserId"
    eventCommand.getSelectedUserToAdd(key) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please try again.").queue()

      case Some(userIds) if userIds.isEmpty =>
        event.getHook.sendMessage("❌ No user selected. Please try again.").queue()

      case Some(userIds) =>
        val userId = userIds.head
        val roleStr = event.getValues.get(0)

        EventRole.fromString(roleStr) match {
          case None =>
            event.getHook.sendMessage(s"❌ Invalid role: `$roleStr`").queue()

          case Some(role) =>
            eventCommand.addUserToEvent(eventId, userId, role) match {
              case Success(assignedRole) =>
                val message = if (assignedRole == role)
                  s"✅ Added <@$userId> as **${role.name}**"
                else
                  s"✅ Added <@$userId> to **${assignedRole.name}** (${role.name} was full)"

                event.getHook.sendMessage(message).queue()
                eventCommand.clearSelectedUserToAdd(key)

              case Failure(e) =>
                event.getHook.sendMessage(s"❌ Failed to add user: ${e.getMessage}").queue()
                eventCommand.clearSelectedUserToAdd(key)
            }
        }
    }
  }

  private def handleRoleSelection(event: StringSelectInteractionEvent): Unit = {
    val selectId = event.getComponentId
    val userId = selectId.split(":").last.toLong

    if (userId != event.getUser.getIdLong) {
      event.reply("❌ This is not your event creation session").setEphemeral(true).queue()
      return
    }

    val selectedValue = event.getValues.get(0)

    val roleId: Option[Long] = selectedValue match {
      case "everyone" => Some(-1L)
      case "here"     => Some(-2L)
      case id         => Some(id.toLong)
    }

    eventCommand.getCreationState(userId) match {
      case None =>
        event.reply("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()

      case Some(state) =>
        val updatedState = state.copy(mentionRoleId = roleId)
        eventCommand.updateCreationState(userId, updatedState)

        val roleName = selectedValue match {
          case "everyone" => "@everyone"
          case "here"     => "@here"
          case id         => event.getGuild.getRoleById(id).getName
        }

        event.reply(s"✅ Selected role: **$roleName**\n\nClick **Continue** button to proceed to date & time.").setEphemeral(true).queue()
    }
  }

  // ========== CREATION CONTINUE BUTTONS ==========

  private def handleContinueButton(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId
    val userId = buttonId.split(":").last.toLong

    if (userId != event.getUser.getIdLong) {
      event.reply("❌ This is not your event creation session").setEphemeral(true).queue()
      return
    }

    if (buttonId.contains("continue_step2")) {
      openStep2Modal(event, userId)
    } else if (buttonId.contains("continue_step3")) {
      openStep3Modal(event, userId)
    }
  }

  private def openStep2Modal(event: ButtonInteractionEvent, userId: Long): Unit = {
    val modal = Modal.create("event_create_step2", "Create Event - Step 2/3")
      .addActionRow(
        TextInput.create("date", "Date (YYYY-MM-DD)", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 2025-01-15")
          .setRequired(true).build()
      )
      .addActionRow(
        TextInput.create("time", "Time (HH:MM)", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 20:00")
          .setRequired(true).build()
      )
      .addActionRow(
        TextInput.create("reminder", "Reminder (minutes before event)", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 15")
          .setValue("15")
          .setRequired(true).build()
      )
      .build()

    event.replyModal(modal).queue()
  }

  private def openStep3Modal(event: ButtonInteractionEvent, userId: Long): Unit = {
    val modal = Modal.create("event_create_step3", "Create Event - Step 3/3")
      .addActionRow(
        TextInput.create("tank", "Tank slots", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 1")
          .setRequired(true).build()
      )
      .addActionRow(
        TextInput.create("healer", "Healer slots", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 2")
          .setRequired(true).build()
      )
      .addActionRow(
        TextInput.create("dps", "DPS slots", TextInputStyle.SHORT)
          .setPlaceholder("e.g. 5")
          .setRequired(true).build()
      )
      .build()

    event.replyModal(modal).queue()
  }

  // ========== HELPERS ==========

  private def hasAdminPermissions(event: ButtonInteractionEvent): Boolean = {
    val member = event.getMember
    if (member == null) return false
    member.hasPermission(Permission.MANAGE_CHANNEL) ||
    member.hasPermission(Permission.ADMINISTRATOR)
  }

  private def updateEventMessage(eventId: Int, message: net.dv8tion.jda.api.entities.Message): Unit = {
    eventService.getEvent(eventId) match {
      case None =>
        logger.error(s"Event $eventId not found")

      case Some(event) =>
        val signupsByRole = eventService.getSignupsByRole(eventId)
        val embed = embedBuilder.buildEventEmbed(event, signupsByRole)
        val buttons = EventButtons.createEventButtons(eventId, event.active)

        message.editMessageEmbeds(embed)
          .setComponents(buttons.asJava)
          .queue(
            _ => logger.debug(s"Updated event message for event $eventId"),
            error => logger.error(s"Failed to update message: ${error.getMessage}")
          )
    }
  }
}
