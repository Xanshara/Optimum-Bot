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
  private val selectedUsersToRemove = new TrieMap[String, List[Long]]() // key: "eventId:userId", value: list of userIds to remove
  
  override def onButtonInteraction(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId
    
    // Sprawdź czy to przycisk eventu
    if (!buttonId.startsWith("event:")) return
    
    // Multi-step continue buttons
    if (buttonId.startsWith("event:continue_step")) {
      handleContinueButton(event)
      return
    }
    
    // Manage buttons - NOWE
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
      // Edytuj wiadomość na pustą (ephemeral nie można usunąć)
      event.editMessage("❌ Cancelled")
        .setComponents()  // Usuń wszystkie przyciski/dropdowny
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
          case "tank" => 
            logger.info(s"Matched action 'tank' -> calling handleRoleSignup with EventRole.Tank")
            handleRoleSignup(event, eventId, EventRole.Tank)
          case "healer" => 
            logger.info(s"Matched action 'healer' -> calling handleRoleSignup with EventRole.Healer")
            handleRoleSignup(event, eventId, EventRole.Healer)
          case "dps" => 
            logger.info(s"Matched action 'dps' -> calling handleRoleSignup with EventRole.DPS")
            handleRoleSignup(event, eventId, EventRole.DPS)
          case "waitlist" => 
            logger.info(s"Matched action 'waitlist' -> calling handleRoleSignup with EventRole.Waitlist")
            handleRoleSignup(event, eventId, EventRole.Waitlist)  // POPRAWKA: używamy tej samej funkcji!
          case "leave" => handleLeave(event, eventId)
          case "manage" => handleManage(event, eventId)
          case "edit" => handleEdit(event, eventId)
          case "delete" => handleDelete(event, eventId)
          case _ =>
            logger.warn(s"Unknown action: '$action'")
            event.reply("❌ Unknown action").setEphemeral(true).queue()
        }
    }
  }
  
  override def onStringSelectInteraction(event: StringSelectInteractionEvent): Unit = {
    val selectId = event.getComponentId
    
    // Handle role selection dropdown
    if (selectId.startsWith("event:select_role:")) {
      handleRoleSelection(event)
      return
    }
    
    // Handle manage remove selection
    if (selectId.startsWith("event:remove_select:")) {
      handleRemoveSelection(event)
      return
    }
    
    // Handle manage add selection
    if (selectId.startsWith("event:add_select:")) {
      handleAddSelection(event)
      return
    }
    
    // Handle role selection for adding user - NOWE
    if (selectId.startsWith("event:add_role_select:")) {
      handleAddRoleSelection(event)
      return
    }
  }
  
  /**
   * Obsługuje wybór użytkowników do usunięcia - NOWE
   */
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
    
    // Zapisz wybrane user IDs
    val selectedUserIds = event.getValues.asScala.toList.map(_.toLong)
    selectedUsersToRemove.put(s"$eventId:$adminUserId", selectedUserIds)
    
    val count = selectedUserIds.size
    event.reply(s"✅ Selected **$count** user(s) to remove. Click **Remove Selected** to confirm.").setEphemeral(true).queue()
  }
  
  /**
   * Obsługuje wybór użytkowników do dodania - NOWE
   */
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
    
    // Pobierz wybranego użytkownika (tylko 1)
    val selectedUserId = event.getValues.get(0).toLong
    
    // Zapisz tymczasowo w EventCommand
    val key = s"add:$eventId:$adminUserId"
    eventCommand.setSelectedUserToAdd(key, List(selectedUserId))
    
    // Pokaż dropdown do wyboru roli (zamiast modala)
    event.deferReply(true).queue()
    
    val guild = event.getGuild
    val member = guild.getMemberById(selectedUserId)
    
    // POPRAWKA: Wymuś nick serwerowy, jeśli nie ma to username
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
      s"event:cancel_manage:$eventId",
      "Cancel"
    )
    
    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(roleSelect)
      .addActionRow(cancelButton)
      .setEphemeral(true)
      .queue()
  }
  
  /**
   * Obsługuje wybór roli dla dodawanego użytkownika - NOWE
   */
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
    
    // Pobierz zapisany user ID
    val key = s"add:$eventId:$adminUserId"
    eventCommand.getSelectedUserToAdd(key) match {
      case None =>
        event.getHook.sendMessage("❌ Session expired. Please try again.").queue()
        
      case Some(userIds) if userIds.isEmpty =>
        event.getHook.sendMessage("❌ No user selected. Please try again.").queue()
        
      case Some(userIds) =>
        val userId = userIds.head
        val roleStr = event.getValues.get(0)  // "tank", "healer", "dps", "waitlist"
        
        EventRole.fromString(roleStr) match {
          case None =>
            event.getHook.sendMessage(s"❌ Invalid role: `$roleStr`").queue()
            
          case Some(role) =>
            // Dodaj użytkownika
            eventCommand.addUserToEvent(eventId, userId, role) match {
              case Success(assignedRole) =>
                val message = if (assignedRole == role) {
                  s"✅ Added <@$userId> as **${role.name}**"
                } else {
                  s"✅ Added <@$userId> to **${assignedRole.name}** (${role.name} was full)"
                }
                
                event.getHook.sendMessage(message).queue()
                
                // Wyczyść zapisany wybór
                eventCommand.clearSelectedUserToAdd(key)
                
              case Failure(e) =>
                event.getHook.sendMessage(s"❌ Failed to add user: ${e.getMessage}").queue()
                eventCommand.clearSelectedUserToAdd(key)
            }
        }
    }
  }
  
  /**
   * Obsługuje dropdown wyboru roli
   */
  private def handleRoleSelection(event: StringSelectInteractionEvent): Unit = {
    val selectId = event.getComponentId
    val userId = selectId.split(":").last.toLong
    
    if (userId != event.getUser.getIdLong) {
      event.reply("❌ This is not your event creation session").setEphemeral(true).queue()
      return
    }
    
    val selectedValue = event.getValues.get(0)
    
    // Map special values
    val roleId: Option[Long] = selectedValue match {
      case "everyone" => Some(-1L)  // Special marker for @everyone
      case "here" => Some(-2L)      // Special marker for @here
      case id => Some(id.toLong)    // Actual role ID
    }
    
    // Update creation state
    eventCommand.getCreationState(userId) match {
      case None =>
        event.reply("❌ Session expired. Please start over with `/event create`").setEphemeral(true).queue()
        
      case Some(state) =>
        val updatedState = state.copy(mentionRoleId = roleId)
        eventCommand.updateCreationState(userId, updatedState)
        
        val roleName = selectedValue match {
          case "everyone" => "@everyone"
          case "here" => "@here"
          case id => event.getGuild.getRoleById(id).getName
        }
        
        event.reply(s"✅ Selected role: **$roleName**\n\nClick **Continue** button to proceed to date & time.").setEphemeral(true).queue()
    }
  }
  
  /**
   * Obsługuje przyciski Continue (step2, step3)
   */
  private def handleContinueButton(event: ButtonInteractionEvent): Unit = {
    val buttonId = event.getComponentId
    val userId = buttonId.split(":").last.toLong
    
    if (userId != event.getUser.getIdLong) {
      event.reply("❌ This is not your event creation session").setEphemeral(true).queue()
      return
    }
    
    if (buttonId.contains("continue_step2")) {
      // Open Step 2 Modal
      openStep2Modal(event, userId)
    } else if (buttonId.contains("continue_step3")) {
      // Open Step 3 Modal
      openStep3Modal(event, userId)
    }
  }
  
  /**
   * Otwiera modal Step 2 (Date + Time + Reminder)
   */
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
  
  /**
   * Otwiera modal Step 3 (Tank + Healer + DPS)
   */
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
  
  /**
   * Obsługuje zarządzanie uczestnikami eventu - NOWY UPROSZCZONY
   */
  private def handleManage(event: ButtonInteractionEvent, eventId: Int): Unit = {
    // Sprawdź uprawnienia
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to manage signups. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }
    
    // Pobierz event
    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()
        
      case Some(existingEvent) =>
        // Pokaż TYLKO przyciski - bez dropdownu
        val embed = new net.dv8tion.jda.api.EmbedBuilder()
          .setTitle("👥 Manage Event Signups")
          .setDescription("Select an action:")
          .setColor(java.awt.Color.BLUE)
          .build()
        
        val removeButton = net.dv8tion.jda.api.interactions.components.buttons.Button.danger(
          s"event:manage_show_remove:$eventId:${event.getUser.getIdLong}",
          "Remove User"
        )
        
        val addButton = net.dv8tion.jda.api.interactions.components.buttons.Button.success(
          s"event:manage_show_add:$eventId:${event.getUser.getIdLong}",
          "Add User"
        )
        
        val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
          s"event:cancel_manage:$eventId",
          "Cancel"
        )
        
        event.replyEmbeds(embed)
          .addActionRow(removeButton, addButton, cancelButton)
          .setEphemeral(true)
          .queue()
    }
  }
  
  /**
   * Pokazuje listę użytkowników do usunięcia - NOWE
   */
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
    
    // Pobierz zapisanych użytkowników
    val signupsByRole = eventService.getSignupsByRole(eventId)
    val allSignups = signupsByRole.values.flatten.toList.sortBy(_.joinedAt.getTime)
    
    if (allSignups.isEmpty) {
      event.reply("❌ No users are signed up for this event").setEphemeral(true).queue()
      return
    }
    
    // Wyślij nową wiadomość ephemeral (nie edytujemy starej)
    event.deferReply(true).queue()
    
    val guild = event.getGuild
    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("👥 Remove Users from Event")
      .setDescription("Select users to remove:")
      .setColor(java.awt.Color.RED)
      .build()
    
    val selectMenu = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
      .create(s"event:remove_select:$eventId:$adminUserId")
      .setPlaceholder("Select users to remove")
      .setMinValues(1)
      .setMaxValues(Math.min(allSignups.size, 25))
      .addOptions(allSignups.take(25).map { signup =>
        val roleName = signup.role.emoji + " " + signup.role.name
        
        // POPRAWKA: Wymuś nick serwerowy, jeśli nie ma to username
        val member = guild.getMemberById(signup.userId)
        val displayName = if (member != null) {
          Option(member.getNickname()).getOrElse(member.getUser.getName)
        } else {
          s"User ${signup.userId}"
        }
        
        net.dv8tion.jda.api.interactions.components.selections.SelectOption.of(
          s"$displayName ($roleName)",
          signup.userId.toString
        ).withDescription(s"ID: ${signup.userId}")
      }.asJava)
      .build()
    
    val removeButton = net.dv8tion.jda.api.interactions.components.buttons.Button.danger(
      s"event:confirm_remove:$eventId:$adminUserId",
      "Remove Selected"
    )
    
    val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
      s"event:cancel_manage:$eventId",
      "Cancel"
    )
    
    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu)
      .addActionRow(removeButton, cancelButton)
      .setEphemeral(true)
      .queue()
  }
  
  /**
   * Pokazuje listę użytkowników do dodania - NOWE
   */
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
    
    // Pobierz zapisanych użytkowników
    val signupsByRole = eventService.getSignupsByRole(eventId)
    val signedUpUserIds = signupsByRole.values.flatten.map(_.userId).toSet
    
    // Pobierz wszystkich członków serwera (którzy nie są zapisani)
    val guild = event.getGuild
    val availableMembers = guild.getMembers.asScala
      .filter(m => !m.getUser.isBot && !signedUpUserIds.contains(m.getIdLong))
      .sortBy(m => Option(m.getNickname()).getOrElse(m.getUser.getName).toLowerCase)  // Sortuj po nicku serwerowym
      .take(25)  // Discord limit
    
    if (availableMembers.isEmpty) {
      event.reply("❌ All members are already signed up or no members available").setEphemeral(true).queue()
      return
    }
    
    // Wyślij nową wiadomość ephemeral (nie edytujemy starej)
    event.deferReply(true).queue()
    
    val embed = new net.dv8tion.jda.api.EmbedBuilder()
      .setTitle("👥 Add User to Event")
      .setDescription("Select a user to add:")
      .setColor(java.awt.Color.GREEN)
      .build()
    
    val selectMenu = net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu
      .create(s"event:add_select:$eventId:$adminUserId")
      .setPlaceholder("Select user to add")
      .addOptions(availableMembers.map { member =>
        // POPRAWKA: Wymuś nick serwerowy, jeśli nie ma to username
        val displayName = Option(member.getNickname()).getOrElse(member.getUser.getName)
        
        net.dv8tion.jda.api.interactions.components.selections.SelectOption.of(
          displayName,
          member.getId
        ).withDescription(s"ID: ${member.getId}")
      }.asJava)
      .build()
    
    val cancelButton = net.dv8tion.jda.api.interactions.components.buttons.Button.secondary(
      s"event:cancel_manage:$eventId",
      "Cancel"
    )
    
    event.getHook.sendMessageEmbeds(embed)
      .addActionRow(selectMenu)
      .addActionRow(cancelButton)
      .setEphemeral(true)
      .queue()
  }
  
  /**
   * Obsługuje potwierdzenie usunięcia wybranych użytkowników
   */
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
    
    // Pobierz zapisane wybory
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
        
        // Wyczyść zapisane wybory
        selectedUsersToRemove.remove(key)
        
        // Aktualizuj wiadomość eventu
        eventService.getEvent(eventId).foreach { evt =>
          val channel = jda.getTextChannelById(evt.channelId)
          if (channel != null) {
            channel.retrieveMessageById(evt.messageId).queue { msg =>
              updateEventMessage(eventId, msg)
            }
          }
        }
        
        val message = if (errors == 0) {
          s"✅ Successfully removed **$removed** user(s) from the event"
        } else {
          s"✅ Removed **$removed** user(s), failed to remove **$errors**"
        }
        
        // Wyślij odpowiedź, potem usuń starą wiadomość
        event.getHook.sendMessage(message).queue(
          _ => {
            // Usuń poprzednią wiadomość z dropdown
            event.getMessage.delete().queue(
              _ => logger.debug("Removed manage dialog after confirmation"),
              error => logger.warn(s"Could not delete manage dialog: ${error.getMessage}")
            )
          }
        )
    }
  }
  
  /**
   * Obsługuje potwierdzenie dodania użytkownika - NOWE
   */
  private def handleConfirmAdd(event: ButtonInteractionEvent): Unit = {
    // To nie jest już potrzebne - używamy modala
  }
  
  /**
   * Obsługuje edycję eventu
   */
  private def handleEdit(event: ButtonInteractionEvent, eventId: Int): Unit = {
    // Sprawdź uprawnienia
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to edit this event. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }
    
    // Pobierz event
    eventService.getEvent(eventId) match {
      case None =>
        event.reply("❌ Event not found").setEphemeral(true).queue()
        
      case Some(existingEvent) =>
        // Otwórz modal do edycji
        val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        val eventTimeStr = dateFormat.format(existingEvent.eventTime)
        
        val modal = Modal.create(s"event_edit:$eventId", "Edit Event")
          .addActionRow(
            TextInput.create("title", "Title", TextInputStyle.SHORT)
              .setValue(existingEvent.title)
              .setRequired(true).build()
          )
          .addActionRow(
            TextInput.create("datetime", "Date & Time (YYYY-MM-DD HH:MM)", TextInputStyle.SHORT)
              .setValue(eventTimeStr)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("tank", "Tank slots", TextInputStyle.SHORT)
              .setValue(existingEvent.tankLimit.toString)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("healer", "Healer slots", TextInputStyle.SHORT)
              .setValue(existingEvent.healerLimit.toString)
              .setRequired(false).build()
          )
          .addActionRow(
            TextInput.create("dps", "DPS slots", TextInputStyle.SHORT)
              .setValue(existingEvent.dpsLimit.toString)
              .setRequired(false).build()
          )
          .build()
        
        event.replyModal(modal).queue()
    }
  }
  
  /**
   * Obsługuje usunięcie eventu
   */
  private def handleDelete(event: ButtonInteractionEvent, eventId: Int): Unit = {
    // Sprawdź uprawnienia
    if (!hasAdminPermissions(event)) {
      event.reply("❌ You don't have permission to delete this event. Required: MANAGE_CHANNEL or ADMINISTRATOR").setEphemeral(true).queue()
      return
    }
    
    event.deferReply(true).queue()
    
    eventService.deleteEvent(eventId) match {
      case Success(_) =>
        event.getHook.sendMessage("✅ Event deleted successfully").queue()
        // Usuń wiadomość eventu
        event.getMessage.delete().queue(
          _ => logger.info(s"Deleted event message for event $eventId"),
          error => logger.error(s"Failed to delete message: ${error.getMessage}")
        )
        
      case Failure(e) =>
        event.getHook.sendMessage(s"❌ ${e.getMessage}").queue()
    }
  }
  
  /**
   * Obsługuje zapis na rolę
   */
  private def handleRoleSignup(event: ButtonInteractionEvent, eventId: Int, role: EventRole): Unit = {
    // Tylko acknowledge bez wysyłania wiadomości
    event.deferEdit().queue()
    
    val userId = event.getUser.getIdLong
    
    logger.info(s"User $userId clicked button for role: ${role.name}")
    
    eventService.signupUser(eventId, userId, role) match {
      case Success(assignedRole) =>
        logger.info(s"User $userId assigned to role: ${assignedRole.name}")
        // Tylko aktualizuj event - BEZ wysyłania wiadomości!
        updateEventMessage(eventId, event.getMessage)
        
      case Failure(e) if e.getMessage == "UNREGISTER" =>
        // Użytkownik kliknął w swoją obecną rolę → został wypisany
        logger.info(s"User $userId unregistered from event $eventId")
        // Tylko aktualizuj event - BEZ wysyłania wiadomości!
        updateEventMessage(eventId, event.getMessage)
        
      case Failure(e) =>
        logger.error(s"Failed to signup user $userId for role ${role.name}: ${e.getMessage}")
        // Tylko w przypadku błędu - pokaż wiadomość ephemeral
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }
  
  /**
   * Obsługuje wypisanie się
   */
  private def handleLeave(event: ButtonInteractionEvent, eventId: Int): Unit = {
    // Tylko acknowledge bez wysyłania wiadomości
    event.deferEdit().queue()
    
    val userId = event.getUser.getIdLong
    
    eventService.unsignupUser(eventId, userId) match {
      case Success(_) =>
        // Tylko aktualizuj event - BEZ wysyłania wiadomości!
        updateEventMessage(eventId, event.getMessage)
        
      case Failure(e) =>
        // Tylko w przypadku błędu - pokaż wiadomość ephemeral
        event.getHook.sendMessage(s"❌ ${e.getMessage}").setEphemeral(true).queue()
    }
  }
  
  /**
   * Sprawdza czy użytkownik ma uprawnienia admina
   */
  private def hasAdminPermissions(event: ButtonInteractionEvent): Boolean = {
    val member = event.getMember
    if (member == null) return false
    
    member.hasPermission(Permission.MANAGE_CHANNEL) ||
    member.hasPermission(Permission.ADMINISTRATOR)
  }
  
  /**
   * Aktualizuje wiadomość eventu
   */
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
